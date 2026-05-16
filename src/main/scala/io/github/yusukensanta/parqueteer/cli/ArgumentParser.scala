package io.github.yusukensanta.parqueteer.cli

import scopt.OParser
import io.github.yusukensanta.parqueteer.core.models.{
  OutputFormat,
  CompressionType
}
import io.github.yusukensanta.parqueteer.config.EnvConfig

object ArgumentParser {
  case class Config(
      command: Option[Command] = None,
      globalOptions: GlobalOptions = GlobalOptions()
  )

  private val builder = OParser.builder[Config]

  val parser: OParser[Unit, Config] = {
    import builder._

    OParser.sequence(
      programName("parqueteer"),
      head("parqueteer", io.github.yusukensanta.parqueteer.BuildInfo.version),
      help("help").abbr("h").text("Show help information"),
      version("version").abbr("V").text("Show version information"),
      opt[Unit]("verbose")
        .abbr("v")
        .action((_, c) =>
          c.copy(globalOptions = c.globalOptions.copy(verbose = true))
        )
        .text("Enable verbose output"),
      opt[Unit]("quiet")
        .abbr("q")
        .action((_, c) =>
          c.copy(globalOptions = c.globalOptions.copy(quiet = true))
        )
        .text("Suppress non-error output"),
      opt[String]("config")
        .action((x, c) =>
          c.copy(globalOptions = c.globalOptions.copy(configPath = Some(x)))
        )
        .text("Path to configuration file"),
      opt[String]("profile")
        .action((x, c) =>
          c.copy(globalOptions = c.globalOptions.copy(profile = Some(x)))
        )
        .text("Cloud credentials profile to use"),
      opt[String]("region")
        .action((x, c) =>
          c.copy(globalOptions = c.globalOptions.copy(region = Some(x)))
        )
        .text("Cloud region to use"),
      opt[String]("color")
        .action((x, c) =>
          c.copy(globalOptions =
            c.globalOptions.copy(colorMode = parseColorMode(x))
          )
        )
        .validate(x =>
          if (List("auto", "always", "never").contains(x.toLowerCase)) success
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
          opt[Long]("max-rows")
            .abbr("n")
            .action((x, c) => updateReadCommand(c, _.copy(maxRows = Some(x))))
            .text("Maximum number of rows to display"),
          opt[Seq[String]]("columns")
            .abbr("c")
            .action((x, c) =>
              updateReadCommand(c, _.copy(columns = Some(x.toList)))
            )
            .text("Comma-separated list of columns to display"),
          opt[String]("filter")
            .abbr("f")
            .action((x, c) => updateReadCommand(c, _.copy(filter = Some(x))))
            .text("Filter expression for rows"),
          opt[String]("format")
            .action((x, c) =>
              updateReadCommand(c, _.copy(format = parseOutputFormat(x)))
            )
            .validate(x =>
              if (
                List("table", "json", "csv", "pretty", "markdown", "ndjson")
                  .contains(x.toLowerCase)
              ) success
              else failure(s"Invalid format: $x")
            )
            .text(
              "Output format: table, json, csv, pretty, markdown, ndjson (default: table)"
            ),
          opt[Int]("parallel")
            .action((x, c) => updateReadCommand(c, _.copy(parallelism = x)))
            .validate(x =>
              if (x >= 1) success
              else failure("Parallelism must be at least 1")
            )
            .text(
              "Number of parallel threads for row group reading (default: 1)"
            ),
          opt[Unit]("stream")
            .action((_, c) => updateReadCommand(c, _.copy(streaming = true)))
            .text(
              "Stream rows progressively (memory-bounded, safe for large files)"
            )
        ),
      cmd("info")
        .text("Show file metadata and schema information")
        .children(
          arg[String]("<file>")
            .required()
            .action((x, c) => c.copy(command = Some(InfoCommand(x))))
            .text("Path to parquet file"),
          opt[String]("format")
            .action((x, c) =>
              updateInfoCommand(c, _.copy(format = parseOutputFormat(x)))
            )
            .text("Output format: table, json"),
          opt[Unit]("schema")
            .abbr("s")
            .action((_, c) => updateInfoCommand(c, _.copy(showSchema = true)))
            .text("Show schema information (default: show all)"),
          opt[Unit]("metadata")
            .abbr("m")
            .action((_, c) => updateInfoCommand(c, _.copy(showMetadata = true)))
            .text("Show metadata information (default: show all)")
        ),
      cmd("write")
        .text("Create parquet file from input data")
        .children(
          arg[String]("<input>")
            .required()
            .action((x, c) => c.copy(command = Some(WriteCommand("", x))))
            .text("Input data file path (JSON or CSV)"),
          arg[String]("<output>")
            .required()
            .action((x, c) => updateWriteCommand(c, _.copy(outputPath = x)))
            .text("Output parquet file path"),
          opt[String]("input-format")
            .action((x, c) => updateWriteCommand(c, _.copy(inputFormat = x)))
            .validate(x =>
              if (List("json", "csv").contains(x.toLowerCase)) success
              else failure(s"Invalid input format: $x")
            )
            .text("Input file format: json, csv (default: json)"),
          opt[String]("compression")
            .abbr("c")
            .action((x, c) =>
              updateWriteCommand(
                c,
                _.copy(compression = parseCompressionType(x))
              )
            )
            .text(
              "Compression type: none, snappy, gzip, lzo, brotli, lz4, zstd"
            ),
          opt[String]("row-group-size")
            .action((x, c) =>
              updateWriteCommand(c, _.copy(rowGroupSize = Some(parseSize(x))))
            )
            .text("Row group size (e.g., 128MB)"),
          opt[Unit]("dry-run")
            .action((_, c) => updateWriteCommand(c, _.copy(dryRun = true)))
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
            .action((_, c) => updateValidateCommand(c, _.copy(verbose = true)))
            .text("Show detailed validation information")
        ),
      cmd("convert")
        .text("Convert between parquet and other formats")
        .children(
          arg[String]("<input>")
            .required()
            .action((x, c) => c.copy(command = Some(ConvertCommand(x, ""))))
            .text("Input file path"),
          arg[String]("<output>")
            .required()
            .action((x, c) => updateConvertCommand(c, _.copy(outputPath = x)))
            .text("Output file path"),
          opt[String]("compression")
            .action((x, c) =>
              updateConvertCommand(
                c,
                _.copy(compression = parseCompressionType(x))
              )
            )
            .text("Compression type for output"),
          opt[Long]("max-rows")
            .abbr("n")
            .action((x, c) =>
              updateConvertCommand(c, _.copy(maxRows = Some(x)))
            )
            .text("Maximum number of rows to convert"),
          opt[Unit]("dry-run")
            .action((_, c) => updateConvertCommand(c, _.copy(dryRun = true)))
            .text(
              "Preview what would be converted without performing the operation"
            )
        ),
      cmd("schema")
        .text("Schema inspection commands")
        .children(
          cmd("diff")
            .text("Compare schemas of two parquet files")
            .action((_, c) =>
              c.copy(command =
                Some(SchemaCommand(SchemaDiffSubcommand("", "")))
              )
            )
            .children(
              arg[String]("<file1>")
                .required()
                .action((x, c) => updateSchemaDiffCommand(c, _.copy(file1 = x)))
                .text("First parquet file path"),
              arg[String]("<file2>")
                .required()
                .action((x, c) => updateSchemaDiffCommand(c, _.copy(file2 = x)))
                .text("Second parquet file path"),
              opt[String]("format")
                .action((x, c) =>
                  updateSchemaDiffCommand(
                    c,
                    _.copy(format = parseOutputFormat(x))
                  )
                )
                .validate(x =>
                  if (List("table", "json").contains(x.toLowerCase)) success
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
              updateMergeCommand(c, m => m.copy(inputPaths = m.inputPaths :+ x))
            )
            .text("Input parquet files (specify two or more)"),
          opt[String]("output")
            .abbr("o")
            .required()
            .action((x, c) => updateMergeCommand(c, _.copy(outputPath = x)))
            .text("Output parquet file path"),
          opt[String]("compression")
            .abbr("c")
            .action((x, c) =>
              updateMergeCommand(
                c,
                _.copy(compression = parseCompressionType(x))
              )
            )
            .validate(x =>
              if (
                List("none", "snappy", "gzip", "lzo", "brotli", "lz4", "zstd")
                  .contains(x.toLowerCase)
              ) success
              else failure(s"Unknown compression: $x")
            )
            .text("Output compression (default: snappy)"),
          opt[String]("schema-mode")
            .action((x, c) =>
              updateMergeCommand(
                c,
                _.copy(schemaMode = x.toLowerCase match {
                  case "union" => SchemaMode.Union
                  case _       => SchemaMode.Strict
                })
              )
            )
            .validate(x =>
              if (List("strict", "union").contains(x.toLowerCase)) success
              else failure(s"Unknown schema-mode: $x. Use strict or union")
            )
            .text("Schema compatibility mode: strict (default) or union")
        ),
      cmd("completions")
        .text("Generate shell completion scripts")
        .children(
          arg[String]("<shell>")
            .required()
            .action((x, c) => c.copy(command = Some(CompletionsCommand(x))))
            .validate(x =>
              if (List("bash", "zsh", "fish").contains(x.toLowerCase)) success
              else failure(s"Unsupported shell: $x. Use bash, zsh, or fish")
            )
            .text("Shell type: bash, zsh, fish")
        ),
      cmd("config")
        .text("Show or validate configuration")
        .children(
          cmd("show")
            .text("Display resolved configuration with source annotations")
            .action((_, c) =>
              c.copy(command = Some(ConfigCommand(ConfigShowSubcommand)))
            ),
          cmd("validate")
            .text("Validate the configuration file")
            .action((_, c) =>
              c.copy(command = Some(ConfigCommand(ConfigValidateSubcommand)))
            )
        )
    )
  }

  private def updateReadCommand(
      config: Config,
      update: ReadCommand => ReadCommand
  ): Config = {
    config.command match {
      case Some(cmd: ReadCommand) => config.copy(command = Some(update(cmd)))
      case _                      => config
    }
  }

  private def updateInfoCommand(
      config: Config,
      update: InfoCommand => InfoCommand
  ): Config = {
    config.command match {
      case Some(cmd: InfoCommand) => config.copy(command = Some(update(cmd)))
      case _                      => config
    }
  }

  private def updateWriteCommand(
      config: Config,
      update: WriteCommand => WriteCommand
  ): Config = {
    config.command match {
      case Some(cmd: WriteCommand) => config.copy(command = Some(update(cmd)))
      case _                       => config
    }
  }

  private def updateValidateCommand(
      config: Config,
      update: ValidateCommand => ValidateCommand
  ): Config = {
    config.command match {
      case Some(cmd: ValidateCommand) =>
        config.copy(command = Some(update(cmd)))
      case _ => config
    }
  }

  private def updateConvertCommand(
      config: Config,
      update: ConvertCommand => ConvertCommand
  ): Config = {
    config.command match {
      case Some(cmd: ConvertCommand) => config.copy(command = Some(update(cmd)))
      case _                         => config
    }
  }

  private def updateSchemaDiffCommand(
      config: Config,
      update: SchemaDiffSubcommand => SchemaDiffSubcommand
  ): Config = {
    config.command match {
      case Some(SchemaCommand(sub)) =>
        config.copy(command = Some(SchemaCommand(update(sub))))
      case _ => config
    }
  }

  private def updateMergeCommand(
      config: Config,
      update: MergeCommand => MergeCommand
  ): Config = {
    config.command match {
      case Some(cmd: MergeCommand) => config.copy(command = Some(update(cmd)))
      case _                       => config
    }
  }

  private def parseOutputFormat(format: String): OutputFormat = {
    format.toLowerCase match {
      case "table"    => OutputFormat.Table
      case "json"     => OutputFormat.JSON
      case "csv"      => OutputFormat.CSV
      case "pretty"   => OutputFormat.Pretty
      case "markdown" => OutputFormat.Markdown
      case "ndjson"   => OutputFormat.NDJSON
      case _          => OutputFormat.Table
    }
  }

  private def parseColorMode(mode: String): ColorMode = {
    mode.toLowerCase match {
      case "always" => ColorMode.Always
      case "never"  => ColorMode.Never
      case _        => ColorMode.Auto
    }
  }

  private def parseCompressionType(compression: String): CompressionType = {
    compression.toLowerCase match {
      case "none" | "uncompressed" => CompressionType.Uncompressed
      case "snappy"                => CompressionType.Snappy
      case "gzip" | "gz"           => CompressionType.Gzip
      case "lzo"                   => CompressionType.Lzo
      case "brotli"                => CompressionType.Brotli
      case "lz4"                   => CompressionType.Lz4
      case "zstd"                  => CompressionType.Zstd
      case _                       => CompressionType.Snappy
    }
  }

  private def parseSize(sizeStr: String): Long =
    io.github.yusukensanta.parqueteer.core.util.SizeParser.parse(sizeStr)
}
