package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  FileContent,
  FileMetadata,
  ParquetSchema
}

class LTSVFormatter extends OutputFormatter {

  override def formatContent(
      content: FileContent,
      schema: Option[ParquetSchema]
  ): String = {
    val rows = content.rows.map(rowToLtsv).mkString("\n")
    if (content.isPartial)
      rows + s"\n# partial:true\ttotal_rows:${content.totalRows}\tshown:${content.rows.size}"
    else
      rows
  }

  override def formatSchema(schema: ParquetSchema): String =
    schema.columns
      .map { col =>
        s"name:${sanitize(col.name)}\ttype:${col.dataType}\toptional:${col.isOptional}\tcompression:${col.compressionType}"
      }
      .mkString("\n")

  override def formatMetadata(metadata: FileMetadata): String =
    Seq(
      s"file_size:${metadata.fileSize}",
      s"created_at:${metadata.createdAt.map(_.toString).getOrElse("")}",
      s"modified_at:${metadata.modifiedAt.map(_.toString).getOrElse("")}",
      s"version:${sanitize(metadata.version)}",
      s"created_by:${metadata.createdBy.map(sanitize).getOrElse("")}",
      s"compression_ratio:${metadata.compressionRatio.map(_.toString).getOrElse("")}"
    ).mkString("\t")

  private[formatters] def rowToLtsv(row: Map[String, CellValue]): String =
    row
      .map { case (k, v) => s"${sanitize(k)}:${sanitizeValue(v.display)}" }
      .mkString("\t")

  private def sanitize(label: String): String =
    label.map(c =>
      if (c.isLetterOrDigit || c == '_' || c == '.' || c == '-') c else '_'
    )

  private def sanitizeValue(value: String): String =
    value.replace("\t", " ").replace("\r", "").replace("\n", " ")
}
