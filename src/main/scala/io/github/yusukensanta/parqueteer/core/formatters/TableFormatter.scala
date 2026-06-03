package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  FileContent,
  ParquetSchema,
  FileMetadata
}

class TableFormatter extends OutputFormatter {
  private val MaxColumnWidth = 50
  private val MinColumnWidth = 5
  private val MaxTableRows = 10_000

  override def formatContent(
      content: FileContent,
      schema: Option[ParquetSchema]
  ): String = {
    if (content.rows.isEmpty) {
      return "No data to display"
    }

    val (rows, effectiveContent) =
      if (content.rows.size > MaxTableRows) {
        Console.err.println(
          s"[parqueteer] warning: ${content.rows.size} rows exceeds table limit ($MaxTableRows). " +
            s"Showing first $MaxTableRows rows. Use --limit N to cap output, or --format json for large datasets."
        )
        val truncated = content.rows.take(MaxTableRows)
        (truncated, content.copy(rows = truncated, isPartial = true))
      } else {
        (content.rows, content)
      }
    val columns = extractColumns(rows, schema)

    // Pre-format all cell values once: calculateColumnWidths and drawRow both
    // need the formatted string, so computing it twice per cell is wasteful.
    val fmtRows = rows.map(row =>
      columns.map(col => row.get(col).map(formatValue).getOrElse("null"))
    )
    val columnWidths = {
      val ws = columns.map(c => displayWidth(c)).toArray
      fmtRows.foreach(vs =>
        vs.zipWithIndex.foreach { case (s, i) =>
          val w = displayWidth(s)
          if (w > ws(i)) ws(i) = w
        }
      )
      ws.map(w => math.max(MinColumnWidth, math.min(MaxColumnWidth, w))).toList
    }
    val sb = new StringBuilder()

    sb.append(drawTopBorder(columnWidths))
    sb.append("\n")

    sb.append(drawRow(columns, columnWidths))
    sb.append("\n")

    sb.append(drawSeparator(columnWidths))
    sb.append("\n")

    fmtRows.foreach { vs =>
      sb.append(drawRow(vs, columnWidths))
      sb.append("\n")
    }

    sb.append(drawBottomBorder(columnWidths))
    sb.append("\n")

    sb.append(drawSummary(effectiveContent))
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
      rows: List[Map[String, CellValue]]
  ): List[Int] = {
    val widths = columns.map(c => displayWidth(c)).toArray
    rows.foreach { row =>
      columns.zipWithIndex.foreach { case (col, i) =>
        val w = row.get(col).map(v => displayWidth(formatValue(v))).getOrElse(0)
        if (w > widths(i)) widths(i) = w
      }
    }
    widths
      .map(w => math.max(MinColumnWidth, math.min(MaxColumnWidth, w)))
      .toList
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

  private[formatters] def formatValue(value: CellValue): String = value.display

  private[formatters] def truncate(str: String, maxWidth: Int): String = {
    if (maxWidth <= 0) return ""
    if (maxWidth <= 3) return ".".repeat(maxWidth)
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
