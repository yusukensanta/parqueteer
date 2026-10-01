package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.{
  CompressionType,
  InputFormat,
  OutputFormat,
  SchemaMode
}

sealed trait Command

case class ReadCommand(
    filePath: String,
    maxRows: Option[Long] = None,
    columns: Option[List[String]] = None,
    filter: Option[String] = None,
    format: OutputFormat = OutputFormat.Table,
    parallelism: Int = 1,
    streaming: Boolean = false,
    schemaMode: SchemaMode = SchemaMode.Strict
) extends Command

case class InfoCommand(
    filePath: String,
    format: OutputFormat = OutputFormat.Table,
    verbose: Boolean = false
) extends Command

case class WriteCommand(
    inputPath: String,
    outputPath: String,
    inputFormat: InputFormat = InputFormat.Json,
    compression: CompressionType = CompressionType.Snappy,
    rowGroupSize: Option[Long] = None,
    dryRun: Boolean = false,
    schemaMode: SchemaMode = SchemaMode.Strict
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
    dryRun: Boolean = false,
    schemaMode: SchemaMode = SchemaMode.Strict
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
    schemaMode: SchemaMode = SchemaMode.Strict,
    dryRun: Boolean = false
) extends Command

case class StatsCommand(
    filePath: String,
    format: OutputFormat = OutputFormat.Table
) extends Command

case class CountCommand(
    filePath: String,
    format: OutputFormat = OutputFormat.Table
) extends Command

/** Shells `parqueteer completions` can generate a script for. */
enum Shell:
  case Bash, Zsh, Fish

object Shell:

  def fromString(s: String): Option[Shell] =
    values.find(_.toString.equalsIgnoreCase(s))

case class CompletionsCommand(shell: Shell) extends Command
