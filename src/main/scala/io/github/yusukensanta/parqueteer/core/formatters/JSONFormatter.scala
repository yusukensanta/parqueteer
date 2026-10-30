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
    Json.fromFields(
      row.view.mapValues(io.github.yusukensanta.parqueteer.core.util.JsonEncoder.encode)
    )
}

object JSONFormatter {

  import io.github.yusukensanta.parqueteer.core.models.{
    ColumnInfo,
    GroupInfo,
    LogicalTypeDetail,
    SchemaNode
  }
  import java.time.Instant

  // {"type": "TIMESTAMP", "unit": "MICROS", "isAdjustedToUTC": true}
  given Encoder[LogicalTypeDetail] = Encoder.instance { lt =>
    def paramJson(v: String): Json = v match {
      case "true"                   => Json.True
      case "false"                  => Json.False
      case n if n.matches("-?\\d+") => Json.fromLong(n.toLong)
      case other                    => Json.fromString(other)
    }
    Json.fromFields(
      ("type" -> Json.fromString(lt.name)) :: lt.params.map { case (k, v) => k -> paramJson(v) }
    )
  }

  given Encoder[GroupInfo] =
    deriveEncoder[GroupInfo]

  given Encoder[SchemaNode] =
    deriveEncoder[SchemaNode]

  given Encoder[ColumnInfo] =
    deriveEncoder[ColumnInfo]

  given Encoder[ParquetSchema] =
    deriveEncoder[ParquetSchema]

  given Encoder[Instant] =
    Encoder.encodeString.contramap(_.toString)

  given Encoder[FileMetadata] =
    deriveEncoder[FileMetadata]
}
