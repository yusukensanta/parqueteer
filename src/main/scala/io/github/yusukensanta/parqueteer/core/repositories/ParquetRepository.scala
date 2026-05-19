package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models._
import io.github.yusukensanta.parqueteer.cloud.CloudCredentialManager
import com.github.mjakubowski84.parquet4s.{
  ParquetReader,
  RowParquetRecord,
  Path => Parquet4sPath,
  Filter,
  Value,
  NullValue,
  BooleanValue,
  IntValue,
  LongValue,
  FloatValue,
  DoubleValue,
  BinaryValue,
  DateTimeValue
}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path => HadoopPath}
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.column.page.PageReadStore
import org.apache.parquet.io.ColumnIOFactory
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import scala.concurrent.{Future, Await, ExecutionContext}
import scala.concurrent.duration.Duration
import java.util.concurrent.Executors
import scala.util.{Try, Success, Using}
import scala.jdk.CollectionConverters._

class ParquetRepository {

  def readContent(file: ParquetFile, config: ReadConfig): Try[FileContent] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val hadoopPath = new HadoopPath(file.location.path)
        val totalRows = getRowCount(hadoopPath, hadoopConfig)

        if (config.parallelism > 1 && config.filter.isEmpty) {
          val rows = readParallel(hadoopPath, hadoopConfig, config)
          val isPartial =
            config.maxRows.exists(limit => rows.size.toLong >= limit)
          FileContent(rows = rows, totalRows = totalRows, isPartial = isPartial)
        } else {
          val path4s = Parquet4sPath(file.location.path)
          val filter = createFilter(config.filter)
          val reader = config.columns match {
            case Some(cols) if cols.nonEmpty =>
              val projectedSchema =
                buildProjectedSchema(hadoopPath, hadoopConfig, cols)
              ParquetReader
                .projectedGeneric(projectedSchema)
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
          val rows = config.maxRows match {
            case Some(limit) =>
              reader.take(limit.toInt).map(convertRecordToMap).toList
            case None => reader.map(convertRecordToMap).toList
          }
          val isPartial =
            config.maxRows.exists(limit => rows.size.toLong >= limit)
          FileContent(rows = rows, totalRows = totalRows, isPartial = isPartial)
        }
      }
    }
  }

  def streamContent(
      file: ParquetFile,
      config: ReadConfig
  )(process: Map[String, Any] => Unit): Try[Long] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path4s = Parquet4sPath(file.location.path)
        val filter = createFilter(config.filter)

        val iterable = config.columns match {
          case Some(cols) if cols.nonEmpty =>
            val hadoopPath = new HadoopPath(file.location.path)
            val projectedSchema =
              buildProjectedSchema(hadoopPath, hadoopConfig, cols)
            ParquetReader
              .projectedGeneric(projectedSchema)
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

        Using.resource(iterable) { source =>
          val iter = config.maxRows match {
            case Some(limit) => source.iterator.take(limit.toInt)
            case None        => source.iterator
          }
          var count = 0L
          iter.foreach { record =>
            process(convertRecordToMap(record))
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
  ): List[Map[String, Any]] = {
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
        val columnSet = cols.toSet
        val filteredFields = fileSchema.getFields.asScala
          .filter(f => columnSet.contains(f.getName))
          .toList
        if (filteredFields.isEmpty)
          throw new IllegalArgumentException(
            s"None of the requested columns exist in the file: ${cols.mkString(", ")}"
          )
        new MessageType("root", filteredFields.asJava)
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
            if (pageStore == null) List.empty[Map[String, Any]]
            else decodePageStore(pageStore, fileSchema, requestedSchema)
          }
        }
      }
      val allRows = Await.result(Future.sequence(futures), Duration.Inf).flatten
      config.maxRows match {
        case Some(limit) => allRows.take(limit.toInt)
        case None        => allRows
      }
    } finally {
      executor.shutdown()
    }
  }

  private def decodePageStore(
      pageStore: PageReadStore,
      fileSchema: MessageType,
      requestedSchema: MessageType
  ): List[Map[String, Any]] = {
    val columnIO =
      new ColumnIOFactory().getColumnIO(requestedSchema, fileSchema)
    val converter = new GroupRecordConverter(requestedSchema)
    val recordReader = columnIO.getRecordReader(pageStore, converter)
    val rowCount = pageStore.getRowCount
    (0L until rowCount).map { _ =>
      val group = recordReader.read()
      if (group == null) Map.empty[String, Any]
      else decodeGroup(group, requestedSchema)
    }.toList
  }

  private def decodeGroup(
      group: Group,
      schema: MessageType
  ): Map[String, Any] = {
    (0 until schema.getFieldCount).flatMap { i =>
      if (group.getFieldRepetitionCount(i) == 0) None
      else {
        val name = schema.getType(i).getName
        val value: Any =
          schema.getType(i).asPrimitiveType().getPrimitiveTypeName match {
            case PrimitiveTypeName.INT32   => group.getInteger(i, 0)
            case PrimitiveTypeName.INT64   => group.getLong(i, 0)
            case PrimitiveTypeName.FLOAT   => group.getFloat(i, 0)
            case PrimitiveTypeName.DOUBLE  => group.getDouble(i, 0)
            case PrimitiveTypeName.BOOLEAN => group.getBoolean(i, 0)
            case PrimitiveTypeName.BINARY =>
              group.getBinary(i, 0).toStringUsingUTF8
            case _ => group.getValueToString(i, 0)
          }
        Some(name -> value)
      }
    }.toMap
  }

  private def buildProjectedSchema(
      path: HadoopPath,
      conf: Configuration,
      columns: List[String]
  ): MessageType = {
    val inputFile = HadoopInputFile.fromPath(path, conf)
    val columnSet = columns.toSet
    Using.resource(ParquetFileReader.open(inputFile)) { reader =>
      val fullSchema = reader.getFooter.getFileMetaData.getSchema
      val projectedFields = fullSchema.getFields.asScala
        .filter(f => columnSet.contains(f.getName))
        .toList
      if (projectedFields.isEmpty)
        throw new IllegalArgumentException(
          s"None of the requested columns exist in the file: ${columns.mkString(", ")}"
        )
      new MessageType("root", projectedFields.asJava)
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

          // Get compression types from first row group (if available)
          val compressionMap: Map[String, String] =
            footer.getBlocks.asScala.headOption
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
            totalRowCount = footer.getBlocks.asScala.map(_.getRowCount).sum
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

        // Use ParquetFileReader.open with InputFile
        val inputFile = HadoopInputFile.fromPath(path, hadoopConfig)
        Using.resource(ParquetFileReader.open(inputFile)) { reader =>
          val footer = reader.getFooter
          val metadata = footer.getFileMetaData

          // Calculate compression ratio from row groups
          val compressionRatio =
            calculateCompressionRatio(footer.getBlocks.asScala.toList)

          FileMetadata(
            fileSize = fileStatus.getLen,
            createdAt = Some(
              java.time.Instant.ofEpochMilli(fileStatus.getModificationTime)
            ),
            modifiedAt = Some(
              java.time.Instant.ofEpochMilli(fileStatus.getModificationTime)
            ),
            compressionRatio = compressionRatio,
            version = metadata.getCreatedBy,
            createdBy = Some(metadata.getCreatedBy)
          )
        }
      }
    }
  }

  def writeContent(
      location: StorageLocation,
      data: List[Map[String, Any]],
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
          case Some(ps) => buildMessageType(ps)
          case None     => inferSchemaFromData(data)
        }

        // Use ExampleParquetWriter.builder for Group writing
        val writer = ExampleParquetWriter
          .builder(new HadoopPath(location.path))
          .withType(parquetSchema)
          .withConf(hadoopConfig)
          .withCompressionCodec(convertCompressionType(config.compressionType))
          .withRowGroupSize(config.rowGroupSize)
          .withPageSize(config.pageSize.toInt)
          .withDictionaryEncoding(config.enableDictionary)
          .withValidation(true)
          .build()

        try {
          val factory = new SimpleGroupFactory(parquetSchema)
          data.foreach { row =>
            val group = factory.newGroup()
            writeRowToGroup(group, row, parquetSchema)
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
  )(feed: (Map[String, Any] => Unit) => Unit): Try[Long] = {
    setupHadoopConfiguration(location).flatMap { hadoopConfig =>
      Try {
        import org.apache.parquet.hadoop.example.ExampleParquetWriter
        import org.apache.parquet.example.data.simple.SimpleGroupFactory
        import org.apache.hadoop.fs.{Path => HadoopPath}

        val parquetSchema = buildMessageType(schema)
        val writer = ExampleParquetWriter
          .builder(new HadoopPath(location.path))
          .withType(parquetSchema)
          .withConf(hadoopConfig)
          .withCompressionCodec(convertCompressionType(config.compressionType))
          .withRowGroupSize(config.rowGroupSize)
          .withPageSize(config.pageSize.toInt)
          .withDictionaryEncoding(config.enableDictionary)
          .withValidation(true)
          .build()

        var count = 0L
        val factory = new SimpleGroupFactory(parquetSchema)
        try {
          feed { row =>
            val group = factory.newGroup()
            writeRowToGroup(group, row, parquetSchema)
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

  private def buildMessageType(schema: ParquetSchema): MessageType = {
    import org.apache.parquet.schema.{Types, PrimitiveType}
    import org.apache.parquet.schema.Type.Repetition

    val builder = Types.buildMessage()

    schema.columns.foreach { col =>
      val repetition =
        if (col.isOptional) Repetition.OPTIONAL else Repetition.REQUIRED

      // Simplified type mapping - extend as needed
      col.dataType.toUpperCase match {
        case "INT32" | "INT" =>
          builder.addField(
            Types
              .primitive(PrimitiveType.PrimitiveTypeName.INT32, repetition)
              .named(col.name)
          )
        case "INT64" | "LONG" =>
          builder.addField(
            Types
              .primitive(PrimitiveType.PrimitiveTypeName.INT64, repetition)
              .named(col.name)
          )
        case "DOUBLE" =>
          builder.addField(
            Types
              .primitive(PrimitiveType.PrimitiveTypeName.DOUBLE, repetition)
              .named(col.name)
          )
        case "FLOAT" =>
          builder.addField(
            Types
              .primitive(PrimitiveType.PrimitiveTypeName.FLOAT, repetition)
              .named(col.name)
          )
        case "BOOLEAN" =>
          builder.addField(
            Types
              .primitive(PrimitiveType.PrimitiveTypeName.BOOLEAN, repetition)
              .named(col.name)
          )
        case "BINARY" | "STRING" =>
          builder.addField(
            Types
              .primitive(PrimitiveType.PrimitiveTypeName.BINARY, repetition)
              .as(org.apache.parquet.schema.LogicalTypeAnnotation.stringType())
              .named(col.name)
          )
        case _ =>
          builder.addField(
            Types
              .primitive(PrimitiveType.PrimitiveTypeName.BINARY, repetition)
              .named(col.name)
          )
      }
    }

    builder.named("root")
  }

// Helper to infer schema from data
  private def inferSchemaFromData(data: List[Map[String, Any]]): MessageType = {
    import org.apache.parquet.schema.{Types, PrimitiveType}
    import org.apache.parquet.schema.Type.Repetition

    if (data.isEmpty) {
      throw new IllegalArgumentException("Cannot infer schema from empty data")
    }

    val builder = Types.buildMessage()
    val allKeys = data.flatMap(_.keys).distinct

    allKeys.foreach { key =>
      val sample = data.collectFirst {
        case row if row.get(key).exists(_ != null) => row(key)
      }
      sample match {
        case Some(_: Int) =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.INT32,
                Repetition.OPTIONAL
              )
              .named(key)
          )
        case Some(_: Long) =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.INT64,
                Repetition.OPTIONAL
              )
              .named(key)
          )
        case Some(_: Double) =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.DOUBLE,
                Repetition.OPTIONAL
              )
              .named(key)
          )
        case Some(_: Float) =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.FLOAT,
                Repetition.OPTIONAL
              )
              .named(key)
          )
        case Some(_: Boolean) =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.BOOLEAN,
                Repetition.OPTIONAL
              )
              .named(key)
          )
        case Some(_: String) =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.BINARY,
                Repetition.OPTIONAL
              )
              .as(org.apache.parquet.schema.LogicalTypeAnnotation.stringType())
              .named(key)
          )
        case _ =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.BINARY,
                Repetition.OPTIONAL
              )
              .named(key)
          )
      }
    }

    builder.named("root")
  }

// Helper to write row data to Group
  private def writeRowToGroup(
      group: Group,
      row: Map[String, Any],
      schema: MessageType
  ): Unit = {
    row.foreach { case (key, value) =>
      val fieldIndex = schema.getFieldIndex(key)
      if (fieldIndex >= 0 && value != null) {
        val fieldTypeName =
          schema.getType(fieldIndex).asPrimitiveType().getPrimitiveTypeName
        value match {
          case i: Int =>
            fieldTypeName match {
              case PrimitiveTypeName.INT64  => group.add(fieldIndex, i.toLong)
              case PrimitiveTypeName.DOUBLE => group.add(fieldIndex, i.toDouble)
              case PrimitiveTypeName.FLOAT  => group.add(fieldIndex, i.toFloat)
              case _                        => group.add(fieldIndex, i)
            }
          case l: Long =>
            fieldTypeName match {
              case PrimitiveTypeName.DOUBLE => group.add(fieldIndex, l.toDouble)
              case PrimitiveTypeName.FLOAT  => group.add(fieldIndex, l.toFloat)
              case _                        => group.add(fieldIndex, l)
            }
          case d: Double  => group.add(fieldIndex, d)
          case f: Float   => group.add(fieldIndex, f)
          case b: Boolean => group.add(fieldIndex, b)
          case s: String  => group.add(fieldIndex, s)
          case other      => group.add(fieldIndex, other.toString)
        }
      }
    }
  }

  def validateFile(file: ParquetFile): Try[List[String]] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path = new HadoopPath(file.location.path)
        val fs = FileSystem.get(path.toUri, hadoopConfig)
        val issues = scala.collection.mutable.ListBuffer[String]()

        if (!fs.exists(path)) {
          issues += "File does not exist"
          issues.toList
        } else {

          // Use ParquetFileReader.open with InputFile
          val inputFile = HadoopInputFile.fromPath(path, hadoopConfig)
          Using.resource(ParquetFileReader.open(inputFile)) { reader =>
            val footer = reader.getFooter

            val schema = footer.getFileMetaData.getSchema
            if (schema.getColumns.isEmpty) {
              issues += "Schema has no columns"
            }

            val blocks = footer.getBlocks.asScala
            if (blocks.isEmpty) {
              issues += "File has no row groups"
            }

            blocks.zipWithIndex.foreach { case (block, index) =>
              if (block.getRowCount <= 0) {
                issues += s"Row group $index has invalid row count: ${block.getRowCount}"
              }
            }

            issues.toList
          }
        }
      }
    }
  }

  def readSchemaFields(
      file: ParquetFile
  ): Try[List[(String, String, Boolean)]] = {
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
              else field.asGroupType().getName
            val optional =
              field.getRepetition == org.apache.parquet.schema.Type.Repetition.OPTIONAL
            (field.getName, typeName, optional)
          }
        }
      }
    }
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

            ColumnStats(colPath, dataType, nullCount, minVal, maxVal)
          }

          FileStats(columns, totalRows, blocks.size.toLong)
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
    CloudCredentialManager.forLocation(location) match {
      case Some(credManager) => credManager.configureHadoop(location)
      case None =>
        Success(new Configuration())
    }
  }

  private def getRowCount(path: HadoopPath, conf: Configuration): Long = {
    // Use ParquetFileReader.open with InputFile
    val inputFile = HadoopInputFile.fromPath(path, conf)
    Using.resource(ParquetFileReader.open(inputFile)) { reader =>
      reader.getFooter.getBlocks.asScala.map(_.getRowCount).sum
    }
  }

  private def convertRecordToMap(record: RowParquetRecord): Map[String, Any] =
    record.iterator.map { case (key, value) => key -> decodeValue(value) }.toMap

  private def decodeValue(value: Value): Any = value match {
    case NullValue           => null
    case BooleanValue(b)     => b
    case IntValue(i)         => i
    case LongValue(l)        => l
    case FloatValue(f)       => f
    case DoubleValue(d)      => d
    case BinaryValue(binary) => binary.toStringUsingUTF8
    case DateTimeValue(l, _) => l
    case _                   => value.toString
  }

  /** Create parquet4s Filter from filter expression
    *
    * Supports SQL-like filter expressions:
    *   - age > 25
    *   - name = "John"
    *   - age > 25 AND salary >= 50000
    *   - (age < 18 OR age > 65) AND active = true
    */
  private def createFilter(filterExpr: Option[String]): Filter = {
    filterExpr match {
      case None => Filter.noopFilter // No filter = accept all

      case Some(expr) =>
        import io.github.yusukensanta.parqueteer.core.filters.FilterParser

        FilterParser.parse(expr) match {
          case Right(filter) => filter
          case Left(error) =>
            throw new IllegalArgumentException(error.userMessage)
        }
    }
  }

  private def convertCompressionType(
      compressionType: CompressionType
  ): CompressionCodecName = {
    compressionType match {
      case CompressionType.Uncompressed => CompressionCodecName.UNCOMPRESSED
      case CompressionType.Snappy       => CompressionCodecName.SNAPPY
      case CompressionType.Gzip         => CompressionCodecName.GZIP
      case CompressionType.Lzo          => CompressionCodecName.LZO
      case CompressionType.Brotli       => CompressionCodecName.BROTLI
      case CompressionType.Lz4          => CompressionCodecName.LZ4
      case CompressionType.Zstd         => CompressionCodecName.ZSTD
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
}
