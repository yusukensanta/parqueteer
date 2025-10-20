package com.github.yusukensanta.parqueteer.core.repositories

import com.github.yusukensanta.parqueteer.core.models._
import com.github.yusukensanta.parqueteer.cloud.CloudCredentialManager
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
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.schema.MessageType
import org.apache.parquet.example.data.Group
import scala.util.{Try, Success, Using}
import scala.jdk.CollectionConverters._

class ParquetRepository {

  def readContent(file: ParquetFile, config: ReadConfig): Try[FileContent] = {
    setupHadoopConfiguration(file.location).flatMap { hadoopConfig =>
      Try {
        val path = Parquet4sPath(file.location.path)

        // Create filter from config
        val filter = createFilter(config.filter)

        // Create ParquetReader with filter
        val reader = ParquetReader
          .as[RowParquetRecord]
          .options(
            ParquetReader.Options(
              hadoopConf = hadoopConfig
            )
          )
          .filter(filter) // Apply filter here!
          .read(path)

        // Read records with limit
        val records = config.maxRows match {
          case Some(limit) => reader.take(limit.toInt).toList
          case None        => reader.toList
        }

        // Convert RowParquetRecord to Map[String, Any]
        val rows = records.map(convertRecordToMap)

        val totalRows =
          getRowCount(new HadoopPath(file.location.path), hadoopConfig)
        val isPartial =
          config.maxRows.exists(_ < totalRows) || filter != Filter.noopFilter

        FileContent(
          rows = rows,
          totalRows = totalRows,
          isPartial = isPartial
        )
      }
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
              dataType = column.getPrimitiveType.getName,
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
        val fs = FileSystem.get(hadoopConfig)
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
    val firstRow = data.head

    firstRow.foreach { case (key, value) =>
      value match {
        case _: Int =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.INT32,
                Repetition.OPTIONAL
              )
              .named(key)
          )
        case _: Long =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.INT64,
                Repetition.OPTIONAL
              )
              .named(key)
          )
        case _: Double =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.DOUBLE,
                Repetition.OPTIONAL
              )
              .named(key)
          )
        case _: Float =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.FLOAT,
                Repetition.OPTIONAL
              )
              .named(key)
          )
        case _: Boolean =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.BOOLEAN,
                Repetition.OPTIONAL
              )
              .named(key)
          )
        case _: String =>
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
        value match {
          case i: Int     => group.add(fieldIndex, i)
          case l: Long    => group.add(fieldIndex, l)
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
        val fs = FileSystem.get(hadoopConfig)
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

  private def convertRecordToMap(record: RowParquetRecord): Map[String, Any] = {
    // RowParquetRecord in 2.x has a toMap method or similar
    // Check actual API - this might need adjustment
    record.iterator.map { case (key, value) =>
      key -> (value match {
        case null => null
        case v    => v
      })
    }.toMap
  }

  @annotation.nowarn("msg=unused")
  private def convertMapToRecord(data: Map[String, Any]): RowParquetRecord = {
    import com.github.mjakubowski84.parquet4s._
    // In parquet4s 2.x, build RowParquetRecord manually
    val fields: Iterable[(String, Value)] = data.map { case (key, value) =>
      val parquetValue: Value = value match {
        case null       => NullValue
        case s: String  => BinaryValue(s)
        case i: Int     => IntValue(i)
        case l: Long    => LongValue(l)
        case d: Double  => DoubleValue(d)
        case f: Float   => FloatValue(f)
        case b: Boolean => BooleanValue(b)
        case other      => BinaryValue(other.toString)
      }
      key -> parquetValue
    }

    RowParquetRecord(fields)
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
        import com.github.yusukensanta.parqueteer.core.filters.FilterParser

        FilterParser.parse(expr) match {
          case Right(filter) => filter
          case Left(error)   =>
            // Log error but don't fail - fall back to no filter
            println(s"Warning: Invalid filter expression: $error")
            println(s"Proceeding without filter")
            Filter.noopFilter
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
