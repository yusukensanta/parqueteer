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
      records.iterator.drop(1).zipWithIndex.map { case (values, idx) =>
        // Tolerate a single trailing empty field produced by a trailing comma
        val normalized =
          if (values.length == headers.length + 1 && values.last.isEmpty)
            values.init
          else values
        if (normalized.length != headers.length)
          throw new IllegalArgumentException(
            s"Row ${idx + 2} has ${normalized.length} fields, expected ${headers.length}"
          )
        headers
          .zip(normalized)
          .map { case (h, v) => h -> TypeInferrer.inferCsvValue(v) }
          .toMap
      }
    }
  }

  def parseRfc4180(content: String): List[Array[String]] = {
    val records = scala.collection.mutable.ListBuffer.empty[Array[String]]
    val fields = scala.collection.mutable.ArrayBuffer.empty[String]
    val current = new StringBuilder
    var inQuote = false
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
          current.append('"')
        case '"' if inQuote && i + 1 < n && content(i + 1) == '"' =>
          current.append('"'); i += 1
        case '"' if inQuote =>
          inQuote = false
        case ',' if !inQuote =>
          fields += current.toString
          current.clear()
        case '\r' if !inQuote =>
          finishRow()
          if (i + 1 < n && content(i + 1) == '\n') i += 1
        case '\n' if !inQuote =>
          finishRow()
        case c =>
          current.append(c)
      }
      i += 1
    }
    // Handle content not terminated by a newline
    if (current.nonEmpty || fields.nonEmpty)
      finishRow()
    records.toList
  }
}
