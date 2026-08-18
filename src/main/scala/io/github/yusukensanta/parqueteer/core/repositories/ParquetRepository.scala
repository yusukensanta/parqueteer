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
import org.apache.parquet.hadoop.metadata.{BlockMetaData, ColumnChunkMetaData, ParquetMetadata}
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

  // Infers a ParquetSchema from a single pass over rows, without ever materializing
  // them into a List — the schema-inference half of a two-pass streaming write
  // (infer, then writeContentStream). See HadoopParquetRepository for the bridge
  // through the internal MessageType-based inference used by writeContent.
  def inferSchemaFromRows(
      rows: Iterator[Map[String, CellValue]]
  ): Try[ParquetSchema]

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
// For S3, the cached Configuration holds a credentials-*provider* class name
// (fs.s3a.aws.credentials.provider), not resolved key/secret/session-token
// values — S3A instantiates and re-invokes that provider per request, so env
// var rotation and short-lived session/IMDS token refresh both work correctly
// without needing a new repository instance mid-process.
class HadoopParquetRepository(
    profile: Option[String] = None,
    region: Option[String] = None,
    s3EndpointUrl: Option[String] = None
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

  // The 5th field (raw ParquetMetadata) is the same footer object schema/blocks
  // were derived from — kept so callers that need to open a ParquetFileReader
  // (e.g. the parallel reader) can pass it in directly instead of triggering
  // another footer read+parse per row group.
  private type FooterEntry = (MessageType, List[BlockMetaData], String, String, ParquetMetadata)

  // Caches (MessageType, blocks, version, createdBy) per file path for the lifetime of this
  // repository instance. Bounded LRU: evicts the least-recently-used entry when size exceeds
  // FooterCacheMaxSize so large multi-file merges don't grow the cache unboundedly.
  private val footerCache: java.util.Map[String, FooterEntry] =
    java.util.Collections.synchronizedMap(
      new java.util.LinkedHashMap[String, FooterEntry](
        16,
        0.75f,
        true
      ) {

        override def removeEldestEntry(
            eldest: java.util.Map.Entry[String, FooterEntry]
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
        s"gcs:$bucket"
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
  ): FooterEntry = {
    val key = path.toString
    Option(footerCache.get(key)) match {
      case Some(cached) =>
        footerCacheHits.incrementAndGet()
        cached
      case None =>
        footerCacheMisses.incrementAndGet()
        val footerBytes = FooterReader.readFooterBytes(HadoopInputFile.fromPath(path, conf))
        val (version, createdBy) = FooterReader.parseRawMeta(footerBytes)
        val meta                 = FooterReader.parseFooter(footerBytes)
        val entry =
          (meta.getFileMetaData.getSchema, meta.getBlocks.asScala.toList, version, createdBy, meta)
        footerCache.put(key, entry)
        entry
    }
  }

  // ── Public API ────────────────────────────────────────────────────────────

  def readContent(file: ParquetFile, config: ReadConfig): Try[FileContent] =
    withHadoopConfig(file.location) { hadoopConfig =>
      val cacheKey = new HadoopPath(file.location.path).toString
      val result = Try {
        val hadoopPath                             = new HadoopPath(file.location.path)
        val (fileSchema, blocks, _, _, footerMeta) = getFooter(hadoopPath, hadoopConfig)
        val totalRows                              = blocks.map(_.getRowCount).sum

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
                footerMeta
              ),
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
    withHadoopConfig(file.location) { hadoopConfig =>
      val cacheKey = new HadoopPath(file.location.path).toString
      val result = Try {
        val path4s                   = Parquet4sPath(file.location.path)
        val hadoopPath               = new HadoopPath(file.location.path)
        val (fileSchema, _, _, _, _) = getFooter(hadoopPath, hadoopConfig)
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
    withHadoopConfig(file.location) { hadoopConfig =>
      Try {
        val path                         = new HadoopPath(file.location.path)
        val (msgSchema, blocks, _, _, _) = getFooter(path, hadoopConfig)
        FooterReader.buildParquetSchema(msgSchema, blocks)
      }
    }

  def readFileInfo(
      file: ParquetFile
  ): Try[(ParquetSchema, FileMetadata, List[RowGroupInfo])] =
    withHadoopConfig(file.location) { hadoopConfig =>
      Try {
        val path       = new HadoopPath(file.location.path)
        val fileStatus = path.getFileSystem(hadoopConfig).getFileStatus(path)
        val (msgSchema, blocks, version, createdBy, _) = getFooter(path, hadoopConfig)
        val ratio                                      = calculateCompressionRatio(blocks)
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
      footerCache.remove(cacheKey)
      result
    }

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
          ParquetFileReader.open(HadoopInputFile.fromPath(path, hadoopConfig))
        ) match {
          case scala.util.Failure(ex: FileNotFoundException) =>
            throw ex
          case scala.util.Failure(ex) =>
            issues += s"File cannot be opened as Parquet: ${io.github.yusukensanta.parqueteer.core.util.CredentialRedactor
                .redact(ex.getMessage)}"
          case scala.util.Success(reader) =>
            Using.resource(reader) { r =>
              Try(r.getFooter) match {
                case scala.util.Failure(ex) =>
                  issues += s"Cannot read file footer: ${io.github.yusukensanta.parqueteer.core.util.CredentialRedactor
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
                            issues += s"Row group $index data is corrupt or truncated: ${io.github.yusukensanta.parqueteer.core.util.CredentialRedactor
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
                            issues += s"Row group $index could not be skipped: ${io.github.yusukensanta.parqueteer.core.util.CredentialRedactor
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
        val path                 = new HadoopPath(file.location.path)
        val (schema, _, _, _, _) = getFooter(path, hadoopConfig)
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
        val path                      = new HadoopPath(file.location.path)
        val (schema, blocks, _, _, _) = getFooter(path, hadoopConfig)
        val totalRows                 = blocks.map(_.getRowCount).sum

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

  private def providerNameFor(location: StorageLocation): String = location match {
    case _: S3Location    => "S3"
    case _: GCSLocation   => "GCS"
    case _: AzureLocation => "Azure"
    case _                => "cloud storage"
  }

  private def isCloudLocation(location: StorageLocation): Boolean = location match {
    case _: S3Location | _: GCSLocation | _: AzureLocation => true
    case _                                                 => false
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
          .forLocation(effectiveLocation, profile, s3EndpointUrl) match {
          case Some(credManager) =>
            credManager.configureHadoop(effectiveLocation).recoverWith {
              case e if !e.isInstanceOf[CloudAuthException] =>
                scala.util.Failure(
                  new CloudAuthException(
                    providerNameFor(effectiveLocation),
                    io.github.yusukensanta.parqueteer.core.util.CredentialRedactor
                      .redact(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)),
                    e
                  )
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

  // Credential *resolution* is no longer eager (S3A resolves/refreshes its own
  // provider chain per request — see S3CredentialManager) — an invalid or
  // unauthorized identity now only surfaces once Hadoop actually attempts a
  // cloud operation, as java.nio.file.AccessDeniedException, not at
  // setupHadoopConfiguration time. Reclassify it as CloudAuthException here so
  // the CLI still reports "cloud authentication failed" instead of a raw I/O
  // error — scoped to cloud locations only, so a real local-filesystem
  // permission error isn't mislabeled as a cloud auth failure.
  private def withHadoopConfig[A](
      location: StorageLocation
  )(f: Configuration => Try[A]): Try[A] =
    setupHadoopConfiguration(location).flatMap(f).recoverWith {
      case e: java.nio.file.AccessDeniedException if isCloudLocation(location) =>
        scala.util.Failure(
          new CloudAuthException(
            providerNameFor(location),
            io.github.yusukensanta.parqueteer.core.util.CredentialRedactor
              .redact(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)),
            e
          )
        )
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
