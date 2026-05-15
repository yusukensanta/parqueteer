package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  FileContent,
  ParquetSchema,
  FileMetadata
}

class TableFormatter extends OutputFormatter {
  private val MaxColumnWidth = 50 // prevents extremely wide columns
  private val MinColumnWidth = 5 // ensure readability

  override def formatContent(
      content: FileContent,
      schema: Option[ParquetSchema]
  ): String = {
    if (content.rows.isEmpty) {
      return "No data to display"
    }

    val rows = content.rows
    val columns = extractColumns(rows)

    val columnWidths = calculateColumnWidths(columns, rows)
    val sb = new StringBuilder()

    sb.append(drawTopBorder(columnWidths))
    sb.append("\n")

    sb.append(drawRow(columns, columnWidths))
    sb.append("\n")

    sb.append(drawSeparator(columnWidths))
    sb.append("\n")

    rows.foreach { row =>
      sb.append(drawDataRow(row, columns, columnWidths, schema))
      sb.append("\n")
    }

    sb.append(drawBottomBorder(columnWidths))
    sb.append("\n")

    sb.append(drawSummary(content))
    sb.toString
  }

  override def formatSchema(schema: ParquetSchema): String = {
    val sb = new StringBuilder()

    sb.append("Schema Information\n")
    sb.append("==================\n\n")
    sb.append(s"Total Columns: ${schema.columns.size}\n")
    sb.append(s"Row Groups: ${schema.rowGroupCount}\n")
    sb.append(s"Total Rows: ${schema.totalRowCount}\n\n")

    sb.append("Columns:\n")

    // Create table for column information
    val headers = List("Name", "Type", "Optional", "Compression")
    val widths = Map(
      "Name" -> 30,
      "Type" -> 15,
      "Optional" -> 10,
      "Compression" -> 15
    )

    sb.append(drawTopBorder(widths.values.toList))
    sb.append("\n")
    sb.append(drawRow(headers, widths.values.toList))
    sb.append("\n")
    sb.append(drawSeparator(widths.values.toList))
    sb.append("\n")

    schema.columns.foreach { col =>
      val row = List(
        truncate(col.name, 30),
        col.dataType,
        if (col.isOptional) "Yes" else "No",
        col.compressionType
      )
      sb.append(drawRow(row, widths.values.toList))
      sb.append("\n")
    }

    sb.append(drawBottomBorder(widths.values.toList))

    sb.toString
  }

  override def formatMetadata(metadata: FileMetadata): String = {
    val sb = new StringBuilder()

    sb.append("File Metadata\n")
    sb.append("=============\n\n")

    // Format file size in human-readable format
    sb.append(f"File Size: ${formatBytes(metadata.fileSize)}\n")

    metadata.createdAt.foreach { created =>
      sb.append(s"Created: $created\n")
    }

    metadata.modifiedAt.foreach { modified =>
      sb.append(s"Modified: $modified\n")
    }

    sb.append(s"Parquet Version: ${metadata.version}\n")

    metadata.createdBy.foreach { creator =>
      sb.append(s"Created By: $creator\n")
    }

    metadata.compressionRatio.foreach { ratio =>
      sb.append(f"Compression Ratio: ${ratio}%.2f\n")
    }

    sb.toString
  }

  private[formatters] def calculateColumnWidths(
      columns: List[String],
      rows: List[Map[String, Any]]
  ): List[Int] = {
    columns.map { colName =>
      val headerWidth = colName.length

      val valueWidth = rows
        .map { row =>
          row
            .get(colName)
            .map(formatValue(_).length)
            .getOrElse(0)
        }
        .maxOption
        .getOrElse(0)

      val width = math.max(headerWidth, valueWidth)
      math.max(MinColumnWidth, math.min(MaxColumnWidth, width))
    }
  }

  private[formatters] def drawTopBorder(widths: List[Int]): String = {
    val segments = widths.map("─" * (_))
    "┌" + segments.mkString("┬") + "┐"
  }

  private[formatters] def drawBottomBorder(widths: List[Int]): String = {
    val segments = widths.map("─" * (_))
    "└" + segments.mkString("┴") + "┘"
  }

  private[formatters] def drawSeparator(widths: List[Int]): String = {
    val segments = widths.map("─" * (_))
    "├" + segments.mkString("┼") + "┤"
  }

  private def drawDataRow(
      row: Map[String, Any],
      columns: List[String],
      widths: List[Int],
      schema: Option[ParquetSchema]
  ): String = {
    val _ = schema // Reserved for future type-aware formatting
    val values = columns.map { col =>
      row
        .get(col)
        .map(v => formatValue(v))
        .getOrElse("null")
    }
    drawRow(values, widths)
  }

  private def drawRow(values: List[String], widths: List[Int]): String = {
    val paddedValues = values.zip(widths).map { case (value, width) =>
      val truncated = truncate(value, width)
      padRight(truncated, width)
    }
    "│" + paddedValues.mkString("│") + "│"
  }

  private[formatters] def formatValue(value: Any): String = value match {
    case null       => "null"
    case d: Double  => f"$d%.2f" // 2 decimal places for doubles
    case f: Float   => f"$f%.2f"
    case b: Boolean => if (b) "true" else "false"
    case s: String  => s
    case other      => other.toString
  }

  private def truncate(str: String, maxWidth: Int): String = {
    if (str.length <= maxWidth) str
    else str.take(maxWidth - 3) + "..."
  }

  private def padRight(str: String, width: Int): String = {
    str + (" " * (width - str.length))
  }

  private[formatters] def drawSummary(content: FileContent): String = {
    val displayed = content.rows.size
    val total = content.totalRows

    if (content.isPartial) {
      s"$total rows total (showing first $displayed)"
    } else {
      s"$displayed rows (showing all)"
    }
  }

  private[formatters] def formatBytes(bytes: Long): String = {
    val units = List("B", "KB", "MB", "GB", "TB")
    @annotation.tailrec
    def loop(size: Double, unitIndex: Int): String =
      if (size < 1024 || unitIndex >= units.length - 1)
        f"$size%.2f ${units(unitIndex)}"
      else loop(size / 1024, unitIndex + 1)
    loop(bytes.toDouble, 0)
  }
}
