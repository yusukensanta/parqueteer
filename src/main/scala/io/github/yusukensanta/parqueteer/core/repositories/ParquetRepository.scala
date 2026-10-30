package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.util.{CredentialRedactor, RowLimiter}
import com.github.mjakubowski84.parquet4s.{
  Filter,
  ParquetReader,
  Path as Parquet4sPath,
  RowParquetRecord
}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path as HadoopPath}
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.metadata.{BlockMetaData, ColumnChunkMetaData}
import org.apache.parquet.hadoop.util.{HadoopInputFile, HadoopStreams}
import org.apache.parquet.io.{InputFile, LocalInputFile, SeekableInputStream}
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.ParquetWriter as HParquetWriter
import org.apache.parquet.example.data.Group
import org.apache.parquet.schema.{GroupType, MessageType}
import org.apache.parquet.schema.Type.Repetition
import java.io.{FileNotFoundException, IOException}
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.{Failure, Success, Try, Using}
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

  def readFileInfo(
      file: ParquetFile
  ): Try[(ParquetSchema, FileMetadata, List[RowGroupInfo])]

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

  // Infers a ParquetSchema from a single pass over rows, without ever materializing
  // them into a List — the schema-inference half of a two-pass streaming write
  // (infer, then writeContentStream). See HadoopParquetRepository for the bridge
  // through the internal MessageType-based inference used by writeContent.
  def inferSchemaFromRows(
      rows: Iterator[Map[String, CellValue]]
  ): Try[ParquetSchema]

  /**
   * Fails when a declared schema can't be written (unknown or unsupported
   * dataType), without touching storage. Defaults to accepting everything so
   * test doubles needn't implement it.
   */
  def validateWriteSchema(@scala.annotation.unused schema: ParquetSchema): Try[Unit] =
    scala.util.Success(())

  def validateFile(file: ParquetFile, deep: Boolean = false): Try[List[String]]
  def readSchemaFields(file: ParquetFile): Try[List[FieldSummary]]
  def deleteFile(location: StorageLocation): Try[Unit]
  def readStats(file: ParquetFile): Try[FileStats]

  /**
   * Full footer metadata (file, key-value, per-row-group and per-column-chunk
   * detail). Defaults to failing so fake repositories used in unit tests don't
   * have to implement it to keep compiling. `extended` adds the rarely needed
   * footer fields (encoding stats, size statistics, index/bloom regions,
   * sorting columns, column orders, ...), which costs a second footer read.
   */
  def readDetailedMetadata(
      file: ParquetFile,
      extended: Boolean = false
  ): Try[ParquetMetadataReport] = {
    val _ = (file, extended)
    Failure(new UnsupportedOperationException("readDetailedMetadata is not implemented"))
  }

  /**
   * Expand a glob pattern (containing *, ?, [, {) into the concrete files it
   * matches, sorted by path. A location with no glob metacharacters is
   * expected to already have been filtered out by the caller — this default
   * passthrough exists only so fake repositories used in unit tests don't
   * need to implement glob matching to keep compiling.
   */
  def globStatus(location: StorageLocation): Try[List[StorageLocation]] =
    Success(List(location))

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

class HadoopParquetRepository(
    profile: Option[String] = None,
    region: Option[String] = None,
    s3EndpointUrl: Option[String] = None
) extends ParquetRepository {

  private val FooterCacheMaxSize = 1024

  private val configProvider = new HadoopConfigProvider(profile, region, s3EndpointUrl)

  // Caches the parsed footer per file path for the lifetime of this repository
  // instance. Bounded so large multi-file merges don't grow it unboundedly.
  private val footerCache = new LruCache[String, FooterInfo](FooterCacheMaxSize)

  override def cacheStats(): ParquetRepository.CacheStats =
    ParquetRepository.CacheStats(
      footerHits = footerCache.hits,
      footerMisses = footerCache.misses,
      configHits = configProvider.hits,
      configMisses = configProvider.misses
    )

  // Close S3A/GCS/ABFS connection pools on JVM exit to prevent background thread leaks
  if HadoopParquetRepository.shutdownHookRegistered.compareAndSet(false, true) then
    Runtime.getRuntime.addShutdownHook(
      new Thread(() =>
        try FileSystem.closeAll()
        catch { case _: Throwable => () }
      )
    )

  // Opens a footer-reading stream for `location`. Local paths go through
  // LocalInputFile (plain RandomAccessFile — no Hadoop Configuration/FileSystem
  // touched at all, which is most of the fixed per-invocation cost for local
  // schema/stats/count reads). Cloud paths go through Hadoop's FileSystem, but
  // via openFile().withFileStatus() rather than plain open() — S3A (and other
  // FileSystems that support it) skip their own internal getFileStatus/HEAD
  // when a FileStatus is already supplied, so passing a caller-known status
  // here avoids a second redundant HEAD on top of the one below.
  private[repositories] def openFooterStream(
      location: StorageLocation,
      path: HadoopPath,
      conf: Configuration,
      knownStatus: Option[FileStatus]
  ): (SeekableInputStream, Long) = location match {
    case LocalPath(p) =>
      val localFile = new LocalInputFile(Paths.get(p))
      (localFile.newStream(), localFile.getLength)
    case _ =>
      val fs     = path.getFileSystem(conf)
      val status = knownStatus.getOrElse(fs.getFileStatus(path))
      val stream = HadoopStreams.wrap(fs.openFile(path).withFileStatus(status).build().get())
      (stream, status.getLen)
  }

  // Same LocalPath-vs-cloud branch as openFooterStream, but returning a plain
  // InputFile for callers (validateFile) that use parquet-mr's own generic
  // ParquetFileReader.open(InputFile) API directly rather than going through
  // getFooter's cached (stream, length) shape.
  private[repositories] def openInputFile(
      location: StorageLocation,
      path: HadoopPath,
      conf: Configuration
  ): InputFile = location match {
    case LocalPath(p) => new LocalInputFile(Paths.get(p))
    case _            => HadoopInputFile.fromPath(path, conf)
  }

  // Cache-aware footer fetch: 0 cloud ops on hit, at most 1 stat + 1 stream on
  // miss (0 stats when the caller already knows the FileStatus).
  private def getFooter(
      location: StorageLocation,
      path: HadoopPath,
      conf: Configuration,
      knownStatus: Option[FileStatus] = None
  ): FooterInfo = {
    val key = path.toString
    footerCache.get(key).getOrElse {
      val (stream, fileLen) = openFooterStream(location, path, conf, knownStatus)
      val footer            = FooterReader.readFooterInfo(stream, fileLen)
      footerCache.put(key, footer)
      footer
    }
  }

  // ── Public API ────────────────────────────────────────────────────────────

  def readContent(file: ParquetFile, config: ReadConfig): Try[FileContent] =
    withHadoopConfig(file.location) { hadoopConfig =>
      val cacheKey = new HadoopPath(file.location.path).toString
      val result = Try {
        val hadoopPath = new HadoopPath(file.location.path)
        val footer     = getFooter(file.location, hadoopPath, hadoopConfig)
        val fileSchema = footer.schema
        val blocks     = footer.blocks
        val totalRows  = blocks.map(_.getRowCount).sum

        // filter forces sequential: parquet4s evaluates predicates during
        // deserialization, not at page-selection time, so parallel reads can't
        // short-circuit and the overhead exceeds any concurrency benefit.
        val useParallel = config.parallelism > 1 && config.filter.isEmpty
        val (rows, hasMoreAfterLimit) =
          if useParallel then
            (
              ParallelRowGroupReader.read(
                hadoopPath,
                hadoopConfig,
                config,
                fileSchema,
                blocks,
                footer.metadata
              ),
              false
            )
          else {
            val path4s    = Parquet4sPath(file.location.path)
            val decodeRow = ParquetRecordDecoder.rowDecoderFor(fileSchema)
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
              ).map(decodeRow).toList
              // Peek one more row to determine if more matching rows exist beyond the limit.
              // Avoids false-positive isPartial when the filter matches exactly maxRows records.
              val peek =
                config.filter.isDefined &&
                  config.maxRows.exists(taken.size.toLong >= _) &&
                  Try(baseIter.hasNext).getOrElse(false)
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
    RowLimiter.limitIterator(source, maxRows)

  def streamContent(
      file: ParquetFile,
      config: ReadConfig
  )(process: Map[String, CellValue] => Unit): Try[Long] =
    withHadoopConfig(file.location) { hadoopConfig =>
      val cacheKey = new HadoopPath(file.location.path).toString
      val result = Try {
        val path4s     = Parquet4sPath(file.location.path)
        val hadoopPath = new HadoopPath(file.location.path)
        val fileSchema = getFooter(file.location, hadoopPath, hadoopConfig).schema
        val decodeRow  = ParquetRecordDecoder.rowDecoderFor(fileSchema)
        Using.resource(
          openParquetReader(path4s, hadoopConfig, config, fileSchema)
        ) { source =>
          val iter  = applyMaxRows(source.iterator, config.maxRows)
          var count = 0L
          iter.foreach { record =>
            process(decodeRow(record))
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

  // Size + mtime for FileMetadata. Takes no Configuration/FileSystem — for
  // local paths this is the only extra Hadoop touch readFileInfo needed
  // beyond the footer read, so keeping it Hadoop-free here (rather than via
  // path.getFileSystem(conf).getFileStatus(path)) is what lets the "schema"
  // and "info" CLI commands skip Hadoop entirely for local files. Mirrors
  // Hadoop's own FileNotFoundException-on-missing-path contract so error
  // handling (ParqueteerError's FileNotFoundException case) is unaffected.
  private[repositories] def localFileSizeAndMTime(path: String): (Long, Long) = {
    val f = new java.io.File(path)
    if !f.exists() then throw new FileNotFoundException(path)
    (f.length(), f.lastModified())
  }

  // Size + mtime (+ the FileStatus, when one had to be fetched, so getFooter
  // can reuse it instead of issuing a second HEAD on cloud storage).
  private def fileSizeAndMTime(
      location: StorageLocation,
      path: HadoopPath,
      hadoopConfig: Configuration
  ): (Long, Long, Option[FileStatus]) = location match {
    case LocalPath(p) =>
      val (size, mtime) = localFileSizeAndMTime(p)
      (size, mtime, None)
    case _ =>
      val status = path.getFileSystem(hadoopConfig).getFileStatus(path)
      (status.getLen, status.getModificationTime, Some(status))
  }

  def readFileInfo(
      file: ParquetFile
  ): Try[(ParquetSchema, FileMetadata, List[RowGroupInfo])] =
    withHadoopConfig(file.location) { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)
        val (fileSize, modTimeMillis, knownStatus) =
          fileSizeAndMTime(file.location, path, hadoopConfig)
        val FooterInfo(msgSchema, blocks, version, createdBy, _) =
          getFooter(file.location, path, hadoopConfig, knownStatus)
        val ratio        = calculateCompressionRatio(blocks)
        val parsedSchema = FooterReader.buildParquetSchema(msgSchema, blocks)
        val codecs       = parsedSchema.columns.map(_.compressionType).distinct
        val codec =
          if codecs.isEmpty then None
          else if codecs.size == 1 then Some(codecs.head)
          else Some("MIXED")
        val avgRGSize =
          if blocks.isEmpty then None
          else Some(blocks.map(_.getTotalByteSize).sum / blocks.size)
        val metadata = FileMetadata(
          fileSize = fileSize,
          createdAt = None,
          modifiedAt = Some(java.time.Instant.ofEpochMilli(modTimeMillis)),
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
        (parsedSchema, metadata, rowGroups)
      }
    }

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
    withHadoopConfig(location) { hadoopConfig =>
      // A successful (or partial) write changes the file on disk, so any cached
      // footer for this path is stale regardless of outcome — evict it.
      val cacheKey = new HadoopPath(location.path).toString
      val result = Try {
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
      footerCache.remove(cacheKey)
      result
    }

  def writeContentStream(
      location: StorageLocation,
      schema: ParquetSchema,
      config: WriteConfig = WriteConfig()
  )(feed: (Map[String, CellValue] => Unit) => Unit): Try[Long] =
    withHadoopConfig(location) { hadoopConfig =>
      // Same rationale as writeContent: the file on disk changes regardless of
      // outcome, so any cached footer for this path must be evicted.
      val cacheKey = new HadoopPath(location.path).toString
      val result = Try {
        val parquetSchema = ParquetSchemaBuilder.buildMessageType(schema)

        var count   = 0L
        val factory = new SimpleGroupFactory(parquetSchema)
        // Using preserves the feed exception as primary and adds close failure as suppressed,
        // preventing writer.close() from masking the original feed exception.
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
      footerCache.remove(cacheKey)
      result
    }

  override def validateWriteSchema(schema: ParquetSchema): Try[Unit] =
    Try(ParquetSchemaBuilder.buildMessageType(schema)).map(_ => ())

  def inferSchemaFromRows(
      rows: Iterator[Map[String, CellValue]]
  ): Try[ParquetSchema] =
    Try(
      FooterReader.buildParquetSchema(
        ParquetSchemaBuilder.inferSchemaFromRows(rows),
        Nil
      )
    )

  def validateFile(
      file: ParquetFile,
      deep: Boolean = false
  ): Try[List[String]] =
    withHadoopConfig(file.location) { hadoopConfig =>
      Try {
        val path   = new HadoopPath(file.location.path)
        val issues = scala.collection.mutable.ListBuffer[String]()

        Try(
          ParquetFileReader.open(openInputFile(file.location, path, hadoopConfig))
        ) match {
          case Failure(ex: FileNotFoundException) =>
            throw ex
          case Failure(ex) =>
            issues += s"File cannot be opened as Parquet: ${CredentialRedactor.redact(ex.getMessage)}"
          case Success(reader) =>
            Using.resource(reader) { r =>
              Try(r.getFooter) match {
                case Failure(ex) =>
                  issues += s"Cannot read file footer: ${CredentialRedactor.redact(ex.getMessage)}"
                case Success(footer) =>
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
                          case Failure(ex) =>
                            issues += s"Row group $index data is corrupt or truncated: ${CredentialRedactor
                                .redact(ex.getMessage)}"
                            readerBroken = true
                          case Success(null) =>
                            issues += s"Row group $index returned no data (file may be truncated)"
                            readerBroken = true
                          case _ =>
                        }
                      } else {
                        Try(r.skipNextRowGroup()) match {
                          case Failure(ex) =>
                            issues += s"Row group $index could not be skipped: ${CredentialRedactor
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
    withHadoopConfig(file.location) { hadoopConfig =>
      Try {
        val path   = new HadoopPath(file.location.path)
        val schema = getFooter(file.location, path, hadoopConfig).schema
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
    withHadoopConfig(location) { hadoopConfig =>
      val cacheKey = new HadoopPath(location.path).toString
      val result = Try {
        val path = new HadoopPath(location.path)
        val fs   = path.getFileSystem(hadoopConfig)
        if !fs.delete(path, false) && fs.exists(path) then
          throw new IOException(s"Failed to delete ${location.path}")
      }
      footerCache.remove(cacheKey)
      result
    }

  def readStats(file: ParquetFile): Try[FileStats] =
    withHadoopConfig(file.location) { hadoopConfig =>
      Try {
        val path      = new HadoopPath(file.location.path)
        val footer    = getFooter(file.location, path, hadoopConfig)
        val schema    = footer.schema
        val blocks    = footer.blocks
        val totalRows = blocks.map(_.getRowCount).sum

        // Build each block's column-name -> chunk index once (O(blocks * columns)),
        // so per-column stats lookup below is O(1) instead of a linear .find over
        // every block's columns for every schema column (O(columns^2 * blocks)).
        val blockColumnsByPath: List[Map[String, ColumnChunkMetaData]] =
          blocks.map { block =>
            block.getColumns.asScala.iterator
              .map(c => c.getPath.toDotString -> c)
              .toMap
          }

        val columns = schema.getColumns.asScala.toList.map { colDescriptor =>
          val colPath     = colDescriptor.getPath.mkString(".")
          val pt          = colDescriptor.getPrimitiveType
          val typeName    = pt.getPrimitiveTypeName
          val logicalType = pt.getLogicalTypeAnnotation
          val dataType    = FooterReader.logicalTypeName(typeName, logicalType)

          val chunkStats = blockColumnsByPath
            .flatMap(_.get(colPath))
            .map(_.getStatistics)
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

  override def readDetailedMetadata(
      file: ParquetFile,
      extended: Boolean
  ): Try[ParquetMetadataReport] =
    withHadoopConfig(file.location) { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)
        val (fileSize, modTimeMillis, knownStatus) =
          fileSizeAndMTime(file.location, path, hadoopConfig)
        val footer = getFooter(file.location, path, hadoopConfig, knownStatus)
        // parquet-java's footer objects drop some thrift fields, so the extended
        // view re-reads the raw footer (the cached one is already parsed).
        val raw = Option.when(extended) {
          val (stream, len) = openFooterStream(file.location, path, hadoopConfig, knownStatus)
          FooterReader.readRawFileMetaData(stream, len)
        }
        MetadataReader.build(
          footer,
          fileSize,
          Some(java.time.Instant.ofEpochMilli(modTimeMillis)),
          raw
        )
      }
    }

  override def globStatus(location: StorageLocation): Try[List[StorageLocation]] =
    withHadoopConfig(location) { hadoopConfig =>
      Try {
        val hadoopPath = new HadoopPath(location.path)
        val fs         = hadoopPath.getFileSystem(hadoopConfig)
        val matches    = Option(fs.globStatus(hadoopPath)).getOrElse(Array.empty[FileStatus])
        matches.toList
          .filterNot(_.isDirectory)
          .flatMap(status => StorageLocationParser.parse(matchedPathString(status)).toOption)
          .sortBy(_.path)
      }
    }

  // Hadoop's local-filesystem Path renders as "file:/abs/path" (single slash,
  // no authority) rather than a standard "file:///abs/path" URI. That form
  // isn't a scheme StorageLocationParser recognizes, so for local matches we
  // hand it the bare filesystem path instead of the "file:" URI string.
  // Cloud schemes (s3a/gs/abfss) already round-trip through toString as-is.
  private def matchedPathString(status: FileStatus): String = {
    val uri = status.getPath.toUri
    if uri.getScheme == "file" then uri.getPath else status.getPath.toString
  }

  private def withHadoopConfig[A](location: StorageLocation)(f: Configuration => Try[A]): Try[A] =
    configProvider.withConfig(location)(f)

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
    NestedType.struct(fields)
  }
}
