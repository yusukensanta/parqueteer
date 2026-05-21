package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path => HadoopPath}
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.schema.MessageType
import scala.util.Using
import scala.jdk.CollectionConverters._

private[repositories] object ParquetSchemaBuilder {

  def projectSchema(
      fileSchema: MessageType,
      columns: List[String]
  ): MessageType = {
    val columnSet = columns.toSet
    val fields = fileSchema.getFields.asScala
      .filter(f => columnSet.contains(f.getName))
      .toList
    if (fields.isEmpty)
      throw new IllegalArgumentException(
        s"None of the requested columns exist in the file: ${columns.mkString(", ")}"
      )
    new MessageType("root", fields.asJava)
  }

  def buildProjectedSchema(
      path: HadoopPath,
      conf: Configuration,
      columns: List[String]
  ): MessageType = {
    val inputFile = HadoopInputFile.fromPath(path, conf)
    Using.resource(ParquetFileReader.open(inputFile)) { reader =>
      projectSchema(reader.getFooter.getFileMetaData.getSchema, columns)
    }
  }

  def buildMessageType(schema: ParquetSchema): MessageType = {
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
        case "DATE" =>
          builder.addField(
            Types
              .primitive(PrimitiveType.PrimitiveTypeName.INT32, repetition)
              .as(org.apache.parquet.schema.LogicalTypeAnnotation.dateType())
              .named(col.name)
          )
        case "TIMESTAMP" | "TIMESTAMP_MILLIS" =>
          builder.addField(
            Types
              .primitive(PrimitiveType.PrimitiveTypeName.INT64, repetition)
              .as(
                org.apache.parquet.schema.LogicalTypeAnnotation.timestampType(
                  true,
                  org.apache.parquet.schema.LogicalTypeAnnotation.TimeUnit.MILLIS
                )
              )
              .named(col.name)
          )
        case "STRING" =>
          builder.addField(
            Types
              .primitive(PrimitiveType.PrimitiveTypeName.BINARY, repetition)
              .as(org.apache.parquet.schema.LogicalTypeAnnotation.stringType())
              .named(col.name)
          )
        case "BINARY" =>
          builder.addField(
            Types
              .primitive(PrimitiveType.PrimitiveTypeName.BINARY, repetition)
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
  def inferSchemaFromData(data: List[Map[String, Any]]): MessageType = {
    import org.apache.parquet.schema.{Types, PrimitiveType}
    import org.apache.parquet.schema.Type.Repetition

    if (data.isEmpty) {
      throw new IllegalArgumentException("Cannot infer schema from empty data")
    }

    val builder = Types.buildMessage()
    val allKeys = data.flatMap(_.keys).distinct.sorted

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
        case Some(_: java.time.LocalDate) =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.INT32,
                Repetition.OPTIONAL
              )
              .as(org.apache.parquet.schema.LogicalTypeAnnotation.dateType())
              .named(key)
          )
        case Some(_: java.time.Instant) =>
          builder.addField(
            Types
              .primitive(
                PrimitiveType.PrimitiveTypeName.INT64,
                Repetition.OPTIONAL
              )
              .as(
                org.apache.parquet.schema.LogicalTypeAnnotation.timestampType(
                  true,
                  org.apache.parquet.schema.LogicalTypeAnnotation.TimeUnit.MILLIS
                )
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
}
