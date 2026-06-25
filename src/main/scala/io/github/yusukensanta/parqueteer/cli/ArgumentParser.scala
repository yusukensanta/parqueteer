package io.github.yusukensanta.parqueteer.cli

import scopt.OParser
import io.github.yusukensanta.parqueteer.core.models.{
  ColorMode,
  CompressionType,
  GlobalOptions,
  OutputFormat,
  SchemaMode
}
import io.github.yusukensanta.parqueteer.config.EnvConfig

object ArgumentParser {

  case class Config(
      command: Option[Command] = None,
      globalOptions: GlobalOptions = GlobalOptions()
  )

  private val builder = OParser.builder[Config]

  private val validCompressions =
    List("none", "snappy", "gzip", "lzo", "brotli", "lz4", "zstd")

  val parser: OParser[Unit, Config] = {
    import builder.*

    OParser.sequence(
      programName("parqueteer"),
      head("parqueteer", io.github.yusukensanta.parqueteer.BuildInfo.version),
      help("help").abbr("h").text("Show help information"),
      version("version").abbr("V").text("Show version information"),
      opt[Unit]("verbose")
        .abbr("v")
        .action((_, c) => c.copy(globalOptions = c.globalOptions.copy(verbose = true)))
        .text(
          "Enable verbose output (caution: may include sensitive metadata from cloud error messages)"
        ),
      opt[Unit]("quiet")
        .abbr("q")
        .action((_, c) => c.copy(globalOptions = c.globalOptions.copy(quiet = true)))
        .text("Suppress non-error output"),
      opt[String]("config")
        .action((x, c) => c.copy(globalOptions = c.globalOptions.copy(configPath = Some(x))))
        .text("Path to configuration file"),
      opt[String]("profile")
        .action((x, c) => c.copy(globalOptions = c.globalOptions.copy(profile = Some(x))))
        .text("AWS S3 credentials profile (from ~/.aws/credentials)"),
      opt[String]("region")
        .action((x, c) => c.copy(globalOptions = c.globalOptions.copy(region = Some(x))))
        .text("AWS S3 region (e.g. us-east-1, ap-northeast-1)"),
      opt[String]("color")
        .action((x, c) =>
          c.copy(globalOptions =
            c.globalOptions.copy(colorMode = ColorMode.fromString(x).getOrElse(ColorMode.Auto))
          )
        )
        .validate(x =>
          if List("auto", "always", "never").contains(x.toLowerCase) then success
          else failure(s"Invalid color mode: $x. Use auto, always, or never")
        )
        .text("Color output mode: auto, always, never (default: auto)"),
      cmd("read")
        .text("Display parquet file content")
        .children(
          arg[String]("<file>")
            .required()
            .action((x, c) =>
              c.copy(command =
                Some(
                  ReadCommand(
                    x,
                    maxRows = EnvConfig.parsedMaxRows,
                    format = EnvConfig.parsedDefaultFormat
                      .getOrElse(OutputFormat.Table)
                  )
                )
              )
            )
            .text("Path to parquet file (local, s3://, gs://, abfss://)"),
          opt[Long]("limit")
            .abbr("n")
            .validate(x =>
              if x > 0 then success
              else failure("--limit must be a positive integer")
            )
            .action((x, c) => updateCmd[ReadCommand](c, _.copy(maxRows = Some(x))))
            .text("Maximum number of rows to display"),
          opt[Seq[String]]("columns")
            .abbr("c")
            .action((x, c) => updateCmd[ReadCommand](c, _.copy(columns = Some(x.toList))))
            .text("Comma-separated list of columns to display"),
          opt[String]("filter")
            .abbr("f")
            .action((x, c) => updateCmd[ReadCommand](c, _.copy(filter = Some(x))))
            .text("Filter expression for rows"),
          opt[String]("format")
            .action((x, c) => updateCmd[ReadCommand](c, _.copy(format = parseOutputFormat(x))))
            .validate(x =>
              if List(
                  "table",
                  "json",
                  "csv",
                  "pretty",
                  "markdown",
                  "ndjson",
                  "ltsv"
                )
                  .contains(x.toLowerCase)
              then success
              else failure(s"Invalid format: $x")
            )
            .text(
              "Output format: table, json, csv, pretty, markdown, ndjson, ltsv (default: table)"
            ),
          opt[Int]("parallel")
            .action((x, c) => updateCmd[ReadCommand](c, _.copy(parallelism = x)))
            .validate(x =>
              if x >= 1 then success
              else failure("Parallelism must be at least 1")
            )
            .text(
              "Number of parallel threads for row group reading (default: 1)"
            ),
          opt[Unit]("stream")
            .action((_, c) => updateCmd[ReadCommand](c, _.copy(streaming = true)))
            .text(
              "Stream rows progressively (memory-bounded, safe for large files)"
            )
        ),
      cmd("info")
        .text(
          "Show file metadata (size, dates, writer version, compression ratio)"
        )
        .children(
          arg[String]("<file>")
            .required()
            .action((x, c) =>
              c.copy(command =
                Some(
                  InfoCommand(
                    x,
                    format =
                      if EnvConfig.parsedDefaultFormat
                          .contains(OutputFormat.JSON)
                      then OutputFormat.JSON
                      else OutputFormat.Table
                  )
                )
              )
            )
            .text("Path to parquet file"),
          opt[String]("format")
            .action((x, c) => updateCmd[InfoCommand](c, _.copy(format = parseOutputFormat(x))))
            .validate(x =>
              if List("table", "json").contains(x.toLowerCase) then success
              else failure(s"Invalid format: $x. Use table or json")
            )
            .text("Output format: table, json (default: table)"),
          opt[Unit]("verbose")
            .action((_, c) => updateCmd[InfoCommand](c, _.copy(verbose = true)))
            .text(
              "Show per-row-group breakdown (index, rows, compressed/uncompressed bytes)"
            )
        ),
      cmd("write")
        .text("Create parquet file from input data")
        .children(
          arg[String]("<input>")
            .required()
            .action((x, c) => c.copy(command = Some(WriteCommand(x, ""))))
            .text("Input data file path (JSON or CSV)"),
          arg[String]("<output>")
            .required()
            .action((x, c) => updateCmd[WriteCommand](c, _.copy(outputPath = x)))
            .text("Output parquet file path"),
          opt[String]("input-format")
            .action((x, c) =>
              updateCmd[WriteCommand](
                c,
                _.copy(
                  inputFormat = InputFormat.fromString(x).getOrElse(InputFormat.Json)
                )
              )
            )
            .validate(x =>
              if List("json", "ndjson", "csv", "ltsv").contains(x.toLowerCase) then success
              else failure(s"Invalid input format: $x")
            )
            .text("Input file format: json, ndjson, csv, ltsv (default: json)"),
          opt[String]("compression")
            .abbr("c")
            .action((x, c) =>
              updateCmd[WriteCommand](
                c,
                _.copy(compression = parseCompressionType(x))
              )
            )
            .validate(x =>
              if validCompressions.contains(x.toLowerCase) then success
              else failure(s"Unknown compression: $x")
            )
            .text(
              "Compression type: none, snappy, gzip, lzo, brotli, lz4, zstd"
            ),
          opt[String]("row-group-size")
            .validate(x =>
              scala.util
                .Try(parseSize(x))
                .fold(e => failure(e.getMessage), _ => success)
            )
            .action((x, c) =>
              updateCmd[WriteCommand](
                c,
                _.copy(rowGroupSize = Some(parseSize(x)))
              )
            )
            .text("Row group size (e.g., 128MB, 1.5GB)"),
          opt[Unit]("dry-run")
            .action((_, c) => updateCmd[WriteCommand](c, _.copy(dryRun = true)))
            .text(
              "Preview what would be written without performing the operation"
            )
        ),
      cmd("validate")
        .text("Verify parquet file integrity")
        .children(
          arg[String]("<file>")
            .required()
            .action((x, c) => c.copy(command = Some(ValidateCommand(x))))
            .text("Path to parquet file"),
          opt[Unit]("verbose")
            .action((_, c) => updateCmd[ValidateCommand](c, _.copy(verbose = true)))
            .text("Show detailed validation information"),
          opt[Unit]("deep")
            .action((_, c) => updateCmd[ValidateCommand](c, _.copy(deep = true)))
            .text(
              "Fully decompress all row groups (default: spot-check first, last, midpoint)"
            )
        ),
      cmd("convert")
        .text("Convert between parquet and other formats")
        .children(
          arg[String]("<input>")
            .required()
            .action((x, c) =>
              c.copy(command = Some(ConvertCommand(x, "", maxRows = EnvConfig.parsedMaxRows)))
            )
            .text("Input file path"),
          arg[String]("<output>")
            .required()
            .action((x, c) => updateCmd[ConvertCommand](c, _.copy(outputPath = x)))
            .text("Output file path"),
          opt[String]("compression")
            .action((x, c) =>
              updateCmd[ConvertCommand](
                c,
                _.copy(compression = parseCompressionType(x))
              )
            )
            .text("Compression type for output"),
          opt[Long]("limit")
            .abbr("n")
            .validate(x =>
              if x > 0 then success
              else failure("--limit must be a positive integer")
            )
            .action((x, c) => updateCmd[ConvertCommand](c, _.copy(maxRows = Some(x))))
            .text("Maximum number of rows to convert"),
          opt[Unit]("dry-run")
            .action((_, c) => updateCmd[ConvertCommand](c, _.copy(dryRun = true)))
            .text(
              "Preview what would be converted without performing the operation"
            )
        ),
      cmd("schema")
        .text("Show column structure (names, types, nullability, compression)")
        .action((_, c) =>
          c.copy(command =
            Some(
              SchemaCommand(
                "",
                format = EnvConfig.parsedDefaultFormat
                  .collect { case f @ (OutputFormat.JSON | OutputFormat.Table) =>
                    f
                  }
                  .getOrElse(OutputFormat.Table)
              )
            )
          )
        )
        .children(
          arg[String]("<file>")
            .optional()
            .action((x, c) => updateCmd[SchemaCommand](c, _.copy(filePath = x)))
            .text("Path to parquet file"),
          opt[String]("format")
            .action((x, c) => updateCmd[SchemaCommand](c, _.copy(format = parseOutputFormat(x))))
            .validate(x =>
              if List("table", "json").contains(x.toLowerCase) then success
              else failure(s"Invalid format: $x. Use table or json")
            )
            .text("Output format: table, json (default: table)"),
          cmd("diff")
            .text("Compare schemas of two parquet files")
            .action((_, c) =>
              c.copy(command =
                Some(
                  SchemaDiffCommand(
                    "",
                    "",
                    format =
                      if EnvConfig.parsedDefaultFormat
                          .contains(OutputFormat.JSON)
                      then OutputFormat.JSON
                      else OutputFormat.Table
                  )
                )
              )
            )
            .children(
              arg[String]("<file1>")
                .required()
                .action((x, c) => updateCmd[SchemaDiffCommand](c, _.copy(file1 = x)))
                .text("First parquet file path"),
              arg[String]("<file2>")
                .required()
                .action((x, c) => updateCmd[SchemaDiffCommand](c, _.copy(file2 = x)))
                .text("Second parquet file path"),
              opt[String]("format")
                .action((x, c) =>
                  updateCmd[SchemaDiffCommand](
                    c,
                    _.copy(format = parseOutputFormat(x))
                  )
                )
                .validate(x =>
                  if List("table", "json").contains(x.toLowerCase) then success
                  else failure(s"Invalid format: $x. Use table or json")
                )
                .text("Output format: table, json (default: table)")
            )
        ),
      cmd("merge")
        .text("Merge multiple parquet files into one")
        .action((_, c) => c.copy(command = Some(MergeCommand())))
        .children(
          arg[String]("<input>")
            .unbounded()
            .required()
            .action((x, c) =>
              updateCmd[MergeCommand](
                c,
                m => m.copy(inputPaths = m.inputPaths :+ x)
              )
            )
            .text("Input parquet files (specify two or more)"),
          opt[String]("output")
            .abbr("o")
            .required()
            .action((x, c) => updateCmd[MergeCommand](c, _.copy(outputPath = x)))
            .text("Output parquet file path"),
          opt[String]("compression")
            .abbr("c")
            .action((x, c) =>
              updateCmd[MergeCommand](
                c,
                _.copy(compression = parseCompressionType(x))
              )
            )
            .validate(x =>
              if validCompressions.contains(x.toLowerCase) then success
              else failure(s"Unknown compression: $x")
            )
            .text("Output compression (default: snappy)"),
          opt[String]("schema-mode")
            .action((x, c) =>
              updateCmd[MergeCommand](
                c,
                _.copy(schemaMode = x.toLowerCase match {
                  case "union"  => SchemaMode.Union
                  case "strict" => SchemaMode.Strict
                  case other =>
                    throw new IllegalArgumentException(
                      s"Unknown schema-mode: $other"
                    )
                })
              )
            )
            .validate(x =>
              if List("strict", "union").contains(x.toLowerCase) then success
              else failure(s"Unknown schema-mode: $x. Use strict or union")
            )
            .text("Schema compatibility mode: strict (default) or union"),
          opt[Unit]("dry-run")
            .action((_, c) => updateCmd[MergeCommand](c, _.copy(dryRun = true)))
            .text("Show what would be merged without writing output")
        ),
      cmd("stats")
        .text(
          "Show column statistics (min, max, null count) from row group metadata"
        )
        .children(
          arg[String]("<file>")
            .required()
            .action((x, c) =>
              c.copy(command =
                Some(
                  StatsCommand(
                    x,
                    format =
                      if EnvConfig.parsedDefaultFormat
                          .contains(OutputFormat.JSON)
                      then OutputFormat.JSON
                      else OutputFormat.Table
                  )
                )
              )
            )
            .text("Path to parquet file"),
          opt[String]("format")
            .action((x, c) => updateCmd[StatsCommand](c, _.copy(format = parseOutputFormat(x))))
            .validate(x =>
              if List("table", "json").contains(x.toLowerCase) then success
              else failure(s"Invalid format: $x. Use table or json")
            )
            .text("Output format: table, json (default: table)")
        ),
      cmd("count")
        .text(
          "Print the total row count from footer metadata (no data scan)"
        )
        .children(
          arg[String]("<file>")
            .required()
            .action((x, c) => c.copy(command = Some(CountCommand(x))))
            .text("Path to parquet file"),
          opt[String]("format")
            .action((x, c) => updateCmd[CountCommand](c, _.copy(format = parseOutputFormat(x))))
            .validate(x =>
              if List("table", "json").contains(x.toLowerCase) then success
              else failure(s"Invalid format: $x. Use table or json")
            )
            .text(
              "Output format: table (plain integer) or json (default: table)"
            )
        ),
      cmd("completions")
        .text("Generate shell completion scripts")
        .children(
          arg[String]("<shell>")
            .required()
            .action((x, c) => c.copy(command = Some(CompletionsCommand(x))))
            .validate(x =>
              if List("bash", "zsh", "fish").contains(x.toLowerCase) then success
              else failure(s"Unsupported shell: $x. Use bash, zsh, or fish")
            )
            .text("Shell type: bash, zsh, fish")
        ),
      cmd("config")
        .text("Show or validate configuration")
        .action((_, c) => c.copy(command = Some(ConfigCommand())))
        .children(
          opt[Unit]("validate")
            .action((_, c) => updateCmd[ConfigCommand](c, _.copy(validate = true)))
            .text("Validate the configuration file instead of displaying it")
        )
    )
  }

  private def updateCmd[C <: Command: reflect.ClassTag](
      config: Config,
      update: C => C
  ): Config =
    config.command match {
      case Some(cmd: C) => config.copy(command = Some(update(cmd)))
      case _            => config
    }

  private def parseOutputFormat(format: String): OutputFormat =
    format.toLowerCase match {
      case "table"    => OutputFormat.Table
      case "json"     => OutputFormat.JSON
      case "csv"      => OutputFormat.CSV
      case "pretty"   => OutputFormat.Pretty
      case "markdown" => OutputFormat.Markdown
      case "ndjson"   => OutputFormat.NDJSON
      case "ltsv"     => OutputFormat.LTSV
      case other =>
        throw new IllegalArgumentException(s"Unknown format: $other")
    }

  private def parseCompressionType(compression: String): CompressionType =
    compression.toLowerCase match {
      case "none" | "uncompressed" => CompressionType.Uncompressed
      case "snappy"                => CompressionType.Snappy
      case "gzip" | "gz"           => CompressionType.Gzip
      case "lzo"                   => CompressionType.Lzo
      case "brotli"                => CompressionType.Brotli
      case "lz4"                   => CompressionType.Lz4
      case "zstd"                  => CompressionType.Zstd
      case other =>
        throw new IllegalArgumentException(s"Unknown compression: $other")
    }

  private def parseSize(sizeStr: String): Long =
    io.github.yusukensanta.parqueteer.core.util.SizeParser.parse(sizeStr)
}
