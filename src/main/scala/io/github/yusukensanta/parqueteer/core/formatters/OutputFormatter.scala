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

  private[formatters] def indexColumns(columns: List[String]): Map[String, Int] =
    columns.iterator.zipWithIndex.toMap

  // Projects a row onto a fixed column list in one pass instead of doing
  // columns.length separate row.get/getOrElse lookups (each O(row.size) on a
  // ListMap, so O(columns.length * row.size) total). Missing columns are left
  // as `null` (a real absence marker, distinct from a present CellValue.Null)
  // so each caller can pick its own missing-value rendering. Shared by every
  // batch OutputFormatter's formatContent and by RowStreamWriter's per-row
  // writers.
  private[formatters] def projectRow(
      row: Map[String, CellValue],
      nameToIndex: Map[String, Int],
      numCols: Int
  ): Array[CellValue] = {
    val values = new Array[CellValue](numCols)
    row.foreach { case (k, v) => nameToIndex.get(k).foreach(idx => values(idx) = v) }
    values
  }
}
