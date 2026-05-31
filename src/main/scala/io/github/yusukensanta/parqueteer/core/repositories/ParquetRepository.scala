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
import scala.concurrent.{Future, Await, ExecutionContext}
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
  def readFileInfo(file: ParquetFile): Try[(ParquetSchema, FileMetadata)]
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
}

class HadoopParquetRepository(
    profile: Option[String] = None,
    region: Option[String] = None
) extends ParquetRepository {
  private val hadoopConfigCache =
    scala.collection.concurrent.TrieMap.empty[String, Configuration]

  // Caches (MessageType, blocks) per file path for the lifetime of this repository instance.
  // Safe because ParquetRepository is created once per CLI invocation.
  private val footerCache =
    scala.collection.concurrent.TrieMap
      .empty[String, (MessageType, List[BlockMetaData])]

  private def configCacheKey(location: StorageLocation): String =
    location match {
      case LocalPath(_)                    => "local"
      case S3Location(bucket, _, _)        => s"s3:$bucket"
      case GCSLocation(bucket, _)          => s"gcs:$bucket"
      case AzureLocation(account, cont, _) => s"azure:$account/$cont"
    }

  // Close S3A/GCS/ABFS connection pools on JVM exit to prevent background thread leaks
  Runtime.getRuntime.addShutdownHook(new Thread(() => FileSystem.closeAll()))

  // ── Shared footer infrastructure ──────────────────────────────────────────

  private def readFooterBytes(inputFile: HadoopInputFile): Array[Byte] =
    Using.resource(inputFile.newStream()) { stream =>
      val fileLen = inputFile.getLength
      val tail = new Array[Byte](8)
      stream.seek(fileLen - 8)
      stream.readFully(tail)
      val footerLen = java.nio.ByteBuffer
        .wrap(tail, 0, 4)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .getInt
      stream.seek(fileLen - 8 - footerLen)
      val footerBytes = new Array[Byte](footerLen)
      stream.readFully(footerBytes)
      footerBytes
    }

  private def parseFooter(
      footerBytes: Array[Byte]
  ): org.apache.parquet.hadoop.metadata.ParquetMetadata = {
    val converter =
      new org.apache.parquet.format.converter.ParquetMetadataConverter()
    converter.readParquetMetadata(
      new java.io.ByteArrayInputStream(footerBytes),
      org.apache.parquet.format.converter.ParquetMetadataConverter.NO_FILTER
    )
  }

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
    val compressionMap = blocks.headOption
      .map(
        _.getColumns.asScala
          .map(c => c.getPath.toDotString -> c.getCodec.name())
          .toMap
      )
      .getOrElse(Map.empty[String, String])
    val columns = msgSchema.getColumns.asScala.map { col =>
      val colPath = col.getPath.mkString(".")
      ColumnInfo(
        name = colPath,
        dataType = col.getPrimitiveType.getPrimitiveTypeName.name(),
        isOptional = col.getMaxRepetitionLevel == 0,
        maxDefinitionLevel = col.getMaxDefinitionLevel,
        maxRepetitionLevel = col.getMaxRepetitionLevel,
        compressionType = compressionMap.getOrElse(colPath, "UNKNOWN")
      )
    }.toList
    ParquetSchema(
      columns = columns,
      rowGroupCount = blocks.size.toLong,
      totalRowCount = blocks.map(_.getRowCount).sum
    )
  }

  // Cache-aware footer fetch: 0 cloud ops on hit, 1 stat + 1 stream on miss.
  private def getFooter(
      path: HadoopPath,
      conf: Configuration
  ): (MessageType, List[BlockMetaData]) =
    footerCache.getOrElseUpdate(
      path.toString, {
        val footerBytes = readFooterBytes(HadoopInputFile.fromPath(path, conf))
        val meta = parseFooter(footerBytes)
        (meta.getFileMetaData.getSchema, meta.getBlocks.asScala.toList)
      }
    )

  // ── Public API ────────────────────────────────────────────────────────────

  def readContent(file: ParquetFile, config: ReadConfig): Try[FileContent] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val hadoopPath = new HadoopPath(file.location.path)
        val (fileSchema, blocks) = getFooter(hadoopPath, hadoopConfig)
        val totalRows = blocks.map(_.getRowCount).sum

        val useParallel = config.parallelism > 1 && config.filter.isEmpty
        val rows =
          if (useParallel)
            // getFooter above is already cached; readParallel reuses it
            readParallel(hadoopPath, hadoopConfig, config)
          else {
            val path4s = Parquet4sPath(file.location.path)
            Using.resource(
              openParquetReader(path4s, hadoopConfig, config, fileSchema)
            ) { reader =>
              applyMaxRows(reader, config.maxRows)
                .map(r =>
                  ParquetRecordDecoder.postProcessTemporalFields(
                    ParquetRecordDecoder.convertRecordToMap(r),
                    fileSchema
                  )
                )
                .toList
            }
          }
        val isPartial =
          config.maxRows.exists(limit => rows.size.toLong >= limit)
        FileContent(rows = rows, totalRows = totalRows, isPartial = isPartial)
      }
    }
  }

  // Apply the optional row-limit to any IterableOnce source. Centralizes the
  // `Some(limit) => take | None => iter` dance so callers can chain transforms.
  private def applyMaxRows[A](
      source: IterableOnce[A],
      maxRows: Option[Long]
  ): Iterator[A] =
    maxRows.fold(source.iterator)(n => source.iterator.take(n.toInt))

  def streamContent(
      file: ParquetFile,
      config: ReadConfig
  )(process: Map[String, CellValue] => Unit): Try[Long] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path4s = Parquet4sPath(file.location.path)
        val hadoopPath = new HadoopPath(file.location.path)
        val (fileSchema, _) = getFooter(hadoopPath, hadoopConfig)
        Using.resource(
          openParquetReader(path4s, hadoopConfig, config, fileSchema)
        ) { source =>
          val iter = applyMaxRows(source.iterator, config.maxRows)
          var count = 0L
          iter.foreach { record =>
            process(
              ParquetRecordDecoder.postProcessTemporalFields(
                ParquetRecordDecoder.convertRecordToMap(record),
                fileSchema
              )
            )
            count += 1
          }
          count
        }
      }
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

    val threadCount = math.min(config.parallelism, math.max(1, selectedBlocks.size))
    val executor = Executors.newFixedThreadPool(threadCount)
    implicit val ec: ExecutionContext =
      ExecutionContext.fromExecutorService(executor)

    try {
      val requestedNames =
        requestedSchema.getFields.asScala.map(_.getName).toSet
      val futures = selectedBlocks.map { block =>
        Future {
          val colChunks = block.getColumns.asScala.toList
          val relevantChunks = {
            val projected = colChunks.filter(c =>
              requestedNames.contains(c.getPath.toDotString)
            )
            if (projected.isEmpty) colChunks else projected
          }
          val rangeStart = relevantChunks.map(_.getStartingPos).min
          val rangeLength =
            relevantChunks
              .map(c => c.getStartingPos + c.getTotalSize)
              .max - rangeStart
          val readOptions = org.apache.parquet.ParquetReadOptions
            .builder()
            .withRange(rangeStart, rangeLength)
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
      val allRows =
        try Await.result(Future.sequence(futures), config.readTimeout).flatten
        catch {
          case _: java.util.concurrent.TimeoutException =>
            executor.shutdownNow()
            throw new RuntimeException(
              s"parallel read timed out after ${config.readTimeout} — " +
                "retry with --parallelism 1, or check network connectivity"
            )
        }
      config.maxRows match {
        case Some(limit) => allRows.take(limit.min(Int.MaxValue).toInt)
        case None        => allRows
      }
    } finally {
      executor.shutdown()
    }
  }

  private def openParquetReader(
      path4s: Parquet4sPath,
      hadoopConfig: Configuration,
      config: ReadConfig,
      fileSchema: MessageType
  ): com.github.mjakubowski84.parquet4s.ParquetIterable[RowParquetRecord] = {
    val filter = config.filter
      .flatMap { expr =>
        import io.github.yusukensanta.parqueteer.core.filters.FilterParser
        FilterParser.parseWithSchema(expr, fileSchema).toOption
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
  ): Try[(ParquetSchema, FileMetadata)] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)
        val fs = FileSystem.get(path.toUri, hadoopConfig)
        val fileStatus = fs.getFileStatus(path)
        val inputFile = HadoopInputFile.fromStatus(fileStatus, hadoopConfig)
        val footerBytes = readFooterBytes(inputFile)
        val (version, createdBy) = parseRawMeta(footerBytes)
        val meta = parseFooter(footerBytes)
        val blocks = meta.getBlocks.asScala.toList
        val msgSchema = meta.getFileMetaData.getSchema
        footerCache.put(path.toString, (msgSchema, blocks))
        val ratio = calculateCompressionRatio(blocks)
        val parsedSchema = buildParquetSchema(msgSchema, blocks)
        val metadata = FileMetadata(
          fileSize = fileStatus.getLen,
          createdAt = None,
          modifiedAt = Some(
            java.time.Instant.ofEpochMilli(fileStatus.getModificationTime)
          ),
          compressionRatio = ratio,
          version = version,
          createdBy = Some(createdBy)
        )
        (parsedSchema, metadata)
      }
    }
  }

  def readMetadata(file: ParquetFile): Try[FileMetadata] =
    readFileInfo(file).map(_._2)

  def writeContent(
      location: StorageLocation,
      data: List[Map[String, CellValue]],
      schema: Option[ParquetSchema],
      config: WriteConfig = WriteConfig()
  ): Try[Unit] = {
    setupHadoopConfiguration(location).flatMap { hadoopConfig =>
      Try {
        import org.apache.parquet.hadoop.example.ExampleParquetWriter
        import org.apache.parquet.example.data.simple.SimpleGroupFactory
        import org.apache.hadoop.fs.{Path => HadoopPath}

        val parquetSchema = schema match {
          case Some(ps) => ParquetSchemaBuilder.buildMessageType(ps)
          case None     => ParquetSchemaBuilder.inferSchemaFromData(data)
        }

        val writer = ExampleParquetWriter
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

        try {
          val factory = new SimpleGroupFactory(parquetSchema)
          data.foreach { row =>
            val group = factory.newGroup()
            ParquetWriteOps.writeRowToGroup(group, row, parquetSchema)
            writer.write(group)
          }
        } finally {
          writer.close()
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
        import org.apache.parquet.hadoop.example.ExampleParquetWriter
        import org.apache.parquet.example.data.simple.SimpleGroupFactory
        import org.apache.hadoop.fs.{Path => HadoopPath}

        val parquetSchema = ParquetSchemaBuilder.buildMessageType(schema)
        val writer = ExampleParquetWriter
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

        var count = 0L
        val factory = new SimpleGroupFactory(parquetSchema)
        try {
          feed { row =>
            val group = factory.newGroup()
            ParquetWriteOps.writeRowToGroup(group, row, parquetSchema)
            writer.write(group)
            count += 1
          }
        } finally {
          writer.close()
        }
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
        val fs = FileSystem.get(path.toUri, hadoopConfig)
        val issues = scala.collection.mutable.ListBuffer[String]()

        if (!fs.exists(path)) {
          issues += "File does not exist"
        } else {
          val inputFile = HadoopInputFile.fromPath(path, hadoopConfig)
          Try(ParquetFileReader.open(inputFile)) match {
            case scala.util.Failure(ex) =>
              issues += s"File cannot be opened as Parquet: ${ex.getMessage}"
            case scala.util.Success(reader) =>
              Using.resource(reader) { r =>
                val footer = r.getFooter
                val schema = footer.getFileMetaData.getSchema
                if (schema.getColumns.isEmpty) issues += "Schema has no columns"

                val blocks = footer.getBlocks.asScala.toList
                if (blocks.isEmpty) issues += "File has no row groups"

                val indicesToCheck = spotCheckIndices(blocks.size, deep)
                blocks.zipWithIndex.foreach { case (block, index) =>
                  if (block.getRowCount <= 0)
                    issues += s"Row group $index has invalid row count: ${block.getRowCount}"
                  if (indicesToCheck.contains(index)) {
                    Try(r.readNextRowGroup()) match {
                      case scala.util.Failure(ex) =>
                        issues += s"Row group $index data is corrupt or truncated: ${ex.getMessage}"
                      case _ =>
                    }
                  } else {
                    Try(r.skipNextRowGroup()) match {
                      case scala.util.Failure(ex) =>
                        issues += s"Row group $index could not be skipped: ${ex.getMessage}"
                      case _ =>
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
            if (field.isPrimitive)
              field.asPrimitiveType().getPrimitiveTypeName.name()
            else groupTypeCanonical(field.asGroupType())
          val optional =
            field.getRepetition == org.apache.parquet.schema.Type.Repetition.OPTIONAL
          FieldSummary(field.getName, typeName, optional)
        }
      }
    }

  def deleteFile(location: StorageLocation): Try[Unit] =
    setupHadoopConfiguration(location).map { hadoopConfig =>
      val path = new HadoopPath(location.path)
      val fs = FileSystem.get(path.toUri, hadoopConfig)
      fs.delete(path, false)
    }

  def readStats(file: ParquetFile): Try[FileStats] =
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)
        val (schema, blocks) = getFooter(path, hadoopConfig)
        val totalRows = blocks.map(_.getRowCount).sum

        val columns = schema.getColumns.asScala.toList.map { colDescriptor =>
          val colPath = colDescriptor.getPath.mkString(".")
          val typeName = colDescriptor.getPrimitiveType.getPrimitiveTypeName
          val dataType = typeName.name()

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
          val (minVal, maxVal) = computeTypedMinMax(withValues, typeName)

          ColumnStats(colPath, dataType, nullCount, minVal, maxVal)
        }

        FileStats(columns, totalRows, blocks.size.toLong)
      }
    }

  private def numericMinMax[T: Ordering](
      withValues: List[org.apache.parquet.column.statistics.Statistics[?]],
      extract: PartialFunction[Any, T]
  ): (Option[String], Option[String]) = {
    val mins =
      withValues.flatMap(s => Option(s.genericGetMin()).collect(extract))
    val maxs =
      withValues.flatMap(s => Option(s.genericGetMax()).collect(extract))
    (mins.minOption.map(_.toString), maxs.maxOption.map(_.toString))
  }

  private def computeTypedMinMax(
      withValues: List[org.apache.parquet.column.statistics.Statistics[?]],
      typeName: PrimitiveTypeName
  ): (Option[String], Option[String]) =
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
          { case n: java.lang.Float => n.floatValue() }
        )
      case PrimitiveTypeName.DOUBLE =>
        numericMinMax[Double](
          withValues,
          { case n: java.lang.Double => n.doubleValue() }
        )
      case _ =>
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
    hadoopConfigCache.get(key) match {
      case Some(cfg) => Success(cfg)
      case None =>
        val effectiveLocation = (location, region) match {
          case (s3: S3Location, Some(r)) => s3.copy(region = Some(r))
          case _                         => location
        }
        val result = CloudCredentialManager
          .forLocation(effectiveLocation, profile) match {
          case Some(credManager) =>
            credManager.configureHadoop(effectiveLocation).recoverWith {
              case e =>
                val providerName = effectiveLocation match {
                  case _: S3Location    => "S3"
                  case _: GCSLocation   => "GCS"
                  case _: AzureLocation => "Azure"
                  case _                => "cloud storage"
                }
                scala.util.Failure(new CloudAuthException(providerName, e.getMessage, e))
            }
          case None => Success(new Configuration())
        }
        result.foreach(cfg => hadoopConfigCache.put(key, cfg))
        result
    }
  }

  /** Calculate compression ratio from row group metadata Ratio =
    * uncompressed_size / compressed_size Higher ratio means better compression
    */
  private def calculateCompressionRatio(
      rowGroups: List[BlockMetaData]
  ): Option[Double] = {
    if (rowGroups.isEmpty) return None

    val (totalUncompressed, totalCompressed) = rowGroups.foldLeft((0L, 0L)) {
      case ((uncompressed, compressed), rowGroup) =>
        val rgUncompressed = rowGroup.getTotalByteSize
        val rgCompressed = rowGroup.getColumns.asScala
          .map(_.getTotalSize)
          .sum

        (uncompressed + rgUncompressed, compressed + rgCompressed)
    }

    if (totalCompressed > 0) {
      Some(totalUncompressed.toDouble / totalCompressed.toDouble)
    } else {
      None
    }
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
      .mkString(",")
    s"STRUCT<$fields>"
  }
}
