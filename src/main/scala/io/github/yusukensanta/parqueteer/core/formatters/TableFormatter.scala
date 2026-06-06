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

    val hasEncodings = schema.columns.exists(_.encodings.nonEmpty)
    val headers =
      if (hasEncodings) List("Name", "Type", "Optional", "Encoding")
      else List("Name", "Type", "Optional")
    val columnWidths =
      if (hasEncodings) List(30, 15, 10, 30)
      else List(30, 15, 10)

    sb.append(drawTopBorder(columnWidths))
    sb.append("\n")
    sb.append(drawRow(headers, columnWidths))
    sb.append("\n")
    sb.append(drawSeparator(columnWidths))
    sb.append("\n")

    schema.columns.foreach { col =>
      val row =
        if (hasEncodings)
          List(
            truncate(col.name, 30),
            col.dataType,
            if (col.isOptional) "Yes" else "No",
            col.encodings.mkString(",")
          )
        else
          List(
            truncate(col.name, 30),
            col.dataType,
            if (col.isOptional) "Yes" else "No"
          )
      sb.append(drawRow(row, columnWidths))
      sb.append("\n")
    }

    sb.append(drawBottomBorder(columnWidths))

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
      sb.append(s"Created By:      $creator\n")
    }

    metadata.compressionType.foreach { codec =>
      sb.append(s"Compression:     $codec\n")
    }

    metadata.compressionRatio.foreach { ratio =>
      sb.append(f"Compression Ratio: ${ratio}%.2f\n")
    }

    metadata.avgRowGroupSizeBytes.foreach { sz =>
      sb.append(s"Avg Row Group:   ${formatBytes(sz)}\n")
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

  private def isWideCodePoint(cp: Int): Boolean =
    // Unicode East Asian Width W (Wide) and F (Fullwidth) — renders as 2 terminal columns.
    // Ranges derived from Unicode EAW property data (ucd/EastAsianWidth.txt).
    (cp >= 0x1100 && cp <= 0x115f) || // Hangul Jamo
      (cp >= 0x2e80 && cp <= 0x303f) || // CJK Radicals, Kangxi, CJK Symbols+Punct (。、《【〇　)
      (cp >= 0x3040 && cp <= 0x33ff) || // Hiragana, Katakana, Bopomofo, Enclosed CJK, CJK Compat
      (cp >= 0x3400 && cp <= 0x4dbf) || // CJK Extension A
      (cp >= 0x4e00 && cp <= 0x9fff) || // CJK Unified Ideographs
      (cp >= 0xa000 && cp <= 0xa4cf) || // Yi Syllables + Radicals
      (cp >= 0xa960 && cp <= 0xa97f) || // Hangul Jamo Extended-A
      (cp >= 0xac00 && cp <= 0xd7af) || // Hangul Syllables
      (cp >= 0xf900 && cp <= 0xfaff) || // CJK Compatibility Ideographs
      (cp >= 0xfe10 && cp <= 0xfe6f) || // Vertical Forms, CJK Compat Forms, Small Form Variants
      (cp >= 0xff00 && cp <= 0xff60) || // Fullwidth Latin & ASCII
      (cp >= 0xffe0 && cp <= 0xffe6) || // Fullwidth symbols
      (cp >= 0x1b000 && cp <= 0x1b16f) || // Kana Supplement, Kana Extended-A, Small Kana Extension
      (cp >= 0x20000 && cp <= 0x2fffd) || // CJK Extensions B–F (supplementary planes)
      (cp >= 0x30000 && cp <= 0x3fffd) // CJK Extensions G+ (supplementary planes)

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
