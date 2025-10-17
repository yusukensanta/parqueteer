package io.parqueteer.core.formatters

import io.parqueteer.core.models.{FileContent, ParquetSchema, FileMetadata}

class CSVFormatter extends OutputFormatter {

  private val Delimiter = ","
  private val Quote = "\""
  private val Newline = "\n"

  override def formatContent(
      content: FileContent,
      schema: Option[ParquetSchema]
  ): String = {
    if (content.rows.isEmpty) {
      return ""
    }

    val rows = content.rows
    val columns = extractColumns(rows)

    val sb = new StringBuilder()

    sb.append(formatRow(columns))
    sb.append(Newline)

    rows.foreach { row =>
      val values = columns.map { col =>
        row
          .get(col)
          .map(formatValue)
          .getOrElse("")
      }
      sb.append(formatRow(values))
      sb.append(Newline)
    }

    sb.toString
  }

  override def formatSchema(schema: ParquetSchema): String = {
    val headers = List("Column Name", "Data Type", "Optional", "Compression")
    val sb = new StringBuilder()

    sb.append(formatRow(headers))
    sb.append(Newline)

    schema.columns.foreach { col =>
      val row = List(
        col.name,
        col.dataType,
        if (col.isOptional) "Yes" else "No",
        col.compressionType
      )
      sb.append(formatRow(row))
      sb.append(Newline)
    }
    sb.toString
  }

  override def formatMetadata(metadata: FileMetadata): String = {
    val sb = new StringBuilder()

    sb.append(formatRow(List("Propetry", "Value")))
    sb.append(Newline)

    sb.append(formatRow(List("File Size", metadata.fileSize.toString)))
    sb.append(Newline)

    metadata.createdAt.foreach { created =>
      sb.append(formatRow(List("Created At", created.toString)))
      sb.append(Newline)
    }

    metadata.modifiedAt.foreach { modified =>
      sb.append(formatRow(List("Modified At", modified.toString)))
      sb.append(Newline)
    }

    sb.append(formatRow(List("Version", metadata.version)))
    sb.append(Newline)

    sb.toString
  }

  private def extractColumns(rows: List[Map[String, Any]]): List[String] = {
    rows.flatMap(_.keys).distinct.sorted
  }

  private def formatRow(values: List[String]): String = {
    values.map(escapeField).mkString(Delimiter)
  }

  private def formatValue(value: Any): String = value match {
    case null       => "" // Empty for null
    case d: Double  => d.toString
    case f: Float   => f.toString
    case b: Boolean => b.toString
    case s: String  => s
    case other      => other.toString
  }

  private def escapeField(field: String): String = {
    val needsQuoting = field.contains(Delimiter) ||
      field.contains(Quote) ||
      field.contains("\n") ||
      field.contains("\r")

    if (needsQuoting) {
      // Escape quotes by doubling them
      val escaped = field.replace(Quote, Quote + Quote)
      Quote + escaped + Quote
    } else {
      field
    }
  }
}
