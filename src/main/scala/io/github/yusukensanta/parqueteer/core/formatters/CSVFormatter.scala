package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  FileContent,
  ParquetSchema,
  FileMetadata
}

object CSVFormatter {
  private[formatters] val Newline = "\r\n"

  private[formatters] def escapeField(field: String): String = {
    // CWE-1236: prefix formula-trigger chars. Also check first non-whitespace char
    // because spreadsheets trim leading spaces before formula evaluation.
    val firstSig = field.dropWhile(c => c <= ' ')
    val sanitized =
      if (
        firstSig.nonEmpty && (firstSig.charAt(0) match {
          case '=' | '+' | '-' | '@' | '\t' | '\r' => true
          case _                                   => false
        })
      ) "'" + field
      else field

    val needsQuoting = sanitized.contains(",") ||
      sanitized.contains("\"") ||
      sanitized.contains("\n") ||
      sanitized.contains("\r")

    if (needsQuoting) {
      "\"" + sanitized.replace("\"", "\"\"") + "\""
    } else {
      sanitized
    }
  }
}

class CSVFormatter extends OutputFormatter {

  private val Delimiter = ","
  private val Newline = CSVFormatter.Newline

  override def formatContent(
      content: FileContent,
      schema: Option[ParquetSchema]
  ): String = {
    if (content.rows.isEmpty) {
      return ""
    }

    val rows = content.rows
    val columns = extractColumns(rows, schema)

    val sb = new StringBuilder()

    sb.append(formatRow(columns))
    sb.append(Newline)

    rows.foreach { row =>
      val values = columns.map { col =>
        row.get(col) match {
          case None | Some(CellValue.Null) => ""
          case Some(v)                     => v.display
        }
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

    sb.append(formatRow(List("Property", "Value")))
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

  private def formatRow(values: List[String]): String = {
    values.map(CSVFormatter.escapeField).mkString(Delimiter)
  }
}
