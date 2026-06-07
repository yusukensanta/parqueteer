package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  FileContent,
  ParquetSchema,
  FileMetadata
}

class PrettyFormatter(
    useColors: Boolean = !sys.env.get("NO_COLOR").exists(_.nonEmpty)
) extends OutputFormatter {

  private val Reset = "\u001b[0m"
  private val Bold = "\u001b[1m"
  private val Dim = "\u001b[2m"
  private val Red = "\u001b[31m"
  private val Green = "\u001b[32m"
  private val Yellow = "\u001b[33m"
  private val Blue = "\u001b[34m"
  private val Magenta = "\u001b[35m"
  private val Cyan = "\u001b[36m"

  private val tableFormatter = new TableFormatter()

  override def formatContent(
      content: FileContent,
      schema: Option[ParquetSchema]
  ): String =
    if (!useColors) tableFormatter.formatContent(content, schema)
    else if (content.rows.isEmpty) colorize("No data to display", Yellow)
    else {
      val rows = content.rows
      val columns = extractColumns(rows, schema)
      val columnWidths = tableFormatter.calculateColumnWidths(columns, rows)

      val sb = new StringBuilder()
      sb.append(colorize(tableFormatter.drawTopBorder(columnWidths), Dim))
      sb.append("\n")
      sb.append(drawColoredHeaderRow(columns, columnWidths))
      sb.append("\n")
      sb.append(colorize(tableFormatter.drawSeparator(columnWidths), Dim))
      sb.append("\n")
      rows.foreach { row =>
        sb.append(drawColoredDataRow(row, columns, columnWidths))
        sb.append("\n")
      }
      sb.append(colorize(tableFormatter.drawBottomBorder(columnWidths), Dim))
      sb.append("\n")
      sb.append(colorize(tableFormatter.drawSummary(content), Green))
      sb.toString
    }

  override def formatSchema(schema: ParquetSchema): String =
    if (!useColors) tableFormatter.formatSchema(schema)
    else {
      val sb = new StringBuilder()

      sb.append(colorize("Schema Information", Bold + Cyan))
      sb.append("\n")
      sb.append(colorize("==================", Dim))
      sb.append("\n\n")

      sb.append(
        s"Total Columns: ${colorize(schema.columns.size.toString, Yellow)}\n"
      )
      sb.append(
        s"Row Groups: ${colorize(schema.rowGroupCount.toString, Yellow)}\n"
      )
      sb.append(
        s"Total Rows: ${colorize(schema.totalRowCount.toString, Yellow)}\n\n"
      )

      sb.append(colorize("Columns:", Bold))
      sb.append("\n\n")

      schema.columns.foreach { col =>
        sb.append(s"  ${colorize(col.name, Cyan)}\n")
        sb.append(s"    Type: ${colorizeType(col.dataType)}\n")
        sb.append(s"    Optional: ${
            if (col.isOptional) colorize("Yes", Green) else colorize("No", Red)
          }\n")
        sb.append(
          s"    Compression: ${colorize(col.compressionType, Yellow)}\n"
        )
        sb.append("\n")
      }

      sb.toString
    }

  override def formatMetadata(metadata: FileMetadata): String =
    if (!useColors) tableFormatter.formatMetadata(metadata)
    else {
      val sb = new StringBuilder()

      sb.append(colorize("File Metadata", Bold + Cyan))
      sb.append("\n")
      sb.append(colorize("=============", Dim))
      sb.append("\n\n")

      sb.append(
        s"File Size: ${colorize(tableFormatter.formatBytes(metadata.fileSize), Yellow)}\n"
      )

      metadata.createdAt.foreach { created =>
        sb.append(s"Created: ${colorize(created.toString, Cyan)}\n")
      }

      metadata.modifiedAt.foreach { modified =>
        sb.append(s"Modified: ${colorize(modified.toString, Cyan)}\n")
      }

      sb.append(s"Version: ${colorize(metadata.version, Yellow)}\n")

      metadata.createdBy.foreach { creator =>
        sb.append(s"Created By: ${colorize(creator, Cyan)}\n")
      }

      metadata.compressionRatio.foreach { ratio =>
        sb.append(s"Compression Ratio: ${colorize(f"${ratio}%.2f", Yellow)}\n")
      }

      sb.toString
    }

  private def colorize(text: String, color: String): String = {
    if (useColors) s"$color$text$Reset"
    else text
  }

  private def colorizeType(dataType: String): String = {
    dataType.toUpperCase match {
      case t if t.contains("INT") => colorize(dataType, Blue)
      case t if t.contains("FLOAT") || t.contains("DOUBLE") =>
        colorize(dataType, Magenta)
      case t if t.contains("STRING") || t.contains("BINARY") =>
        colorize(dataType, Green)
      case t if t.contains("BOOL") => colorize(dataType, Yellow)
      case _                       => colorize(dataType, Cyan)
    }
  }

  private def colorizeFormatted(formatted: String, cv: CellValue): String =
    cv match {
      case CellValue.Null                      => colorize(formatted, Dim)
      case CellValue.I32(_) | CellValue.I64(_) => colorize(formatted, Blue)
      case CellValue.F64(_) | CellValue.F32(_) => colorize(formatted, Magenta)
      case CellValue.Bool(true)                => colorize(formatted, Green)
      case CellValue.Bool(false)               => colorize(formatted, Red)
      case _                                   => colorize(formatted, Reset)
    }

  private def drawColoredHeaderRow(
      columns: List[String],
      widths: List[Int]
  ): String = {
    val paddedValues = columns.zip(widths).map { case (col, width) =>
      val truncated = tableFormatter.truncate(col, width)
      colorize(truncated, Bold + Cyan) + " " * (width - tableFormatter
        .displayWidth(truncated))
    }
    colorize("│", Dim) + paddedValues.mkString(colorize("│", Dim)) + colorize(
      "│",
      Dim
    )
  }

  private def drawColoredDataRow(
      row: Map[String, CellValue],
      columns: List[String],
      widths: List[Int]
  ): String = {
    val paddedValues =
      columns.zip(widths).map { case (col, width) =>
        val cv = row.getOrElse(col, CellValue.Null)
        val formatted = tableFormatter.truncate(cv.display, width)
        val colored = colorizeFormatted(formatted, cv)
        val padding = " " * (width - tableFormatter.displayWidth(formatted))
        colored + padding
      }

    colorize("│", Dim) + paddedValues.mkString(colorize("│", Dim)) + colorize(
      "│",
      Dim
    )
  }

}
