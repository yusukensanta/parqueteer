package io.github.yusukensanta.parqueteer.core.util

object CsvParser {

  def parse(content: String): List[Map[String, Any]] = {
    val records = parseRfc4180(content)
    if (records.isEmpty) List.empty
    else {
      val headers = records.head
      records.tail.zipWithIndex.map { case (values, idx) =>
        if (values.length != headers.length)
          throw new IllegalArgumentException(
            s"Row ${idx + 2} has ${values.length} fields, expected ${headers.length}"
          )
        headers
          .zip(values)
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

    while (i < n) {
      content(i) match {
        case '"' if !inQuote =>
          inQuote = true
        case '"' if inQuote && i + 1 < n && content(i + 1) == '"' =>
          current.append('"'); i += 1
        case '"' if inQuote =>
          inQuote = false
        case ',' if !inQuote =>
          fields += current.toString
          current.clear()
        case '\r' if !inQuote =>
          fields += current.toString
          current.clear()
          if (fields.exists(_.nonEmpty)) records += fields.toArray
          fields.clear()
          if (i + 1 < n && content(i + 1) == '\n') i += 1
        case '\n' if !inQuote =>
          fields += current.toString
          current.clear()
          if (fields.exists(_.nonEmpty)) records += fields.toArray
          fields.clear()
        case c =>
          current.append(c)
      }
      i += 1
    }
    if (current.nonEmpty || fields.nonEmpty) {
      fields += current.toString
      if (fields.exists(_.nonEmpty)) records += fields.toArray
    }
    records.toList
  }
}
