package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.{
  OutputFormat,
  CompressionType,
  SchemaMode
}

enum InputFormat:
  case Json, NDJson, Csv, Ltsv

object InputFormat:
  def fromString(s: String): Option[InputFormat] = s.toLowerCase match
    case "json"   => Some(Json)
    case "ndjson" => Some(NDJson)
    case "csv"    => Some(Csv)
    case "ltsv"   => Some(Ltsv)
    case _        => None

  def toServiceString(f: InputFormat): String = f match
    case Json   => "json"
    case NDJson => "ndjson"
    case Csv    => "csv"
    case Ltsv   => "ltsv"

sealed trait Command

case class ReadCommand(
    filePath: String,
    maxRows: Option[Long] = None,
    columns: Option[List[String]] = None,
    filter: Option[String] = None,
    format: OutputFormat = OutputFormat.Table,
    parallelism: Int = 1,
    streaming: Boolean = false
) extends Command

case class InfoCommand(
    filePath: String,
    format: OutputFormat = OutputFormat.Table
) extends Command

case class WriteCommand(
    inputPath: String,
    outputPath: String,
    inputFormat: InputFormat = InputFormat.Json,
    compression: CompressionType = CompressionType.Snappy,
    rowGroupSize: Option[Long] = None,
    dryRun: Boolean = false
) extends Command

case class ValidateCommand(
    filePath: String,
    verbose: Boolean = false,
    deep: Boolean = false
) extends Command

case class ConvertCommand(
    inputPath: String,
    outputPath: String,
    compression: CompressionType = CompressionType.Snappy,
    maxRows: Option[Long] = None,
    dryRun: Boolean = false
) extends Command

case class ConfigCommand(validate: Boolean = false) extends Command

case class SchemaCommand(
    filePath: String,
    format: OutputFormat = OutputFormat.Table
) extends Command

case class SchemaDiffCommand(
    file1: String,
    file2: String,
    format: OutputFormat = OutputFormat.Table
) extends Command

case class MergeCommand(
    inputPaths: List[String] = List.empty,
    outputPath: String = "",
    compression: CompressionType = CompressionType.Snappy,
    schemaMode: SchemaMode = SchemaMode.Strict
) extends Command

case class StatsCommand(
    filePath: String,
    format: OutputFormat = OutputFormat.Table
) extends Command

case class CountCommand(
    filePath: String,
    format: OutputFormat = OutputFormat.Table
) extends Command

case class CompletionsCommand(shell: String) extends Command

enum ColorMode:
  case Auto, Always, Never

object ColorMode:
  def fromString(s: String): Option[ColorMode] = s.toLowerCase match
    case "always" => Some(Always)
    case "never"  => Some(Never)
    case "auto"   => Some(Auto)
    case _        => None

case class GlobalOptions(
    verbose: Boolean = false,
    quiet: Boolean = false,
    configPath: Option[String] = None,
    profile: Option[String] = None,
    region: Option[String] = None,
    colorMode: ColorMode = ColorMode.Auto
)
