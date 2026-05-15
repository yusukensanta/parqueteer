package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  FileContent,
  ParquetSchema,
  FileMetadata
}
import io.circe.{Json, Encoder}
import io.circe.syntax._
import io.circe.generic.semiauto._

class JSONFormatter extends OutputFormatter {
  import JSONFormatter.given

  override def formatContent(
      content: FileContent,
      schema: Option[ParquetSchema]
  ): String = {
    val json = Json.obj(
      "rows" -> Json.arr(content.rows.map(encodeRow)*),
      "totalRows" -> Json.fromLong(content.totalRows),
      "isPartial" -> Json.fromBoolean(content.isPartial),
      "displayedRows" -> Json.fromInt(content.rows.size)
    )

    json.spaces2
  }

  override def formatSchema(schema: ParquetSchema): String = {
    val json = Json.obj(
      "columns" -> schema.columns.asJson,
      "rowGroupCount" -> Json.fromLong(schema.rowGroupCount),
      "totalRowCount" -> Json.fromLong(schema.totalRowCount)
    )

    json.spaces2
  }

  override def formatMetadata(metadata: FileMetadata): String = {
    metadata.asJson.spaces2
  }

  private def encodeRow(row: Map[String, Any]): Json = {
    val fields = row.map { case (key, value) =>
      key -> encodeValue(value)
    }
    Json.obj(fields.toSeq*)
  }

  private def encodeValue(value: Any): Json =
    io.github.yusukensanta.parqueteer.core.util.JsonEncoder.encodeAny(value)
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
