package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  FileContent,
  ParquetSchema,
  FileMetadata
}

trait OutputFormatter {
  def formatContent(content: FileContent, schema: Option[ParquetSchema]): String
  def formatSchema(schema: ParquetSchema): String
  def formatMetadata(metadata: FileMetadata): String

  protected def extractColumns(rows: List[Map[String, Any]]): List[String] =
    rows.flatMap(_.keys).distinct.sorted
}

object OutputFormatter {
  import io.github.yusukensanta.parqueteer.core.models.OutputFormat

  def apply(format: OutputFormat): OutputFormatter = format match {
    case OutputFormat.Table    => new TableFormatter()
    case OutputFormat.JSON     => new JSONFormatter()
    case OutputFormat.CSV      => new CSVFormatter()
    case OutputFormat.Pretty   => new PrettyFormatter()
    case OutputFormat.Markdown => new MarkdownFormatter()
    case OutputFormat.NDJSON   => new NDJSONFormatter()
  }
}
