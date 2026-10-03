package io.github.yusukensanta.parqueteer.cli

import scopt.OParser
import io.github.yusukensanta.parqueteer.core.models.{
  ColorMode,
  CompressionType,
  GlobalOptions,
  InputFormat,
  OutputFormat,
  SchemaMode
}
import io.github.yusukensanta.parqueteer.config.EnvConfig
import io.github.yusukensanta.parqueteer.core.util.SizeParser

object ArgumentParser {

  case class Config(
      command: Option[Command] = None,
      globalOptions: GlobalOptions = GlobalOptions()
  )

  private val builder = OParser.builder[Config]

  val parser: OParser[Unit, Config] = {
    import builder.*

    // Shared option definitions: each enum-valued flag is parsed by a single
    // fromString (so validation and action can't disagree), and options that
    // recur across subcommands are defined once so their messages can't drift.
    def enumOpt[C <: Command: reflect.ClassTag, A](
        name: String,
        parse: String => Option[A],
        allowed: String
    )(set: (C, A) => C) =
      opt[String](name)
        .validate(x =>
          if parse(x).isDefined then success else failure(s"Invalid --$name: $x. Use $allowed")
        )
        .action((x, c) => updateCmd[C](c, cmd => parse(x).fold(cmd)(set(cmd, _))))

    def schemaModeOpt[C <: Command: reflect.ClassTag](set: (C, SchemaMode) => C) =
      enumOpt[C, SchemaMode]("schema-mode", SchemaMode.fromString, "strict or union")(set)

    def compressionOpt[C <: Command: reflect.ClassTag](set: (C, CompressionType) => C) =
      enumOpt[C, CompressionType](
        "compression",
        CompressionType.fromString,
        "snappy, gzip, zstd, lz4, brotli, or none"
      )(set)

    def tableOrJsonFormatOpt[C <: Command: reflect.ClassTag](set: (C, OutputFormat) => C) =
      enumOpt[C, OutputFormat]("format", parseTableOrJson, "table or json")(set)

    def limitOpt[C <: Command: reflect.ClassTag](set: (C, Long) => C) =
      opt[Long]("limit")
        .abbr("n")
        .validate(x =>
          if x > 0 then success
          else failure("--limit must be a positive integer")
        )
        .action((x, c) => updateCmd[C](c, set(_, x)))

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
      opt[Int]("file-parallelism")
        .action((x, c) => c.copy(globalOptions = c.globalOptions.copy(fileParallelism = x)))
        .validate(x =>
          if x >= 1 then success
          else failure("File parallelism must be at least 1")
        )
        .text(
          "Max cloud files fetched concurrently for multi-file/glob commands " +
            "(info, validate, stats, count, schema info, merge, convert) — bounds " +
            "concurrent connections and in-flight memory (default: 4)"
        ),
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
            .text(
              "Path to parquet file (local, s3://, gs://, abfss://) (supports glob patterns: *, ?, [], {})"
            ),
          limitOpt[ReadCommand]((cmd, n) => cmd.copy(maxRows = Some(n)))
            .text("Maximum number of rows to display"),
          opt[Seq[String]]("columns")
            .abbr("c")
            .action((x, c) => updateCmd[ReadCommand](c, _.copy(columns = Some(x.toList))))
            .text("Comma-separated list of columns to display"),
          opt[String]("filter")
            .abbr("f")
            .action((x, c) => updateCmd[ReadCommand](c, _.copy(filter = Some(x))))
            .text("Filter expression for rows"),
          enumOpt[ReadCommand, OutputFormat](
            "format",
            OutputFormat.fromString,
            "table, json, csv, pretty, markdown, ndjson, or ltsv"
          )((cmd, f) => cmd.copy(format = f))
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
            ),
          schemaModeOpt[ReadCommand]((cmd, m) => cmd.copy(schemaMode = m))
            .text(
              "Schema compatibility mode for multi-file glob reads: strict (default) or union"
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
                    format = defaultTableOrJsonFormat
                  )
                )
              )
            )
            .text("Path to parquet file (supports glob patterns: *, ?, [], {})"),
          tableOrJsonFormatOpt[InfoCommand]((cmd, f) => cmd.copy(format = f))
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
            .text("Input data file path (JSON or CSV) (supports glob patterns: *, ?, [], {})"),
          arg[String]("<output>")
            .required()
            .action((x, c) => updateCmd[WriteCommand](c, _.copy(outputPath = x)))
            .text("Output parquet file path"),
          enumOpt[WriteCommand, InputFormat](
            "input-format",
            InputFormat.fromString,
            "json, ndjson, csv, or ltsv"
          )((cmd, f) => cmd.copy(inputFormat = f))
            .text("Input file format: json, ndjson, csv, ltsv (default: json)"),
          compressionOpt[WriteCommand]((cmd, ct) => cmd.copy(compression = ct))
            .abbr("c")
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
            ),
          schemaModeOpt[WriteCommand]((cmd, m) => cmd.copy(schemaMode = m))
            .text(
              "Schema compatibility mode for multi-file glob reads: strict (default) or union"
            )
        ),
      cmd("validate")
        .text("Verify parquet file integrity")
        .children(
          arg[String]("<file>")
            .required()
            .action((x, c) => c.copy(command = Some(ValidateCommand(x))))
            .text("Path to parquet file (supports glob patterns: *, ?, [], {})"),
          opt[Unit]("verbose")
            .action((_, c) => updateCmd[ValidateCommand](c, _.copy(verbose = true)))
            .text("Show detailed validation information"),
          opt[Unit]("deep")
            .action((_, c) => updateCmd[ValidateCommand](c, _.copy(deep = true)))
            .text(
              "Fully decompress all row groups (default: spot-check first, last, midpoint)"
            ),
          opt[String]("expect-schema")
            .action((x, c) => updateCmd[ValidateCommand](c, _.copy(expectSchema = Some(x))))
            .valueName("<contract.json>")
            .text(
              "Also require the schema to match this contract (the output of `schema --format json`); exit 4 on mismatch"
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
            .text("Input file path (supports glob patterns: *, ?, [], {})"),
          arg[String]("<output>")
            .required()
            .action((x, c) => updateCmd[ConvertCommand](c, _.copy(outputPath = x)))
            .text("Output file path"),
          compressionOpt[ConvertCommand]((cmd, ct) => cmd.copy(compression = ct))
            .text("Compression type for output"),
          limitOpt[ConvertCommand]((cmd, n) => cmd.copy(maxRows = Some(n)))
            .text("Maximum number of rows to convert"),
          opt[Unit]("dry-run")
            .action((_, c) => updateCmd[ConvertCommand](c, _.copy(dryRun = true)))
            .text(
              "Preview what would be converted without performing the operation"
            ),
          schemaModeOpt[ConvertCommand]((cmd, m) => cmd.copy(schemaMode = m))
            .text(
              "Schema compatibility mode for multi-file glob reads: strict (default) or union"
            )
        ),
      cmd("schema")
        .text("Show column structure (names, types, nullability, compression)")
        .action((_, c) =>
          c.copy(command =
            Some(
              SchemaCommand(
                "",
                format = defaultTableOrJsonFormat
              )
            )
          )
        )
        .children(
          arg[String]("<file>")
            .optional()
            .action((x, c) => updateCmd[SchemaCommand](c, _.copy(filePath = x)))
            .text("Path to parquet file (supports glob patterns: *, ?, [], {})"),
          tableOrJsonFormatOpt[SchemaCommand]((cmd, f) => cmd.copy(format = f))
            .text("Output format: table, json (default: table)"),
          cmd("diff")
            .text("Compare schemas of two parquet files")
            .action((_, c) =>
              c.copy(command =
                Some(
                  SchemaDiffCommand(
                    "",
                    "",
                    format = defaultTableOrJsonFormat
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
              tableOrJsonFormatOpt[SchemaDiffCommand]((cmd, f) => cmd.copy(format = f))
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
          compressionOpt[MergeCommand]((cmd, ct) => cmd.copy(compression = ct))
            .abbr("c")
            .text("Output compression (default: snappy)"),
          schemaModeOpt[MergeCommand]((cmd, m) => cmd.copy(schemaMode = m))
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
                    format = defaultTableOrJsonFormat
                  )
                )
              )
            )
            .text("Path to parquet file (supports glob patterns: *, ?, [], {})"),
          tableOrJsonFormatOpt[StatsCommand]((cmd, f) => cmd.copy(format = f))
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
            .text("Path to parquet file (supports glob patterns: *, ?, [], {})"),
          tableOrJsonFormatOpt[CountCommand]((cmd, f) => cmd.copy(format = f))
            .text(
              "Output format: table (plain integer) or json (default: table)"
            )
        ),
      cmd("completions")
        .text("Generate shell completion scripts")
        .children(
          arg[String]("<shell>")
            .required()
            .action((x, c) =>
              Shell.fromString(x).fold(c)(sh => c.copy(command = Some(CompletionsCommand(sh))))
            )
            .validate(x =>
              if Shell.fromString(x).isDefined then success
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

  private def isTableOrJson(f: OutputFormat): Boolean =
    f == OutputFormat.Table || f == OutputFormat.JSON

  private def parseTableOrJson(s: String): Option[OutputFormat] =
    OutputFormat.fromString(s).filter(isTableOrJson)

  // PARQUETEER_DEFAULT_FORMAT, for commands that can only render table or json.
  private def defaultTableOrJsonFormat: OutputFormat =
    EnvConfig.parsedDefaultFormat.filter(isTableOrJson).getOrElse(OutputFormat.Table)

  private def parseSize(sizeStr: String): Long =
    SizeParser.parse(sizeStr)
}
