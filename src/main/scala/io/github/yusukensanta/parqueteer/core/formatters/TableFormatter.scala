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
      val headerWidth = displayWidth(colName)

      val valueWidth = rows
        .map { row =>
          row
            .get(colName)
            .map(v => displayWidth(formatValue(v)))
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

  private[formatters] def drawRow(
      values: List[String],
      widths: List[Int]
  ): String = {
    val paddedValues = values.zip(widths).map { case (value, width) =>
      val truncated = truncate(value, width)
      padRight(truncated, width)
    }
    "│" + paddedValues.mkString("│") + "│"
  }

  private[formatters] def formatValue(value: Any): String = value match {
    case null                                 => "null"
    case d: Double if d.isNaN || d.isInfinite => d.toString
    case d: Double                            => f"$d%.2f"
    case f: Float if f.isNaN || f.isInfinite  => f.toString
    case f: Float                             => f"$f%.2f"
    case b: Boolean                           => if (b) "true" else "false"
    case bd: BigDecimal                       => bd.underlying.toPlainString
    case s: String                            => s
    case other                                => other.toString
  }

  private def truncate(str: String, maxWidth: Int): String = {
    if (displayWidth(str) <= maxWidth) str
    else {
      val sb = new java.lang.StringBuilder
      var w = 0
      var i = 0
      while (i < str.length) {
        val cp = str.codePointAt(i)
        val cpw = if (isWideCodePoint(cp)) 2 else 1
        if (w + cpw + 3 > maxWidth) { sb.append("..."); return sb.toString }
        sb.appendCodePoint(cp)
        w += cpw
        i += Character.charCount(cp)
      }
      sb.toString
    }
  }

  private def padRight(str: String, width: Int): String =
    str + " " * (width - displayWidth(str))

  private[formatters] def displayWidth(s: String): Int = {
    var w = 0
    var i = 0
    while (i < s.length) {
      val cp = s.codePointAt(i)
      w += (if (isWideCodePoint(cp)) 2 else 1)
      i += Character.charCount(cp)
    }
    w
  }

  private def isWideCodePoint(cp: Int): Boolean = {
    import Character.UnicodeBlock
    val b = UnicodeBlock.of(cp)
    b == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
    b == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
    b == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
    b == UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
    b == UnicodeBlock.HIRAGANA ||
    b == UnicodeBlock.KATAKANA ||
    b == UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
    b == UnicodeBlock.HANGUL_SYLLABLES ||
    b == UnicodeBlock.HANGUL_JAMO ||
    b == UnicodeBlock.HANGUL_COMPATIBILITY_JAMO ||
    (cp >= 0xff01 && cp <= 0xff60) || // fullwidth ASCII & punctuation
    (cp >= 0xffe0 && cp <= 0xffe6) // fullwidth symbols
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

  private[formatters] def formatBytes(bytes: Long): String =
    io.github.yusukensanta.parqueteer.core.util.ByteFormatter.format(bytes)
}
