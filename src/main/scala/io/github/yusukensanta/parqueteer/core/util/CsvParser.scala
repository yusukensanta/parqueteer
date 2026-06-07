package io.github.yusukensanta.parqueteer.core.util

import io.github.yusukensanta.parqueteer.core.models.CellValue

object CsvParser {

  def parse(content: String): List[Map[String, CellValue]] =
    parseStream(content).toList

  def parseStream(content: String): Iterator[Map[String, CellValue]] = {
    val records = parseRfc4180(content)
    if (records.isEmpty) Iterator.empty
    else {
      val headers = records.head
      var trailingCommaWarned = false
      records.iterator.drop(1).zipWithIndex.map { case (values, idx) =>
        val normalized =
          if (values.length == headers.length + 1 && values.last.isEmpty) {
            if (!trailingCommaWarned) {
              Console.err.println(
                s"[parqueteer] warning: CSV row ${idx + 2} has a trailing comma — extra empty field ignored. Suppress with a consistent schema."
              )
              trailingCommaWarned = true
            }
            values.init
          } else values
        if (normalized.length != headers.length)
          throw new IllegalArgumentException(
            s"Row ${idx + 2} has ${normalized.length} fields, expected ${headers.length}"
          )
        scala.collection.immutable.ListMap.from(
          headers.zip(normalized).map { case (h, v) =>
            h -> TypeInferrer.inferCsvValue(v)
          }
        )
      }
    }
  }

  def parseRfc4180(content: String): List[Array[String]] = {
    val records = scala.collection.mutable.ListBuffer.empty[Array[String]]
    val fields = scala.collection.mutable.ArrayBuffer.empty[String]
    val current = new StringBuilder
    var inQuote = false
    // RFC 4180 §2.5: after a closing quote, only delimiter/newline/EOF is valid.
    var postQuote = false
    var i = 0
    val n = content.length

    def finishRow(): Unit = {
      val row = (fields :+ current.toString).toArray
      current.clear()
      fields.clear()
      // A completely blank line produces exactly one empty field; skip it.
      if (row.length > 1 || (row.length == 1 && row(0).nonEmpty))
        records += row
    }

    while (i < n) {
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
          if (i + 1 < n && content(i + 1) == '\n') i += 1
        case '\n' if !inQuote =>
          postQuote = false
          finishRow()
        case c =>
          if (postQuote)
            throw new IllegalArgumentException(
              s"Malformed CSV: data after closing quote at position $i — " +
                "use double-quotes to include quotes inside a field"
            )
          current.append(c)
      }
      i += 1
    }
    if (inQuote)
      throw new IllegalArgumentException(
        "Unterminated quoted field in CSV input"
      )
    // Handle content not terminated by a newline
    if (current.nonEmpty || fields.nonEmpty)
      finishRow()
    records.toList
  }
}
