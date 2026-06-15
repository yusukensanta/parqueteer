package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models._
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.CloudAuthException
import io.github.yusukensanta.parqueteer.cloud.CloudCredentialManager
import com.github.mjakubowski84.parquet4s.{
  ParquetReader,
  RowParquetRecord,
  Path => Parquet4sPath,
  Filter
}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path => HadoopPath}
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.metadata.BlockMetaData
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import scala.concurrent.{
  Future,
  Await,
  ExecutionContext,
  ExecutionContextExecutorService
}
import java.util.concurrent.Executors
import scala.util.{Try, Success, Using}
import scala.jdk.CollectionConverters._

/** Public interface for Parquet file I/O. Implementations may choose to use
  * Hadoop, in-memory fakes for testing, etc.
  */
trait ParquetRepository {
  def readContent(file: ParquetFile, config: ReadConfig): Try[FileContent]
  def streamContent(
      file: ParquetFile,
      config: ReadConfig
  )(process: Map[String, CellValue] => Unit): Try[Long]
  def readSchema(file: ParquetFile): Try[ParquetSchema]
  def readFileInfo(
      file: ParquetFile
  ): Try[(ParquetSchema, FileMetadata, List[RowGroupInfo])]
  def readMetadata(file: ParquetFile): Try[FileMetadata]
  def writeContent(
      location: StorageLocation,
      data: List[Map[String, CellValue]],
      schema: Option[ParquetSchema],
      config: WriteConfig = WriteConfig()
  ): Try[Unit]
  def writeContentStream(
      location: StorageLocation,
      schema: ParquetSchema,
      config: WriteConfig
  )(feed: (Map[String, CellValue] => Unit) => Unit): Try[Long]
  def validateFile(file: ParquetFile, deep: Boolean = false): Try[List[String]]
  def readSchemaFields(file: ParquetFile): Try[List[FieldSummary]]
  def deleteFile(location: StorageLocation): Try[Unit]
  def readStats(file: ParquetFile): Try[FileStats]
  def cacheStats(): ParquetRepository.CacheStats =
    ParquetRepository.CacheStats(0, 0, 0, 0)
}

object ParquetRepository {
  final case class CacheStats(
      footerHits: Long,
      footerMisses: Long,
      configHits: Long,
      configMisses: Long
  )
}

object HadoopParquetRepository {
  private val shutdownHookRegistered =
    new java.util.concurrent.atomic.AtomicBoolean(false)

  // Keep type alias for source compatibility with code referencing HadoopParquetRepository.CacheStats
  type CacheStats = ParquetRepository.CacheStats
  val CacheStats: ParquetRepository.CacheStats.type =
    ParquetRepository.CacheStats
}

// The Hadoop Configuration cache is keyed on storage location type + bucket/region.
// It does not incorporate credential env vars (AWS_ACCESS_KEY_ID, etc.).
// Callers that rotate credentials mid-process must create a new repository instance.
class HadoopParquetRepository(
    profile: Option[String] = None,
    region: Option[String] = None
) extends ParquetRepository {
  private val logger =
    org.slf4j.LoggerFactory.getLogger(getClass)
  private val HadoopConfigCacheMaxSize = 64
  private val FooterCacheMaxSize = 1024
  private val hadoopConfigCache: java.util.Map[String, Configuration] =
    java.util.Collections.synchronizedMap(
      new java.util.LinkedHashMap[String, Configuration](
        16,
        0.75f,
        true
      ) {
        override def removeEldestEntry(
            eldest: java.util.Map.Entry[String, Configuration]
        ): Boolean = size() > HadoopConfigCacheMaxSize
      }
    )

  // Caches (MessageType, blocks) per file path for the lifetime of this repository instance.
  // Bounded LRU: evicts the least-recently-used entry when size exceeds FooterCacheMaxSize so
  // large multi-file merges don't grow the cache unboundedly.
  private val footerCache
      : java.util.Map[String, (MessageType, List[BlockMetaData])] =
    java.util.Collections.synchronizedMap(
      new java.util.LinkedHashMap[String, (MessageType, List[BlockMetaData])](
        16,
        0.75f,
        true
      ) {
        override def removeEldestEntry(
            eldest: java.util.Map.Entry[
              String,
              (MessageType, List[BlockMetaData])
            ]
        ): Boolean = size() > FooterCacheMaxSize
      }
    )

  private val footerCacheHits =
    new java.util.concurrent.atomic.AtomicLong(0)
  private val footerCacheMisses =
    new java.util.concurrent.atomic.AtomicLong(0)
  private val configCacheHits =
    new java.util.concurrent.atomic.AtomicLong(0)
  private val configCacheMisses =
    new java.util.concurrent.atomic.AtomicLong(0)

  override def cacheStats(): ParquetRepository.CacheStats =
    ParquetRepository.CacheStats(
      footerHits = footerCacheHits.get(),
      footerMisses = footerCacheMisses.get(),
      configHits = configCacheHits.get(),
      configMisses = configCacheMisses.get()
    )

  private val metadataConverter =
    new org.apache.parquet.format.converter.ParquetMetadataConverter()

  private def configCacheKey(location: StorageLocation): String =
    location match {
      case LocalPath(_) => "local"
      case S3Location(bucket, _, r) =>
        s"s3:$bucket:${r.orElse(region).getOrElse("")}"
      case GCSLocation(bucket, _) =>
        s"gcs:${profile.getOrElse("")}:$bucket"
      case AzureLocation(account, cont, _) =>
        s"azure:${profile.getOrElse("")}:$account/$cont"
    }

  // Close S3A/GCS/ABFS connection pools on JVM exit to prevent background thread leaks
  if (HadoopParquetRepository.shutdownHookRegistered.compareAndSet(false, true))
    Runtime.getRuntime.addShutdownHook(
      new Thread(() =>
        try FileSystem.closeAll()
        catch { case _: Throwable => () }
      )
    )

  // ── Shared footer infrastructure ──────────────────────────────────────────

  private val parquetMagic = Array[Byte]('P', 'A', 'R', '1')

  private def readFooterBytes(inputFile: HadoopInputFile): Array[Byte] =
    Using.resource(inputFile.newStream()) { stream =>
      val fileLen = inputFile.getLength
      if (fileLen < 12)
        throw new java.io.IOException(
          s"File too small to be a valid Parquet file (${fileLen} bytes)"
        )
      val tail = new Array[Byte](8)
      stream.seek(fileLen - 8)
      stream.readFully(tail)
      if (
        tail(4) != parquetMagic(0) || tail(5) != parquetMagic(1) ||
        tail(6) != parquetMagic(2) || tail(7) != parquetMagic(3)
      )
        throw new java.io.IOException(
          "File does not end with PAR1 magic bytes — not a valid Parquet file"
        )
      val footerLen = java.nio.ByteBuffer
        .wrap(tail, 0, 4)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .getInt
      val MaxFooterBytes = 256 * 1024 * 1024
      if (
        footerLen <= 0 || footerLen > fileLen - 8 || footerLen > MaxFooterBytes
      )
        throw new java.io.IOException(
          s"Invalid Parquet footer length: $footerLen (file length: $fileLen, max: ${MaxFooterBytes}B)"
        )
      stream.seek(fileLen - 8 - footerLen)
      val footerBytes = new Array[Byte](footerLen)
      stream.readFully(footerBytes)
      footerBytes
    }

  private def parseFooter(
      footerBytes: Array[Byte]
  ): org.apache.parquet.hadoop.metadata.ParquetMetadata =
    metadataConverter.readParquetMetadata(
      new java.io.ByteArrayInputStream(footerBytes),
      org.apache.parquet.format.converter.ParquetMetadataConverter.NO_FILTER
    )

  // Returns (formatVersion, createdBy) from the raw Thrift footer
  private def parseRawMeta(footerBytes: Array[Byte]): (String, String) = {
    val raw = org.apache.parquet.format.Util.readFileMetaData(
      new java.io.ByteArrayInputStream(footerBytes)
    )
    (
      if (raw.version == 2) "2.0" else "1.0",
      Option(raw.created_by).getOrElse("")
    )
  }

  private def buildParquetSchema(
      msgSchema: MessageType,
      blocks: List[BlockMetaData]
  ): ParquetSchema = {
    val chunkMap = blocks.headOption
      .map(
        _.getColumns.asScala
          .map(c => c.getPath.toDotString -> c)
          .toMap
      )
      .getOrElse(Map.empty)
    val columns = msgSchema.getColumns.asScala.map { col =>
      val colPath = col.getPath.mkString(".")
      val pt = col.getPrimitiveType
      val chunk = chunkMap.get(colPath)
      ColumnInfo(
        name = colPath,
        dataType =
          logicalTypeName(pt.getPrimitiveTypeName, pt.getLogicalTypeAnnotation),
        isOptional =
          pt.getRepetition == org.apache.parquet.schema.Type.Repetition.OPTIONAL,
        maxDefinitionLevel = col.getMaxDefinitionLevel,
        maxRepetitionLevel = col.getMaxRepetitionLevel,
        compressionType = chunk.map(_.getCodec.name()).getOrElse("UNKNOWN"),
        encodings = chunk
          .map(_.getEncodings.asScala.map(_.name()).toList.sorted.distinct)
          .getOrElse(Nil)
      )
    }.toList
    ParquetSchema(
      columns = columns,
      rowGroupCount = blocks.size.toLong,
      totalRowCount = blocks.map(_.getRowCount).sum
    )
  }

  // Cache-aware footer fetch: 0 cloud ops on hit, 1 stat + 1 stream on miss.
  // LRU eviction is handled by the LinkedHashMap's removeEldestEntry override.
  private def getFooter(
      path: HadoopPath,
      conf: Configuration
  ): (MessageType, List[BlockMetaData]) = {
    val key = path.toString
    Option(footerCache.get(key)) match {
      case Some(cached) =>
        footerCacheHits.incrementAndGet()
        cached
      case None =>
        footerCacheMisses.incrementAndGet()
        val footerBytes = readFooterBytes(HadoopInputFile.fromPath(path, conf))
        val meta = parseFooter(footerBytes)
        val entry =
          (meta.getFileMetaData.getSchema, meta.getBlocks.asScala.toList)
        footerCache.put(key, entry)
        entry
    }
  }

  // ── Public API ────────────────────────────────────────────────────────────

  def readContent(file: ParquetFile, config: ReadConfig): Try[FileContent] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      val cacheKey = new HadoopPath(file.location.path).toString
      val result = Try {
        val hadoopPath = new HadoopPath(file.location.path)
        val (fileSchema, blocks) = getFooter(hadoopPath, hadoopConfig)
        val totalRows = blocks.map(_.getRowCount).sum

        // filter forces sequential: parquet4s evaluates predicates during
        // deserialization, not at page-selection time, so parallel reads can't
        // short-circuit and the overhead exceeds any concurrency benefit.
        val useParallel = config.parallelism > 1 && config.filter.isEmpty
        val (rows, hasMoreAfterLimit) =
          if (useParallel)
            // getFooter above is already cached; readParallel reuses it
            (readParallel(hadoopPath, hadoopConfig, config), false)
          else {
            val path4s = Parquet4sPath(file.location.path)
            val rawBinaryFields =
              ParquetRecordDecoder.rawBinaryFieldsFor(fileSchema)
            val int96Fields =
              ParquetRecordDecoder.int96FieldsFor(fileSchema)
            val temporalTransformer =
              ParquetRecordDecoder.buildTemporalTransformer(fileSchema)
            Using.resource(
              openParquetReader(path4s, hadoopConfig, config, fileSchema)
            ) { reader =>
              // Hold a reference to the underlying iterator so we can peek after
              // the take — reader is a ParquetIterable (Iterable), not an Iterator.
              val baseIter = reader.iterator
              val taken = applyMaxRows(
                new IterableOnce[RowParquetRecord] {
                  def iterator: Iterator[RowParquetRecord] = baseIter
                },
                config.maxRows
              ).map(r =>
                ParquetRecordDecoder.applyTemporalTransformer(
                  ParquetRecordDecoder.convertRecordToMapWithSchema(
                    r,
                    rawBinaryFields,
                    int96Fields
                  ),
                  temporalTransformer
                )
              ).toList
              // Peek one more row to determine if more matching rows exist beyond the limit.
              // Avoids false-positive isPartial when the filter matches exactly maxRows records.
              val peek =
                config.filter.isDefined &&
                  config.maxRows.exists(taken.size.toLong >= _) &&
                  scala.util.Try(baseIter.hasNext).getOrElse(false)
              (taken, peek)
            }
          }
        val hitLimit = config.maxRows.exists(rows.size.toLong >= _)
        val isPartial =
          if (config.filter.isDefined) hitLimit && hasMoreAfterLimit
          else hitLimit && rows.size.toLong < totalRows
        FileContent(rows = rows, totalRows = totalRows, isPartial = isPartial)
      }
      if (result.isFailure) footerCache.remove(cacheKey)
      result
    }
  }

  // Apply the optional row-limit to any IterableOnce source. Centralizes the
  // `Some(limit) => take | None => iter` dance so callers can chain transforms.
  private def applyMaxRows[A](
      source: IterableOnce[A],
      maxRows: Option[Long]
  ): Iterator[A] =
    maxRows.fold(source.iterator) { n =>
      val base = source.iterator
      var taken = 0L
      new Iterator[A] {
        def hasNext: Boolean = taken < n && base.hasNext
        def next(): A = { val v = base.next(); taken += 1; v }
      }
    }

  def streamContent(
      file: ParquetFile,
      config: ReadConfig
  )(process: Map[String, CellValue] => Unit): Try[Long] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      val cacheKey = new HadoopPath(file.location.path).toString
      val result = Try {
        val path4s = Parquet4sPath(file.location.path)
        val hadoopPath = new HadoopPath(file.location.path)
        val (fileSchema, _) = getFooter(hadoopPath, hadoopConfig)
        val rawBinaryFields =
          ParquetRecordDecoder.rawBinaryFieldsFor(fileSchema)
        val int96Fields =
          ParquetRecordDecoder.int96FieldsFor(fileSchema)
        val temporalTransformer =
          ParquetRecordDecoder.buildTemporalTransformer(fileSchema)
        Using.resource(
          openParquetReader(path4s, hadoopConfig, config, fileSchema)
        ) { source =>
          val iter = applyMaxRows(source.iterator, config.maxRows)
          var count = 0L
          iter.foreach { record =>
            process(
              ParquetRecordDecoder.applyTemporalTransformer(
                ParquetRecordDecoder
                  .convertRecordToMapWithSchema(
                    record,
                    rawBinaryFields,
                    int96Fields
                  ),
                temporalTransformer
              )
            )
            count += 1
          }
          count
        }
      }
      if (result.isFailure) footerCache.remove(cacheKey)
      result
    }
  }

  private def readParallel(
      path: HadoopPath,
      conf: Configuration,
      config: ReadConfig
  ): List[Map[String, CellValue]] = {
    // getFooter is a cache hit here — readContent already called it
    val (fileSchema, blocks) = getFooter(path, conf)

    // Pre-select the minimum prefix of row groups needed to satisfy maxRows.
    // Without this, all row groups are decoded before the limit is applied.
    val selectedBlocks = config.maxRows match {
      case None => blocks
      case Some(limit) =>
        var cumulative = 0L
        blocks.takeWhile { block =>
          if (cumulative >= limit) false
          else { cumulative += block.getRowCount; true }
        }
    }

    val requestedSchema = config.columns match {
      case Some(cols) if cols.nonEmpty =>
        ParquetSchemaBuilder.projectSchema(fileSchema, cols)
      case _ => fileSchema
    }

    val threadCount =
      math.min(config.parallelism, math.max(1, selectedBlocks.size))
    val rawEc = Executors.newFixedThreadPool(threadCount)

    // True once shutdownNow() has been called; in that case awaitTermination
    // may take the full 30s waiting for threads to respond to interrupt — not a bug.
    var forciblyShutdown = false
    try {
      implicit val ec: ExecutionContextExecutorService =
        ExecutionContext.fromExecutorService(rawEc)
      val requestedNames =
        requestedSchema.getColumns.asScala.map(_.getPath.mkString(".")).toSet
      val futures = selectedBlocks.map { block =>
        Future {
          val colChunks = block.getColumns.asScala.toList
          val relevantChunks =
            if (requestedNames.isEmpty) colChunks
            else
              colChunks.filter(c =>
                requestedNames.contains(c.getPath.toDotString)
              )
          if (relevantChunks.isEmpty) {
            // Row group predates the requested columns (intra-file schema evolution).
            // Emit null-valued rows to match the sequential path: the schema declares
            // the columns optional, so absent values are Null, not omitted rows.
            logger.warn(
              s"Row group (${block.getRowCount} rows) has no chunks matching " +
                s"requested columns $requestedNames — fabricating null rows for schema evolution."
            )
            val nullRow = requestedSchema.getColumns.asScala
              .map(col => col.getPath.mkString(".") -> CellValue.Null)
              .toMap
            List.fill(block.getRowCount.toInt)(nullRow)
          } else {
            val rangeStart = relevantChunks.map(_.getStartingPos).min
            val rangeEnd =
              relevantChunks.map(c => c.getStartingPos + c.getTotalSize).max
            val readOptions = org.apache.parquet.ParquetReadOptions
              .builder()
              .withRange(rangeStart, rangeEnd)
              .build()
            Using.resource(
              ParquetFileReader.open(
                HadoopInputFile.fromPath(path, conf),
                readOptions
              )
            ) { reader =>
              val pageStore = reader.readNextRowGroup()
              if (pageStore == null) List.empty[Map[String, CellValue]]
              else
                ParquetRecordDecoder.decodePageStore(
                  pageStore,
                  fileSchema,
                  requestedSchema
                )
            }
          }
        }
      }
      val allRows =
        try Await.result(Future.sequence(futures), config.readTimeout).flatten
        catch {
          case _: java.util.concurrent.TimeoutException =>
            forciblyShutdown = true
            ec.shutdownNow()
            throw new RuntimeException(
              s"parallel read timed out after ${config.readTimeout} — " +
                "retry with --parallelism 1, or check network connectivity"
            )
          case t: Throwable =>
            forciblyShutdown = true
            ec.shutdownNow()
            throw t
        }
      config.maxRows match {
        case Some(limit) =>
          if (limit >= allRows.size.toLong) allRows
          // limit < allRows.size (an Int), so limit fits in Int — safe narrowing.
          else allRows.take(limit.toInt)
        case None => allRows
      }
    } finally {
      rawEc.shutdown()
      // After shutdownNow() the pool may linger up to 30s draining interrupted tasks —
      // that is expected; only warn when a graceful shutdown stalls unexpectedly.
      if (
        !forciblyShutdown && !rawEc.awaitTermination(
          30,
          java.util.concurrent.TimeUnit.SECONDS
        )
      )
        Console.err.println(
          "[parqueteer] warning: parallel read executor did not terminate within 30s — some cloud connections may remain open"
        )
    }
  }

  private def openParquetReader(
      path4s: Parquet4sPath,
      hadoopConfig: Configuration,
      config: ReadConfig,
      fileSchema: MessageType
  ): com.github.mjakubowski84.parquet4s.ParquetIterable[RowParquetRecord] = {
    val filter = config.filter
      .map { expr =>
        import io.github.yusukensanta.parqueteer.core.filters.FilterParser
        FilterParser
          .parseWithSchema(expr, fileSchema)
          .fold(
            err =>
              throw new RuntimeException(
                s"Cannot apply filter to file schema: ${err.userMessage}"
              ),
            identity
          )
      }
      .getOrElse(Filter.noopFilter)
    config.columns match {
      case Some(cols) if cols.nonEmpty =>
        val schema = ParquetSchemaBuilder.projectSchema(fileSchema, cols)
        ParquetReader
          .projectedGeneric(schema)
          .options(ParquetReader.Options(hadoopConf = hadoopConfig))
          .filter(filter)
          .read(path4s)
      case _ =>
        ParquetReader
          .as[RowParquetRecord]
          .options(ParquetReader.Options(hadoopConf = hadoopConfig))
          .filter(filter)
          .read(path4s)
    }
  }

  def readSchema(file: ParquetFile): Try[ParquetSchema] =
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)
        val (msgSchema, blocks) = getFooter(path, hadoopConfig)
        buildParquetSchema(msgSchema, blocks)
      }
    }

  def readFileInfo(
      file: ParquetFile
  ): Try[(ParquetSchema, FileMetadata, List[RowGroupInfo])] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)
        val fileStatus = path.getFileSystem(hadoopConfig).getFileStatus(path)
        val inputFile = HadoopInputFile.fromStatus(fileStatus, hadoopConfig)
        val footerBytes = readFooterBytes(inputFile)
        val (version, createdBy) = parseRawMeta(footerBytes)
        val meta = parseFooter(footerBytes)
        val blocks = meta.getBlocks.asScala.toList
        val msgSchema = meta.getFileMetaData.getSchema
        val ratio = calculateCompressionRatio(blocks)
        val parsedSchema = buildParquetSchema(msgSchema, blocks)
        val codecs = parsedSchema.columns.map(_.compressionType).distinct
        val codec =
          if (codecs.isEmpty) None
          else if (codecs.size == 1) Some(codecs.head)
          else Some("MIXED")
        val avgRGSize =
          if (blocks.isEmpty) None
          else Some(blocks.map(_.getTotalByteSize).sum / blocks.size)
        val metadata = FileMetadata(
          fileSize = fileStatus.getLen,
          createdAt = None,
          modifiedAt = Some(
            java.time.Instant.ofEpochMilli(fileStatus.getModificationTime)
          ),
          compressionRatio = ratio,
          version = version,
          createdBy = Some(createdBy),
          compressionType = codec,
          avgRowGroupSizeBytes = avgRGSize
        )
        val rowGroups = blocks.zipWithIndex.map { case (block, idx) =>
          RowGroupInfo(
            index = idx,
            rowCount = block.getRowCount,
            compressedBytes = block.getColumns.asScala.map(_.getTotalSize).sum,
            uncompressedBytes = block.getTotalByteSize
          )
        }
        footerCache.put(path.toString, (msgSchema, blocks))
        (parsedSchema, metadata, rowGroups)
      }
    }
  }

  def readMetadata(file: ParquetFile): Try[FileMetadata] =
    readFileInfo(file).map { case (_, meta, _) => meta }

  private def buildWriter(
      parquetSchema: MessageType,
      location: StorageLocation,
      hadoopConfig: Configuration,
      config: WriteConfig
  ): org.apache.parquet.hadoop.ParquetWriter[
    org.apache.parquet.example.data.Group
  ] = {
    import org.apache.parquet.hadoop.example.ExampleParquetWriter

    location match {
      case LocalPath(p) =>
        val parent = java.nio.file.Paths.get(p).getParent
        if (parent != null) java.nio.file.Files.createDirectories(parent)
      case _ =>
    }

    ExampleParquetWriter
      .builder(new HadoopPath(location.path))
      .withType(parquetSchema)
      .withConf(hadoopConfig)
      .withCompressionCodec(
        ParquetWriteOps.convertCompressionType(config.compressionType)
      )
      .withRowGroupSize(config.rowGroupSize)
      .withPageSize(config.pageSize)
      .withDictionaryEncoding(config.enableDictionary)
      .withValidation(true)
      .build()
  }

  def writeContent(
      location: StorageLocation,
      data: List[Map[String, CellValue]],
      schema: Option[ParquetSchema],
      config: WriteConfig = WriteConfig()
  ): Try[Unit] = {
    setupHadoopConfiguration(location).flatMap { hadoopConfig =>
      Try {
        import org.apache.parquet.example.data.simple.SimpleGroupFactory

        val parquetSchema = schema match {
          case Some(ps) => ParquetSchemaBuilder.buildMessageType(ps)
          case None     =>
            // data is already fully in memory (List); infer from all rows to
            // avoid RowSchemaMismatchException when rows beyond the sample
            // introduce new columns or wider types.
            ParquetSchemaBuilder.inferSchemaFromData(data)
        }

        Using.resource(
          buildWriter(parquetSchema, location, hadoopConfig, config)
        ) { w =>
          val factory = new SimpleGroupFactory(parquetSchema)
          data.foreach { row =>
            val group = factory.newGroup()
            ParquetWriteOps.writeRowToGroup(group, row, parquetSchema)
            w.write(group)
          }
        }
      }
    }
  }

  def writeContentStream(
      location: StorageLocation,
      schema: ParquetSchema,
      config: WriteConfig = WriteConfig()
  )(feed: (Map[String, CellValue] => Unit) => Unit): Try[Long] = {
    setupHadoopConfiguration(location).flatMap { hadoopConfig =>
      Try {
        import org.apache.parquet.example.data.simple.SimpleGroupFactory

        val parquetSchema = ParquetSchemaBuilder.buildMessageType(schema)

        var count = 0L
        val factory = new SimpleGroupFactory(parquetSchema)
        // Using preserves the feed exception as primary and adds close failure as suppressed,
        // preventing writer.close() from masking the original MergeStreamException.
        scala.util
          .Using(buildWriter(parquetSchema, location, hadoopConfig, config)) {
            w =>
              feed { row =>
                val group = factory.newGroup()
                ParquetWriteOps.writeRowToGroup(group, row, parquetSchema)
                w.write(group)
                count += 1
              }
          }
          .get
        count
      }
    }
  }

  def validateFile(
      file: ParquetFile,
      deep: Boolean = false
  ): Try[List[String]] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)
        val issues = scala.collection.mutable.ListBuffer[String]()

        Try(
          ParquetFileReader.open(HadoopInputFile.fromPath(path, hadoopConfig))
        ) match {
          case scala.util.Failure(_: java.io.FileNotFoundException) =>
            issues += "File does not exist"
          case scala.util.Failure(ex) =>
            issues += s"File cannot be opened as Parquet: ${ex.getMessage}"
          case scala.util.Success(reader) =>
            Using.resource(reader) { r =>
              Try(r.getFooter) match {
                case scala.util.Failure(ex) =>
                  issues += s"Cannot read file footer: ${ex.getMessage}"
                case scala.util.Success(footer) =>
                  val schema = footer.getFileMetaData.getSchema
                  if (schema.getColumns.isEmpty)
                    issues += "Schema has no columns"

                  val blocks = footer.getBlocks.asScala.toList
                  if (blocks.isEmpty) issues += "File has no row groups"

                  val indicesToCheck = spotCheckIndices(blocks.size, deep)
                  var readerBroken = false
                  blocks.zipWithIndex.foreach { case (block, index) =>
                    if (block.getRowCount <= 0)
                      issues += s"Row group $index has invalid row count: ${block.getRowCount}"
                    if (!readerBroken) {
                      if (indicesToCheck.contains(index)) {
                        Try(r.readNextRowGroup()) match {
                          case scala.util.Failure(ex) =>
                            issues += s"Row group $index data is corrupt or truncated: ${ex.getMessage}"
                            readerBroken = true
                          case scala.util.Success(null) =>
                            issues += s"Row group $index returned no data (file may be truncated)"
                            readerBroken = true
                          case _ =>
                        }
                      } else {
                        Try(r.skipNextRowGroup()) match {
                          case scala.util.Failure(ex) =>
                            issues += s"Row group $index could not be skipped: ${ex.getMessage}"
                            readerBroken = true
                          case _ =>
                        }
                      }
                    }
                  }
              }
            }
        }

        issues.toList
      }
    }
  }

  // Returns the set of row-group indices to decompress during validation.
  // In deep mode (or for small files), checks all groups.
  // Otherwise spot-checks first, middle, and last to bound I/O cost.
  private def spotCheckIndices(blockCount: Int, deep: Boolean): Set[Int] =
    if (deep || blockCount <= 3) (0 until blockCount).toSet
    else Set(0, blockCount / 2, blockCount - 1)

  def readSchemaFields(
      file: ParquetFile
  ): Try[List[FieldSummary]] =
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)
        val (schema, _) = getFooter(path, hadoopConfig)
        schema.getFields.asScala.toList.map { field =>
          val typeName =
            if (field.isPrimitive) {
              val pf = field.asPrimitiveType()
              logicalTypeName(
                pf.getPrimitiveTypeName,
                pf.getLogicalTypeAnnotation
              )
            } else groupTypeCanonical(field.asGroupType())
          val optional =
            field.getRepetition == org.apache.parquet.schema.Type.Repetition.OPTIONAL
          FieldSummary(field.getName, typeName, optional)
        }
      }
    }

  private def logicalTypeName(
      primitive: org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName,
      annotation: org.apache.parquet.schema.LogicalTypeAnnotation
  ): String = {
    import org.apache.parquet.schema.LogicalTypeAnnotation
    if (annotation == null) primitive.name()
    else
      annotation match {
        case _: LogicalTypeAnnotation.DateLogicalTypeAnnotation => "DATE"
        case ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation =>
          ts.getUnit match {
            case LogicalTypeAnnotation.TimeUnit.MICROS => "TIMESTAMP_MICROS"
            case LogicalTypeAnnotation.TimeUnit.NANOS  => "TIMESTAMP_NANOS"
            case _                                     => "TIMESTAMP_MILLIS"
          }
        case _: LogicalTypeAnnotation.StringLogicalTypeAnnotation => "STRING"
        case _: LogicalTypeAnnotation.EnumLogicalTypeAnnotation   => "STRING"
        case _: LogicalTypeAnnotation.JsonLogicalTypeAnnotation   => "STRING"
        case dec: LogicalTypeAnnotation.DecimalLogicalTypeAnnotation =>
          s"DECIMAL(${dec.getPrecision},${dec.getScale})"
        case _ =>
          primitive match {
            case org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT96 =>
              "TIMESTAMP_MICROS"
            case org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY =>
              "BINARY"
            case _ => primitive.name()
          }
      }
  }

  def deleteFile(location: StorageLocation): Try[Unit] =
    setupHadoopConfiguration(location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(location.path)
        val fs = path.getFileSystem(hadoopConfig)
        if (!fs.delete(path, false) && fs.exists(path))
          throw new java.io.IOException(s"Failed to delete ${location.path}")
      }
    }

  def readStats(file: ParquetFile): Try[FileStats] =
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)
        val (schema, blocks) = getFooter(path, hadoopConfig)
        val totalRows = blocks.map(_.getRowCount).sum

        val columns = schema.getColumns.asScala.toList.map { colDescriptor =>
          val colPath = colDescriptor.getPath.mkString(".")
          val pt = colDescriptor.getPrimitiveType
          val typeName = pt.getPrimitiveTypeName
          val logicalType = pt.getLogicalTypeAnnotation
          val dataType = logicalTypeName(typeName, logicalType)

          val chunkStats = blocks
            .flatMap { block =>
              block.getColumns.asScala
                .find(_.getPath.toDotString == colPath)
                .map(_.getStatistics)
            }
            .filter(_ != null)

          val nullCount = {
            val counts = chunkStats.filter(_.isNumNullsSet).map(_.getNumNulls)
            if (counts.nonEmpty) counts.sum else -1L
          }

          val withValues =
            chunkStats.filter(s => !s.isEmpty && s.hasNonNullValue)
          val (minVal, maxVal) =
            computeTypedMinMax(withValues, typeName, logicalType)

          ColumnStats(colPath, dataType, nullCount, minVal, maxVal)
        }

        FileStats(columns, totalRows, blocks.size.toLong)
      }
    }

  private def numericMinMax[T: Ordering](
      withValues: List[org.apache.parquet.column.statistics.Statistics[?]],
      extract: PartialFunction[Any, T],
      filter: T => Boolean = (_: T) => true
  ): (Option[String], Option[String]) = {
    val mins =
      withValues.flatMap(s =>
        Option(s.genericGetMin()).collect(extract).filter(filter)
      )
    val maxs =
      withValues.flatMap(s =>
        Option(s.genericGetMax()).collect(extract).filter(filter)
      )
    (mins.minOption.map(_.toString), maxs.maxOption.map(_.toString))
  }

  private def computeTypedMinMax(
      withValues: List[org.apache.parquet.column.statistics.Statistics[?]],
      typeName: PrimitiveTypeName,
      logicalType: org.apache.parquet.schema.LogicalTypeAnnotation
  ): (Option[String], Option[String]) = {
    import org.apache.parquet.schema.LogicalTypeAnnotation
    logicalType match {
      case _: LogicalTypeAnnotation.DateLogicalTypeAnnotation =>
        val (mn, mx) = numericMinMax[Int](
          withValues,
          { case n: java.lang.Integer => n.intValue() }
        )
        (
          mn.map(v => java.time.LocalDate.ofEpochDay(v.toLong).toString),
          mx.map(v => java.time.LocalDate.ofEpochDay(v.toLong).toString)
        )

      case ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation =>
        def rawToInstant(raw: String): String = {
          val v = raw.toLong
          ts.getUnit match {
            case LogicalTypeAnnotation.TimeUnit.MICROS =>
              java.time.Instant
                .ofEpochSecond(
                  Math.floorDiv(v, 1_000_000L),
                  Math.floorMod(v, 1_000_000L) * 1000L
                )
                .toString
            case LogicalTypeAnnotation.TimeUnit.NANOS =>
              java.time.Instant
                .ofEpochSecond(
                  Math.floorDiv(v, 1_000_000_000L),
                  Math.floorMod(v, 1_000_000_000L)
                )
                .toString
            case _ => java.time.Instant.ofEpochMilli(v).toString
          }
        }
        val (mn, mx) = numericMinMax[Long](
          withValues,
          { case n: java.lang.Long => n.longValue() }
        )
        (mn.map(rawToInstant), mx.map(rawToInstant))

      case dec: LogicalTypeAnnotation.DecimalLogicalTypeAnnotation =>
        val scale = dec.getScale
        def applyScale(raw: String): String =
          new java.math.BigDecimal(
            new java.math.BigInteger(raw),
            scale
          ).toPlainString
        if (typeName == PrimitiveTypeName.INT32) {
          val (mn, mx) = numericMinMax[Int](
            withValues,
            { case n: java.lang.Integer => n.intValue() }
          )
          (mn.map(applyScale), mx.map(applyScale))
        } else if (typeName == PrimitiveTypeName.INT64) {
          val (mn, mx) = numericMinMax[Long](
            withValues,
            { case n: java.lang.Long => n.longValue() }
          )
          (mn.map(applyScale), mx.map(applyScale))
        } else {
          // BINARY / FIXED_LEN_BYTE_ARRAY DECIMAL: stats carry two's-complement unscaled bytes
          def fromBin(v: Any): Option[scala.math.BigDecimal] =
            PartialFunction.condOpt(v) {
              case bin: org.apache.parquet.io.api.Binary =>
                scala.math.BigDecimal(
                  new java.math.BigDecimal(
                    new java.math.BigInteger(bin.getBytes),
                    scale
                  )
                )
            }
          val mins =
            withValues.flatMap(s => Option(s.genericGetMin()).flatMap(fromBin))
          val maxs =
            withValues.flatMap(s => Option(s.genericGetMax()).flatMap(fromBin))
          (
            mins.minOption.map(_.underlying.toPlainString),
            maxs.maxOption.map(_.underlying.toPlainString)
          )
        }

      case _ =>
        typeName match {
          case PrimitiveTypeName.INT32 =>
            numericMinMax[Int](
              withValues,
              { case n: java.lang.Integer => n.intValue() }
            )
          case PrimitiveTypeName.INT64 =>
            numericMinMax[Long](
              withValues,
              { case n: java.lang.Long => n.longValue() }
            )
          case PrimitiveTypeName.FLOAT =>
            numericMinMax[Float](
              withValues,
              { case n: java.lang.Float => n.floatValue() },
              filter = v => !v.isNaN
            )
          case PrimitiveTypeName.DOUBLE =>
            numericMinMax[Double](
              withValues,
              { case n: java.lang.Double => n.doubleValue() },
              filter = v => !v.isNaN
            )
          case PrimitiveTypeName.BOOLEAN =>
            val mins = withValues.flatMap(s =>
              Option(s.genericGetMin()).collect { case b: java.lang.Boolean =>
                b.booleanValue()
              }
            )
            val maxs = withValues.flatMap(s =>
              Option(s.genericGetMax()).collect { case b: java.lang.Boolean =>
                b.booleanValue()
              }
            )
            (mins.minOption.map(_.toString), maxs.maxOption.map(_.toString))
          case PrimitiveTypeName.INT96 =>
            // Most writers (Spark, Hive) do not emit min/max stats for INT96.
            // When stats are present, decode each 12-byte Binary using the same
            // Julian-day layout as the read path and format as ISO-8601 UTC.
            def decodeInt96Stat(v: Any): Option[String] = v match {
              case b: org.apache.parquet.io.api.Binary if b.length() == 12 =>
                ParquetRecordDecoder.decodeInt96Binary(b.getBytes) match {
                  case CellValue.Ts(inst) => Some(inst.toString)
                  case _                  => None
                }
              case _ => None
            }
            val minStrs =
              withValues.flatMap(s =>
                Option(s.genericGetMin()).flatMap(decodeInt96Stat)
              )
            val maxStrs =
              withValues.flatMap(s =>
                Option(s.genericGetMax()).flatMap(decodeInt96Stat)
              )
            // ISO-8601 UTC strings sort lexicographically == chronologically
            (minStrs.minOption, maxStrs.maxOption)
          case _ =>
            if (
              typeName == PrimitiveTypeName.BINARY ||
              typeName == PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY
            ) {
              // Compare Binary objects byte-by-byte (not as UTF-8 strings) so the
              // cross-row-group min/max is correct even for non-UTF-8 binary data.
              implicit val binOrd: Ordering[org.apache.parquet.io.api.Binary] =
                (a, b) => java.util.Arrays.compare(a.getBytes, b.getBytes)
              val mins = withValues.flatMap(s =>
                Option(s.genericGetMin()).collect {
                  case b: org.apache.parquet.io.api.Binary => b
                }
              )
              val maxs = withValues.flatMap(s =>
                Option(s.genericGetMax()).collect {
                  case b: org.apache.parquet.io.api.Binary => b
                }
              )
              (
                mins.minOption.map(_.toStringUsingUTF8),
                maxs.maxOption.map(_.toStringUsingUTF8)
              )
            } else {
              val minVal = withValues
                .flatMap(s =>
                  Option(s.genericGetMin()).map(v => formatStatVal(v, typeName))
                )
                .minOption
              val maxVal = withValues
                .flatMap(s =>
                  Option(s.genericGetMax()).map(v => formatStatVal(v, typeName))
                )
                .maxOption
              (minVal, maxVal)
            }
        }
    }
  }

  private def formatStatVal(value: Any, typeName: PrimitiveTypeName): String =
    typeName match {
      case PrimitiveTypeName.BINARY | PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY =>
        value match {
          case b: org.apache.parquet.io.api.Binary => b.toStringUsingUTF8
          case other                               => other.toString
        }
      case _ => value.toString
    }

  private def setupHadoopConfiguration(
      location: StorageLocation
  ): Try[Configuration] = {
    val key = configCacheKey(location)
    Option(hadoopConfigCache.get(key)) match {
      case Some(cfg) =>
        configCacheHits.incrementAndGet()
        Success(new Configuration(cfg))
      case None =>
        configCacheMisses.incrementAndGet()
        val effectiveLocation = (location, region) match {
          case (s3: S3Location, Some(r)) => s3.copy(region = Some(r))
          case _                         => location
        }
        val result = CloudCredentialManager
          .forLocation(effectiveLocation, profile) match {
          case Some(credManager) =>
            credManager.configureHadoop(effectiveLocation).recoverWith {
              case e if !e.isInstanceOf[CloudAuthException] =>
                val providerName = effectiveLocation match {
                  case _: S3Location    => "S3"
                  case _: GCSLocation   => "GCS"
                  case _: AzureLocation => "Azure"
                  case _                => "cloud storage"
                }
                scala.util.Failure(
                  new CloudAuthException(providerName, e.getMessage, e)
                )
            }
          case None => Success(new Configuration())
        }
        // INVARIANT: cached Configuration must never be mutated after this put.
        // Callers always get a shallow copy (new Configuration(cfg)); mutating the
        // cached original while another thread copies it is not thread-safe.
        result.foreach(cfg => hadoopConfigCache.put(key, cfg))
        result.map(new Configuration(_))
    }
  }

  private def calculateCompressionRatio(
      rowGroups: List[BlockMetaData]
  ): Option[Double] =
    if (rowGroups.isEmpty) None
    else {
      val (totalUncompressed, totalCompressed) = rowGroups.foldLeft((0L, 0L)) {
        case ((uncompressed, compressed), rowGroup) =>
          (
            uncompressed + rowGroup.getTotalByteSize,
            compressed + rowGroup.getColumns.asScala.map(_.getTotalSize).sum
          )
      }
      Option.when(totalCompressed > 0)(
        totalUncompressed.toDouble / totalCompressed.toDouble
      )
    }

  private def groupTypeCanonical(
      gt: org.apache.parquet.schema.GroupType
  ): String = {
    val fields = gt.getFields.asScala
      .map { f =>
        val t =
          if (f.isPrimitive) f.asPrimitiveType().getPrimitiveTypeName.name()
          else groupTypeCanonical(f.asGroupType())
        s"${f.getName}:$t"
      }
      .toList
      .sorted
      .mkString(",")
    s"STRUCT<$fields>"
  }
}
