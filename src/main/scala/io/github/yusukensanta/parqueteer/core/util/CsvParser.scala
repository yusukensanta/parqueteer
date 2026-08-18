package io.github.yusukensanta.parqueteer.core.util

import io.github.yusukensanta.parqueteer.core.models.CellValue

object CsvParser {

  def parse(content: String): List[Map[String, CellValue]] =
    parseStream(content).toList

  def parseStream(content: String): Iterator[Map[String, CellValue]] =
    rowsToMaps(parseRfc4180(content).iterator)

  /**
   * Converts a header row + data rows into typed record maps. Shared by the
   * whole-content path (`parseRfc4180(content).iterator`) and the streaming
   * path (`parseRecordsIncremental`) so both apply identical header/trailing
   * comma/field-count handling.
   */
  def rowsToMaps(
      records: Iterator[Array[String]]
  ): Iterator[Map[String, CellValue]] =
    if !records.hasNext then Iterator.empty
    else {
      val headers             = records.next()
      var trailingCommaWarned = false
      var rowNum              = 1
      records.map { values =>
        rowNum += 1
        val normalized =
          if values.length == headers.length + 1 && values.last.isEmpty then {
            if !trailingCommaWarned then {
              Warnings.emit(
                s"CSV row $rowNum has a trailing comma — extra empty field ignored. Suppress with a consistent schema."
              )
              trailingCommaWarned = true
            }
            values.init
          } else values
        if normalized.length != headers.length then
          throw new IllegalArgumentException(
            s"Row $rowNum has ${normalized.length} fields, expected ${headers.length}"
          )
        scala.collection.immutable.ListMap.from(
          headers.zip(normalized).map { case (h, v) =>
            h -> TypeInferrer.inferCsvValue(v)
          }
        )
      }
    }

  /**
   * Parses CSV rows from a line iterator (e.g. `Source.fromFile(path).getLines()`)
   * instead of a fully-materialized String, so callers can stream a file
   * without loading it whole. `getLines()` strips line terminators, which
   * would corrupt a quoted field containing a literal newline — those need
   * to be re-joined across line-iterator calls before being handed to
   * `parseRfc4180`, which already implements the full RFC 4180 state
   * machine (quote/escape/delimiter handling) correctly for a complete
   * chunk of text.
   */
  def parseRecordsIncremental(lines: Iterator[String]): Iterator[Array[String]] =
    new Iterator[Array[String]] {
      private var pending: List[Array[String]] = Nil
      private var carry: String                = ""

      private def fill(): Unit =
        while pending.isEmpty && lines.hasNext do {
          val line      = lines.next()
          val candidate = if carry.isEmpty then line else carry + "\n" + line
          try {
            pending = parseRfc4180(candidate + "\n")
            carry = ""
          } catch {
            case e: IllegalArgumentException
                if e.getMessage != null &&
                  e.getMessage.startsWith("Unterminated quoted field") =>
              carry = candidate
          }
        }
        // Input exhausted with an unresolved quoted field: no more lines will
        // arrive to close it, so surface the same error `parseRfc4180` would
        // raise for whole-content input, instead of silently dropping it.
        if pending.isEmpty && carry.nonEmpty && !lines.hasNext then parseRfc4180(carry + "\n"): Unit

      def hasNext: Boolean = { fill(); pending.nonEmpty }

      def next(): Array[String] = {
        fill()
        if pending.isEmpty then throw new NoSuchElementException("next on empty iterator")
        val row = pending.head
        pending = pending.tail
        row
      }
    }

  def parseRfc4180(content: String): List[Array[String]] = {
    val records = scala.collection.mutable.ListBuffer.empty[Array[String]]
    val fields  = scala.collection.mutable.ArrayBuffer.empty[String]
    val current = new StringBuilder
    var inQuote = false
    // RFC 4180 §2.5: after a closing quote, only delimiter/newline/EOF is valid.
    var postQuote = false
    var i         = 0
    val n         = content.length

    def finishRow(): Unit = {
      val row = (fields :+ current.toString).toArray
      current.clear()
      fields.clear()
      // A completely blank line produces exactly one empty field; skip it.
      if row.length > 1 || (row.length == 1 && row(0).nonEmpty) then records += row
    }

    while i < n do {
      content(i) match {
        // RFC 4180: a quote is only meaningful at the start of a field
        case '"' if !inQuote && current.isEmpty =>
          inQuote = true
        case '"' if !inQuote =>
          postQuote = false
          current.append('"')
        case '"' if inQuote && i + 1 < n && content(i + 1) == '"' =>
          current.append('"'); i += 1
        case '"' if inQuote =>
          inQuote = false
          postQuote = true
        case ',' if !inQuote =>
          postQuote = false
          fields += current.toString
          current.clear()
        case '\r' if !inQuote =>
          postQuote = false
          finishRow()
          if i + 1 < n && content(i + 1) == '\n' then i += 1
        case '\n' if !inQuote =>
          postQuote = false
          finishRow()
        case c =>
          if postQuote then
            throw new IllegalArgumentException(
              s"Malformed CSV: data after closing quote at position $i — " +
                "use double-quotes to include quotes inside a field"
            )
          current.append(c)
      }
      i += 1
    }
    if inQuote then
      throw new IllegalArgumentException(
        "Unterminated quoted field in CSV input"
      )
    // Handle content not terminated by a newline
    if current.nonEmpty || fields.nonEmpty then finishRow()
    records.toList
  }
}
