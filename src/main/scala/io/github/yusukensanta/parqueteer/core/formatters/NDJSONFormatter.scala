package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  FileContent,
  ParquetSchema,
  FileMetadata
}
import io.circe.Json

class NDJSONFormatter extends OutputFormatter {

  override def formatContent(
      content: FileContent,
      schema: Option[ParquetSchema]
  ): String = {
    content.rows
      .map { row =>
        val fields =
          row.map { case (k, v) =>
            k -> io.github.yusukensanta.parqueteer.core.util.JsonEncoder
              .encode(v)
          }
        Json.obj(fields.toSeq*).noSpaces
      }
      .mkString("\n")
  }

  override def formatSchema(schema: ParquetSchema): String = {
    schema.columns
      .map { col =>
        Json
          .obj(
            "name" -> Json.fromString(col.name),
            "type" -> Json.fromString(col.dataType),
            "optional" -> Json.fromBoolean(col.isOptional),
            "compression" -> Json.fromString(col.compressionType)
          )
          .noSpaces
      }
      .mkString("\n")
  }

  override def formatMetadata(metadata: FileMetadata): String = {
    import io.circe.syntax._
    import io.github.yusukensanta.parqueteer.core.formatters.JSONFormatter.given
    metadata.asJson.noSpaces
  }

}
