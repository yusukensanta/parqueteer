package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.{
  OutputFormat,
  CompressionType
}

sealed trait Command

case class ReadCommand(
    filePath: String,
    maxRows: Option[Long] = None,
    columns: Option[List[String]] = None,
    filter: Option[String] = None,
    format: OutputFormat = OutputFormat.Table
) extends Command

case class InfoCommand(
    filePath: String,
    format: OutputFormat = OutputFormat.Table,
    showSchema: Boolean = true,
    showMetadata: Boolean = true
) extends Command

case class WriteCommand(
    outputPath: String,
    inputPath: String,
    inputFormat: String = "json",
    compression: CompressionType = CompressionType.Snappy,
    rowGroupSize: Option[Long] = None
) extends Command

case class ValidateCommand(
    filePath: String,
    verbose: Boolean = false
) extends Command

case class ConvertCommand(
    inputPath: String,
    outputPath: String,
    compression: CompressionType = CompressionType.Snappy,
    maxRows: Option[Long] = None
) extends Command

case class GlobalOptions(
    verbose: Boolean = false,
    configPath: Option[String] = None,
    profile: Option[String] = None,
    region: Option[String] = None,
    noColor: Boolean = false
)
