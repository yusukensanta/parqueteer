package com.github.yusukensanta.parqueteer.core.formatters

import com.github.yusukensanta.parqueteer.core.models.{
  FileContent,
  ParquetSchema,
  FileMetadata
}

trait OutputFormatter {
  def formatContent(content: FileContent, schema: Option[ParquetSchema]): String
  def formatSchema(schema: ParquetSchema): String
  def formatMetadata(metadata: FileMetadata): String
}

object OutputFormatter {
  import com.github.yusukensanta.parqueteer.core.models.OutputFormat

  def apply(format: OutputFormat): OutputFormatter = format match {
    case OutputFormat.Table  => new TableFormatter()
    case OutputFormat.JSON   => new JSONFormatter()
    case OutputFormat.CSV    => new CSVFormatter()
    case OutputFormat.Pretty => new PrettyFormatter()
  }
}
