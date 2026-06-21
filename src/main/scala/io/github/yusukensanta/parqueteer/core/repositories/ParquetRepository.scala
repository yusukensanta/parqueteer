package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.CloudAuthException
import io.github.yusukensanta.parqueteer.cloud.CloudCredentialManager
import com.github.mjakubowski84.parquet4s.{
  Filter,
  ParquetReader,
  Path as Parquet4sPath,
  RowParquetRecord
}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path as HadoopPath}
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.metadata.BlockMetaData
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.ParquetWriter as HParquetWriter
import org.apache.parquet.example.data.Group
import org.apache.parquet.schema.{GroupType, MessageType}
import org.apache.parquet.schema.Type.Repetition
import java.io.{FileNotFoundException, IOException}
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.util.{Success, Try, Using}
import scala.jdk.CollectionConverters.*

/**
 * Public interface for Parquet file I/O. Implementations may choose to use
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
    new AtomicBoolean(false)
}

// The Hadoop Configuration cache is keyed on storage location type + bucket/region.
// It does not incorporate credential env vars (AWS_ACCESS_KEY_ID, etc.).
// Callers that rotate credentials mid-process must create a new repository instance.
class HadoopParquetRepository(
    profile: Option[String] = None,
    region: Option[String] = None
) extends ParquetRepository {

  private val HadoopConfigCacheMaxSize = 64
  private val FooterCacheMaxSize       = 1024

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
  private val footerCache: java.util.Map[String, (MessageType, List[BlockMetaData])] =
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
    new AtomicLong(0)

  private val footerCacheMisses =
    new AtomicLong(0)

  private val configCacheHits =
    new AtomicLong(0)

  private val configCacheMisses =
    new AtomicLong(0)

  override def cacheStats(): ParquetRepository.CacheStats =
    ParquetRepository.CacheStats(
      footerHits = footerCacheHits.get(),
      footerMisses = footerCacheMisses.get(),
      configHits = configCacheHits.get(),
      configMisses = configCacheMisses.get()
    )

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
  if HadoopParquetRepository.shutdownHookRegistered.compareAndSet(false, true) then
    Runtime.getRuntime.addShutdownHook(
      new Thread(() =>
        try FileSystem.closeAll()
        catch { case _: Throwable => () }
      )
    )

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
        val footerBytes = FooterReader.readFooterBytes(HadoopInputFile.fromPath(path, conf))
        val meta        = FooterReader.parseFooter(footerBytes)
        val entry =
          (meta.getFileMetaData.getSchema, meta.getBlocks.asScala.toList)
        footerCache.put(key, entry)
        entry
    }
  }

  // ── Public API ────────────────────────────────────────────────────────────

  def readContent(file: ParquetFile, config: ReadConfig): Try[FileContent] =
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      val cacheKey = new HadoopPath(file.location.path).toString
      val result = Try {
        val hadoopPath           = new HadoopPath(file.location.path)
        val (fileSchema, blocks) = getFooter(hadoopPath, hadoopConfig)
        val totalRows            = blocks.map(_.getRowCount).sum

        // filter forces sequential: parquet4s evaluates predicates during
        // deserialization, not at page-selection time, so parallel reads can't
        // short-circuit and the overhead exceeds any concurrency benefit.
        val useParallel = config.parallelism > 1 && config.filter.isEmpty
        val (rows, hasMoreAfterLimit) =
          if useParallel then
            (
              ParallelRowGroupReader.read(hadoopPath, hadoopConfig, config, fileSchema, blocks),
              false
            )
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
          if config.filter.isDefined then hitLimit && hasMoreAfterLimit
          else hitLimit && rows.size.toLong < totalRows
        FileContent(rows = rows, totalRows = totalRows, isPartial = isPartial)
      }
      if result.isFailure then footerCache.remove(cacheKey)
      result
    }

  private def applyMaxRows[A](
      source: IterableOnce[A],
      maxRows: Option[Long]
  ): Iterator[A] =
    io.github.yusukensanta.parqueteer.core.util.RowLimiter
      .limitIterator(source, maxRows)

  def streamContent(
      file: ParquetFile,
      config: ReadConfig
  )(process: Map[String, CellValue] => Unit): Try[Long] =
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      val cacheKey = new HadoopPath(file.location.path).toString
      val result = Try {
        val path4s          = Parquet4sPath(file.location.path)
        val hadoopPath      = new HadoopPath(file.location.path)
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
          val iter  = applyMaxRows(source.iterator, config.maxRows)
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
      if result.isFailure then footerCache.remove(cacheKey)
      result
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
              throw new ParqueteerError.FilterParseException(
                expr,
                err.message
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
        val path                = new HadoopPath(file.location.path)
        val (msgSchema, blocks) = getFooter(path, hadoopConfig)
        FooterReader.buildParquetSchema(msgSchema, blocks)
      }
    }

  def readFileInfo(
      file: ParquetFile
  ): Try[(ParquetSchema, FileMetadata, List[RowGroupInfo])] =
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path                 = new HadoopPath(file.location.path)
        val fileStatus           = path.getFileSystem(hadoopConfig).getFileStatus(path)
        val inputFile            = HadoopInputFile.fromStatus(fileStatus, hadoopConfig)
        val footerBytes          = FooterReader.readFooterBytes(inputFile)
        val (version, createdBy) = FooterReader.parseRawMeta(footerBytes)
        val meta                 = FooterReader.parseFooter(footerBytes)
        val blocks               = meta.getBlocks.asScala.toList
        val msgSchema            = meta.getFileMetaData.getSchema
        val ratio                = calculateCompressionRatio(blocks)
        val parsedSchema         = FooterReader.buildParquetSchema(msgSchema, blocks)
        val codecs               = parsedSchema.columns.map(_.compressionType).distinct
        val codec =
          if codecs.isEmpty then None
          else if codecs.size == 1 then Some(codecs.head)
          else Some("MIXED")
        val avgRGSize =
          if blocks.isEmpty then None
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

  def readMetadata(file: ParquetFile): Try[FileMetadata] =
    readFileInfo(file).map { case (_, meta, _) => meta }

  private def buildWriter(
      parquetSchema: MessageType,
      location: StorageLocation,
      hadoopConfig: Configuration,
      config: WriteConfig
  ): HParquetWriter[Group] = {
    location match {
      case LocalPath(p) =>
        val parent = Paths.get(p).getParent
        if parent != null then Files.createDirectories(parent)
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
  ): Try[Unit] =
    setupHadoopConfiguration(location).flatMap { hadoopConfig =>
      Try {
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

  def writeContentStream(
      location: StorageLocation,
      schema: ParquetSchema,
      config: WriteConfig = WriteConfig()
  )(feed: (Map[String, CellValue] => Unit) => Unit): Try[Long] =
    setupHadoopConfiguration(location).flatMap { hadoopConfig =>
      Try {
        val parquetSchema = ParquetSchemaBuilder.buildMessageType(schema)

        var count   = 0L
        val factory = new SimpleGroupFactory(parquetSchema)
        // Using preserves the feed exception as primary and adds close failure as suppressed,
        // preventing writer.close() from masking the original MergeStreamException.
        scala.util
          .Using(buildWriter(parquetSchema, location, hadoopConfig, config)) { w =>
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

  def validateFile(
      file: ParquetFile,
      deep: Boolean = false
  ): Try[List[String]] =
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path   = new HadoopPath(file.location.path)
        val issues = scala.collection.mutable.ListBuffer[String]()

        Try(
          ParquetFileReader.open(HadoopInputFile.fromPath(path, hadoopConfig))
        ) match {
          case scala.util.Failure(ex: FileNotFoundException) =>
            throw ex
          case scala.util.Failure(ex) =>
            issues += s"File cannot be opened as Parquet: ${io.github.yusukensanta.parqueteer.cli.CredentialRedactor
                .redact(ex.getMessage)}"
          case scala.util.Success(reader) =>
            Using.resource(reader) { r =>
              Try(r.getFooter) match {
                case scala.util.Failure(ex) =>
                  issues += s"Cannot read file footer: ${io.github.yusukensanta.parqueteer.cli.CredentialRedactor
                      .redact(ex.getMessage)}"
                case scala.util.Success(footer) =>
                  val schema = footer.getFileMetaData.getSchema
                  if schema.getColumns.isEmpty then issues += "Schema has no columns"

                  val blocks = footer.getBlocks.asScala.toList
                  if blocks.isEmpty then issues += "File has no row groups"

                  val indicesToCheck = spotCheckIndices(blocks.size, deep)
                  var readerBroken   = false
                  blocks.zipWithIndex.foreach { case (block, index) =>
                    if block.getRowCount <= 0 then
                      issues += s"Row group $index has invalid row count: ${block.getRowCount}"
                    if !readerBroken then {
                      if indicesToCheck.contains(index) then {
                        Try(r.readNextRowGroup()) match {
                          case scala.util.Failure(ex) =>
                            issues += s"Row group $index data is corrupt or truncated: ${io.github.yusukensanta.parqueteer.cli.CredentialRedactor
                                .redact(ex.getMessage)}"
                            readerBroken = true
                          case scala.util.Success(null) =>
                            issues += s"Row group $index returned no data (file may be truncated)"
                            readerBroken = true
                          case _ =>
                        }
                      } else {
                        Try(r.skipNextRowGroup()) match {
                          case scala.util.Failure(ex) =>
                            issues += s"Row group $index could not be skipped: ${io.github.yusukensanta.parqueteer.cli.CredentialRedactor
                                .redact(ex.getMessage)}"
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

  // Returns the set of row-group indices to decompress during validation.
  // In deep mode (or for small files), checks all groups.
  // Otherwise spot-checks first, middle, and last to bound I/O cost.
  private def spotCheckIndices(blockCount: Int, deep: Boolean): Set[Int] =
    if deep || blockCount <= 3 then (0 until blockCount).toSet
    else Set(0, blockCount / 2, blockCount - 1)

  def readSchemaFields(
      file: ParquetFile
  ): Try[List[FieldSummary]] =
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path        = new HadoopPath(file.location.path)
        val (schema, _) = getFooter(path, hadoopConfig)
        schema.getFields.asScala.toList.map { field =>
          val typeName =
            if field.isPrimitive then {
              val pf = field.asPrimitiveType()
              FooterReader.logicalTypeName(
                pf.getPrimitiveTypeName,
                pf.getLogicalTypeAnnotation
              )
            } else groupTypeCanonical(field.asGroupType())
          val optional =
            field.getRepetition == Repetition.OPTIONAL
          FieldSummary(field.getName, typeName, optional)
        }
      }
    }

  def deleteFile(location: StorageLocation): Try[Unit] =
    setupHadoopConfiguration(location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(location.path)
        val fs   = path.getFileSystem(hadoopConfig)
        if !fs.delete(path, false) && fs.exists(path) then
          throw new IOException(s"Failed to delete ${location.path}")
      }
    }

  def readStats(file: ParquetFile): Try[FileStats] =
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path             = new HadoopPath(file.location.path)
        val (schema, blocks) = getFooter(path, hadoopConfig)
        val totalRows        = blocks.map(_.getRowCount).sum

        val columns = schema.getColumns.asScala.toList.map { colDescriptor =>
          val colPath     = colDescriptor.getPath.mkString(".")
          val pt          = colDescriptor.getPrimitiveType
          val typeName    = pt.getPrimitiveTypeName
          val logicalType = pt.getLogicalTypeAnnotation
          val dataType    = FooterReader.logicalTypeName(typeName, logicalType)

          val chunkStats = blocks
            .flatMap { block =>
              block.getColumns.asScala
                .find(_.getPath.toDotString == colPath)
                .map(_.getStatistics)
            }
            .filter(_ != null)

          val nullCount = {
            val counts = chunkStats.filter(_.isNumNullsSet).map(_.getNumNulls)
            if counts.nonEmpty then counts.sum else -1L
          }

          val withValues =
            chunkStats.filter(s => !s.isEmpty && s.hasNonNullValue)
          val (minVal, maxVal) =
            StatsComputer.computeTypedMinMax(withValues, typeName, logicalType)

          ColumnStats(colPath, dataType, nullCount, minVal, maxVal)
        }

        FileStats(columns, totalRows, blocks.size.toLong)
      }
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
    if rowGroups.isEmpty then None
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
      gt: GroupType
  ): String = {
    val fields = gt.getFields.asScala
      .map { f =>
        val t =
          if f.isPrimitive then f.asPrimitiveType().getPrimitiveTypeName.name()
          else groupTypeCanonical(f.asGroupType())
        s"${f.getName}:$t"
      }
      .toList
      .sorted
      .mkString(",")
    s"STRUCT<$fields>"
  }
}
