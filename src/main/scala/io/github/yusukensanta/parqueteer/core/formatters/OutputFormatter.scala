package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  FileContent,
  FileMetadata,
  ParquetSchema
}

trait OutputFormatter {
  def formatContent(content: FileContent, schema: Option[ParquetSchema]): String
  def formatSchema(schema: ParquetSchema): String
  def formatMetadata(metadata: FileMetadata): String

  protected def extractColumns(
      rows: List[Map[String, CellValue]],
      schema: Option[ParquetSchema] = None
  ): List[String] = {
    val seen = {
      val s = scala.collection.mutable.LinkedHashSet.empty[String]
      rows.foreach(_.keysIterator.foreach(s += _))
      s
    }
    schema.map(_.columns.map(_.name).filter(seen)).getOrElse(seen.toList)
  }
}

object OutputFormatter {
  import io.github.yusukensanta.parqueteer.core.models.OutputFormat

  def apply(format: OutputFormat, useColors: Boolean = true): OutputFormatter =
    format match {
      case OutputFormat.Table    => new TableFormatter()
      case OutputFormat.JSON     => new JSONFormatter()
      case OutputFormat.CSV      => new CSVFormatter()
      case OutputFormat.Pretty   => new PrettyFormatter(useColors)
      case OutputFormat.Markdown => new MarkdownFormatter()
      case OutputFormat.NDJSON   => new NDJSONFormatter()
      case OutputFormat.LTSV     => new LTSVFormatter()
    }
}
