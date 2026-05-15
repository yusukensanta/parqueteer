package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  FileContent,
  ParquetSchema,
  FileMetadata
}

class PrettyFormatter extends OutputFormatter {

  private val Reset = "\u001b[0m"
  private val Bold = "\u001b[1m"
  private val Dim = "\u001b[2m"
  private val Red = "\u001b[31m"
  private val Green = "\u001b[32m"
  private val Yellow = "\u001b[33m"
  private val Blue = "\u001b[34m"
  private val Magenta = "\u001b[35m"
  private val Cyan = "\u001b[36m"

  private val useColors = sys.env.get("NO_COLOR").isEmpty

  private val tableFormatter = new TableFormatter()

  override def formatContent(
      content: FileContent,
      schema: Option[ParquetSchema]
  ): String = {
    if (!useColors) {
      return tableFormatter.formatContent(content, schema)
    }

    if (content.rows.isEmpty) {
      return colorize("No data to display", Yellow)
    }

    val rows = content.rows
    val columns = extractColumns(rows)
    val columnWidths = calculateColumnWidths(columns, rows)

    val sb = new StringBuilder()
    sb.append(colorize(drawTopBorder(columnWidths), Dim))
    sb.append("\n")

    sb.append(drawColoredHeaderRow(columns, columnWidths))
    sb.append("\n")

    sb.append(colorize(drawSeparator(columnWidths), Dim))
    sb.append("\n")

    rows.foreach { row =>
      sb.append(drawColoredDataRow(row, columns, columnWidths, schema))
      sb.append("\n")
    }

    sb.append(colorize(drawBottomBorder(columnWidths), Dim))
    sb.append("\n")

    sb.append(colorize(drawSummary(content), Green))

    sb.toString
  }

  override def formatSchema(schema: ParquetSchema): String = {
    if (!useColors) {
      return tableFormatter.formatSchema(schema)
    }

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
      sb.append(s"    Compression: ${colorize(col.compressionType, Yellow)}\n")
      sb.append("\n")
    }

    sb.toString
  }

  override def formatMetadata(metadata: FileMetadata): String = {
    if (!useColors) {
      return tableFormatter.formatMetadata(metadata)
    }

    val sb = new StringBuilder()

    sb.append(colorize("File Metadata", Bold + Cyan))
    sb.append("\n")
    sb.append(colorize("=============", Dim))
    sb.append("\n\n")

    sb.append(
      s"File Size: ${colorize(formatBytes(metadata.fileSize), Yellow)}\n"
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

  private def colorizeValue(value: Any): String = value match {
    case null             => colorize("null", Dim)
    case _: Int | _: Long => colorize(value.toString, Blue)
    case d: Double        => colorize(f"$d%.2f", Magenta)
    case f: Float         => colorize(f"$f%.2f", Magenta)
    case true             => colorize("true", Green)
    case false            => colorize("false", Red)
    case s: String        => colorize(s, Reset) // Normal color for strings
    case other            => colorize(other.toString, Reset)
  }

  private def drawColoredHeaderRow(
      columns: List[String],
      widths: List[Int]
  ): String = {
    val coloredColumns = columns.map(col => colorize(col, Bold + Cyan))
    val paddedValues = coloredColumns.zip(widths).map { case (value, width) =>
      val displayLength = columns(coloredColumns.indexOf(value)).length
      val padding = " " * (width - displayLength)
      value + padding
    }
    colorize("│", Dim) + paddedValues.mkString(colorize("│", Dim)) + colorize(
      "│",
      Dim
    )
  }

  private def drawColoredDataRow(
      row: Map[String, Any],
      columns: List[String],
      widths: List[Int],
      schema: Option[ParquetSchema]
  ): String = {
    val _ = schema // Reserved for future type-aware formatting
    val values = columns.map { col =>
      row
        .get(col)
        .map(v => colorizeValue(v))
        .getOrElse(colorize("null", Dim))
    }

    val paddedValues =
      values.zip(widths).zip(columns).map { case ((value, width), col) =>
        val displayLength = row.get(col).map(formatValue(_).length).getOrElse(4)
        val padding = " " * (width - displayLength)
        value + padding
      }

    colorize("│", Dim) + paddedValues.mkString(colorize("│", Dim)) + colorize(
      "│",
      Dim
    )
  }

  // Delegate to TableFormatter helpers
  private def calculateColumnWidths(
      cols: List[String],
      rows: List[Map[String, Any]]
  ) =
    tableFormatter.calculateColumnWidths(cols, rows)
  private def drawTopBorder(widths: List[Int]) =
    tableFormatter.drawTopBorder(widths)
  private def drawBottomBorder(widths: List[Int]) =
    tableFormatter.drawBottomBorder(widths)
  private def drawSeparator(widths: List[Int]) =
    tableFormatter.drawSeparator(widths)
  private def drawSummary(content: FileContent) =
    tableFormatter.drawSummary(content)
  private def formatValue(value: Any) = tableFormatter.formatValue(value)
  private def formatBytes(bytes: Long) = tableFormatter.formatBytes(bytes)
}
