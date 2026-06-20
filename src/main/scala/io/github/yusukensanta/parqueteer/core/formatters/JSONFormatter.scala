package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  FileContent,
  FileMetadata,
  ParquetSchema
}
import io.circe.{Encoder, Json}
import io.circe.syntax.*
import io.circe.generic.semiauto.*

class JSONFormatter extends OutputFormatter {
  import JSONFormatter.given

  override def formatContent(
      content: FileContent,
      schema: Option[ParquetSchema]
  ): String = {
    val json = Json.obj(
      "rows"          -> Json.arr(content.rows.map(encodeRow)*),
      "totalRows"     -> Json.fromLong(content.totalRows),
      "isPartial"     -> Json.fromBoolean(content.isPartial),
      "displayedRows" -> Json.fromInt(content.rows.size)
    )

    json.noSpaces
  }

  override def formatSchema(schema: ParquetSchema): String = {
    val json = Json.obj(
      "columns"       -> schema.columns.asJson,
      "rowGroupCount" -> Json.fromLong(schema.rowGroupCount),
      "totalRowCount" -> Json.fromLong(schema.totalRowCount)
    )

    json.noSpaces
  }

  override def formatMetadata(metadata: FileMetadata): String =
    metadata.asJson.noSpaces

  private def encodeRow(row: Map[String, CellValue]): Json =
    Json.fromFields(row.map { case (key, value) =>
      key -> io.github.yusukensanta.parqueteer.core.util.JsonEncoder
        .encode(value)
    })
}

object JSONFormatter {

  import io.github.yusukensanta.parqueteer.core.models.ColumnInfo
  import java.time.Instant

  given Encoder[ColumnInfo] =
    deriveEncoder[ColumnInfo]

  given Encoder[ParquetSchema] =
    deriveEncoder[ParquetSchema]

  given Encoder[Instant] =
    Encoder.encodeString.contramap(_.toString)

  given Encoder[FileMetadata] =
    deriveEncoder[FileMetadata]
}
