package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models._
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
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import scala.concurrent.{Future, Await, ExecutionContext}
import java.util.concurrent.Executors
import scala.util.{Try, Success, Using}
import scala.jdk.CollectionConverters._

class ParquetRepository(
    profile: Option[String] = None,
    region: Option[String] = None
) {
  private val hadoopConfigCache =
    scala.collection.concurrent.TrieMap.empty[String, Configuration]

  private def configCacheKey(location: StorageLocation): String =
    location match {
      case LocalPath(_)                    => "local"
      case S3Location(bucket, _, _)        => s"s3:$bucket"
      case GCSLocation(bucket, _)          => s"gcs:$bucket"
      case AzureLocation(account, cont, _) => s"azure:$account/$cont"
    }

  // Close S3A/GCS/ABFS connection pools on JVM exit to prevent background thread leaks
  Runtime.getRuntime.addShutdownHook(new Thread(() => FileSystem.closeAll()))

  def readContent(file: ParquetFile, config: ReadConfig): Try[FileContent] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val hadoopPath = new HadoopPath(file.location.path)
        val (totalRows, fileSchema) = getFileMetadata(hadoopPath, hadoopConfig)

        val useParallel = config.parallelism > 1 && config.filter.isEmpty
        if (useParallel) {
          val rows = readParallel(hadoopPath, hadoopConfig, config)
          val isPartial =
            config.maxRows.exists(limit => rows.size.toLong >= limit)
          FileContent(rows = rows, totalRows = totalRows, isPartial = isPartial)
        } else {
          val path4s = Parquet4sPath(file.location.path)
          Using.resource(
            openParquetReader(
              path4s,
              hadoopPath,
              hadoopConfig,
              config,
              Some(fileSchema)
            )
          ) { reader =>
            val rows = config.maxRows match {
              case Some(limit) =>
                reader
                  .take(limit.min(Int.MaxValue).toInt)
                  .map(r =>
                    ParquetRecordDecoder.postProcessTemporalFields(
                      ParquetRecordDecoder.convertRecordToMap(r),
                      fileSchema
                    )
                  )
                  .toList
              case None =>
                reader
                  .map(r =>
                    ParquetRecordDecoder.postProcessTemporalFields(
                      ParquetRecordDecoder.convertRecordToMap(r),
                      fileSchema
                    )
                  )
                  .toList
            }
            val isPartial =
              config.maxRows.exists(limit => rows.size.toLong >= limit)
            FileContent(
              rows = rows,
              totalRows = totalRows,
              isPartial = isPartial
            )
          }
        }
      }
    }
  }

  def streamContent(
      file: ParquetFile,
      config: ReadConfig
  )(process: Map[String, CellValue] => Unit): Try[Long] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path4s = Parquet4sPath(file.location.path)
        val hadoopPath = new HadoopPath(file.location.path)
        val (_, fileSchema) = getFileMetadata(hadoopPath, hadoopConfig)
        Using.resource(
          openParquetReader(
            path4s,
            hadoopPath,
            hadoopConfig,
            config,
            Some(fileSchema)
          )
        ) { source =>
          val iter = config.maxRows match {
            case Some(limit) =>
              source.iterator.take(limit.min(Int.MaxValue).toInt)
            case None => source.iterator
          }
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
    val inputFile = HadoopInputFile.fromPath(path, conf)
    val (blocks, fileSchema) =
      Using.resource(ParquetFileReader.open(inputFile)) { reader =>
        (
          reader.getFooter.getBlocks.asScala.toList,
          reader.getFooter.getFileMetaData.getSchema
        )
      }

    val requestedSchema = config.columns match {
      case Some(cols) if cols.nonEmpty =>
        ParquetSchemaBuilder.projectSchema(fileSchema, cols)
      case _ => fileSchema
    }

    val threadCount = math.min(config.parallelism, math.max(1, blocks.size))
    val executor = Executors.newFixedThreadPool(threadCount)
    implicit val ec: ExecutionContext =
      ExecutionContext.fromExecutorService(executor)

    try {
      val futures = blocks.toList.map { block =>
        Future {
          val rangeStart = block.getStartingPos
          val rangeLength = block.getColumns.asScala.map(_.getTotalSize).sum
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
      hadoopPath: HadoopPath,
      hadoopConfig: Configuration,
      config: ReadConfig,
      fileSchema: Option[org.apache.parquet.schema.MessageType]
  ): com.github.mjakubowski84.parquet4s.ParquetIterable[RowParquetRecord] = {
    val filter = config.filter
      .flatMap { expr =>
        import io.github.yusukensanta.parqueteer.core.filters.FilterParser
        fileSchema
          .map(s => FilterParser.parseWithSchema(expr, s))
          .getOrElse(FilterParser.parse(expr))
          .toOption
      }
      .getOrElse(Filter.noopFilter)
    config.columns match {
      case Some(cols) if cols.nonEmpty =>
        val schema = ParquetSchemaBuilder.buildProjectedSchema(
          hadoopPath,
          hadoopConfig,
          cols
        )
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

  def readSchema(file: ParquetFile): Try[ParquetSchema] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)

        // Open ParquetFileReader with InputFile
        val inputFile = HadoopInputFile.fromPath(path, hadoopConfig)
        Using.resource(ParquetFileReader.open(inputFile)) { reader =>
          val footer = reader.getFooter
          val schema = footer.getFileMetaData.getSchema

          val blocks = footer.getBlocks.asScala

          // Get compression types from first row group (if available)
          val compressionMap: Map[String, String] =
            blocks.headOption
              .map { rowGroup =>
                rowGroup.getColumns.asScala.map { columnChunk =>
                  val columnPath = columnChunk.getPath.toDotString
                  val compression = columnChunk.getCodec.name()
                  columnPath -> compression
                }.toMap
              }
              .getOrElse(Map.empty[String, String])

          val columns = schema.getColumns.asScala.map { column =>
            val columnPath = column.getPath.mkString(".")
            ColumnInfo(
              name = columnPath,
              dataType = column.getPrimitiveType.getPrimitiveTypeName.name(),
              isOptional = column.getMaxRepetitionLevel == 0,
              maxDefinitionLevel = column.getMaxDefinitionLevel,
              maxRepetitionLevel = column.getMaxRepetitionLevel,
              compressionType = compressionMap.getOrElse(columnPath, "UNKNOWN")
            )
          }.toList

          ParquetSchema(
            columns = columns,
            rowGroupCount = footer.getBlocks.size().toLong,
            totalRowCount = blocks.map(_.getRowCount).sum
          )
        }
      }
    }
  }

  def readMetadata(file: ParquetFile): Try[FileMetadata] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)
        val fs = FileSystem.get(path.toUri, hadoopConfig)
        val fileStatus = fs.getFileStatus(path)
        val inputFile = HadoopInputFile.fromPath(path, hadoopConfig)

        // One stream open: read raw footer bytes, parse twice from memory.
        val (formatVersion, compressionRatio, createdBy) =
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

            val rawMeta = org.apache.parquet.format.Util.readFileMetaData(
              new java.io.ByteArrayInputStream(footerBytes)
            )
            val version = if (rawMeta.version == 2) "2.0" else "1.0"
            val createdByStr = Option(rawMeta.created_by).getOrElse("")

            val converter =
              new org.apache.parquet.format.converter.ParquetMetadataConverter()
            val parquetMeta = converter.readParquetMetadata(
              new java.io.ByteArrayInputStream(footerBytes),
              org.apache.parquet.format.converter.ParquetMetadataConverter.NO_FILTER
            )
            val ratio = calculateCompressionRatio(
              parquetMeta.getBlocks.asScala.toList
            )
            (version, ratio, createdByStr)
          }

        FileMetadata(
          fileSize = fileStatus.getLen,
          createdAt = None,
          modifiedAt = Some(
            java.time.Instant.ofEpochMilli(fileStatus.getModificationTime)
          ),
          compressionRatio = compressionRatio,
          version = formatVersion,
          createdBy = Some(createdBy)
        )
      }
    }
  }

  def writeContent(
      location: StorageLocation,
      data: List[Map[String, CellValue]],
      schema: Option[ParquetSchema],
      config: WriteConfig = WriteConfig()
  ): Try[Unit] = {
    setupHadoopConfiguration(location).flatMap { hadoopConfig =>
      Try {
        // For parquet4s 2.x, we need to use the low-level Hadoop API
        // or convert data to proper case classes

        import org.apache.parquet.hadoop.example.ExampleParquetWriter
        import org.apache.parquet.example.data.simple.SimpleGroupFactory
        import org.apache.hadoop.fs.{Path => HadoopPath}

        // Build Parquet schema from data structure
        val parquetSchema = schema match {
          case Some(ps) => ParquetSchemaBuilder.buildMessageType(ps)
          case None     => ParquetSchemaBuilder.inferSchemaFromData(data)
        }

        // Use ExampleParquetWriter.builder for Group writing
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
  ): Try[List[FieldSummary]] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)
        val inputFile = HadoopInputFile.fromPath(path, hadoopConfig)
        Using.resource(ParquetFileReader.open(inputFile)) { reader =>
          val schema = reader.getFooter.getFileMetaData.getSchema
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
    }
  }

  def deleteFile(location: StorageLocation): Try[Unit] =
    setupHadoopConfiguration(location).map { hadoopConfig =>
      val path = new HadoopPath(location.path)
      val fs = FileSystem.get(path.toUri, hadoopConfig)
      fs.delete(path, false)
    }

  def readStats(file: ParquetFile): Try[FileStats] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)
        val inputFile = HadoopInputFile.fromPath(path, hadoopConfig)
        Using.resource(ParquetFileReader.open(inputFile)) { reader =>
          val footer = reader.getFooter
          val schema = footer.getFileMetaData.getSchema
          val blocks = footer.getBlocks.asScala.toList
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
    }
  }

  private def numericMinMax[T: Ordering](
      withValues: List[org.apache.parquet.column.statistics.Statistics[?]],
      extract: Any => T
  ): (Option[String], Option[String]) = {
    val mins = withValues.flatMap(s => Option(s.genericGetMin()).map(extract))
    val maxs = withValues.flatMap(s => Option(s.genericGetMax()).map(extract))
    (mins.minOption.map(_.toString), maxs.maxOption.map(_.toString))
  }

  private def computeTypedMinMax(
      withValues: List[org.apache.parquet.column.statistics.Statistics[?]],
      typeName: PrimitiveTypeName
  ): (Option[String], Option[String]) =
    typeName match {
      case PrimitiveTypeName.INT32 =>
        numericMinMax(withValues, _.asInstanceOf[java.lang.Integer].intValue())
      case PrimitiveTypeName.INT64 =>
        numericMinMax(withValues, _.asInstanceOf[java.lang.Long].longValue())
      case PrimitiveTypeName.FLOAT =>
        numericMinMax(withValues, _.asInstanceOf[java.lang.Float].floatValue())
      case PrimitiveTypeName.DOUBLE =>
        numericMinMax(
          withValues,
          _.asInstanceOf[java.lang.Double].doubleValue()
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
            credManager.configureHadoop(effectiveLocation)
          case None => Success(new Configuration())
        }
        result.foreach(cfg => hadoopConfigCache.put(key, cfg))
        result
    }
  }

  private def getFileMetadata(
      path: HadoopPath,
      conf: Configuration
  ): (Long, MessageType) = {
    val inputFile = HadoopInputFile.fromPath(path, conf)
    Using.resource(ParquetFileReader.open(inputFile)) { reader =>
      val rowCount = reader.getFooter.getBlocks.asScala.map(_.getRowCount).sum
      val schema = reader.getFooter.getFileMetaData.getSchema
      (rowCount, schema)
    }
  }

  /** Calculate compression ratio from row group metadata Ratio =
    * uncompressed_size / compressed_size Higher ratio means better compression
    */
  private def calculateCompressionRatio(
      rowGroups: List[org.apache.parquet.hadoop.metadata.BlockMetaData]
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
