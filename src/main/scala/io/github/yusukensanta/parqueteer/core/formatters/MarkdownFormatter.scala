package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  FileContent,
  FileMetadata,
  ParquetSchema
}

class MarkdownFormatter extends OutputFormatter {

  override def formatContent(
      content: FileContent,
      schema: Option[ParquetSchema]
  ): String =
    if content.rows.isEmpty then "No data to display"
    else {
      val columns = extractColumns(content.rows, schema)
      val sb      = new StringBuilder()

      sb.append("| ")
        .append(columns.map(escapeCell).mkString(" | "))
        .append(" |\n")
      sb.append("| ")
        .append(columns.map(_ => "---").mkString(" | "))
        .append(" |\n")

      content.rows.foreach { row =>
        val values =
          columns.map(col => escapeCell(row.get(col).fold("")(_.display)))
        sb.append("| ").append(values.mkString(" | ")).append(" |\n")
      }

      if content.isPartial then
        sb.append(
          s"\n_${content.totalRows} rows total (showing first ${content.rows.size})_\n"
        )
      else sb.append(s"\n_${content.rows.size} rows_\n")

      sb.toString
    }

  override def formatSchema(schema: ParquetSchema): String = {
    val sb = new StringBuilder()
    sb.append("## Schema\n\n")
    sb.append(s"- **Columns:** ${schema.columns.size}\n")
    sb.append(s"- **Row Groups:** ${schema.rowGroupCount}\n")
    sb.append(s"- **Total Rows:** ${schema.totalRowCount}\n\n")
    sb.append("| Name | Type | Optional | Compression |\n")
    sb.append("| --- | --- | --- | --- |\n")
    schema.columns.foreach { col =>
      sb.append(s"| ${escapeCell(col.name)} | ${escapeCell(col.dataType)} | ${
          if col.isOptional then "Yes" else "No"
        } | ${escapeCell(col.compressionType)} |\n")
    }
    sb.toString
  }

  override def formatMetadata(metadata: FileMetadata): String = {
    val sb = new StringBuilder()
    sb.append("## Metadata\n\n")
    sb.append(s"- **File Size:** ${metadata.fileSize} bytes\n")
    metadata.createdAt.foreach(t => sb.append(s"- **Created:** $t\n"))
    metadata.modifiedAt.foreach(t => sb.append(s"- **Modified:** $t\n"))
    sb.append(s"- **Parquet Version:** ${escapeCell(metadata.version)}\n")
    metadata.createdBy.foreach(c => sb.append(s"- **Created By:** ${escapeCell(c)}\n"))
    metadata.compressionRatio.foreach(r => sb.append(f"- **Compression Ratio:** $r%.2f\n"))
    sb.toString
  }

  private def escapeCell(s: String): String =
    CellValue
      .sanitizeTerminal(s)
      .replace("\\", "\\\\")
      .replace("|", "\\|")
      .replace("\r\n", " ")
      .replace("\n", " ")
      .replace("\r", " ")
}
