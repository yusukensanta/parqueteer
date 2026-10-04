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
import io.github.yusukensanta.parqueteer.core.services.StatsAssertion
import io.github.yusukensanta.parqueteer.core.util.SizeParser
import CliSpec.Choices

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
        choices: List[String],
        valueName: String
    )(set: (C, A) => C) =
      opt[String](name)
        .valueName(valueName)
        .validate(x =>
          if parse(x).isDefined then success
          else failure(s"Invalid --$name: $x. Use ${orList(choices)}")
        )
        .action((x, c) => updateCmd[C](c, cmd => parse(x).fold(cmd)(set(cmd, _))))

    def schemaModeOpt[C <: Command: reflect.ClassTag](set: (C, SchemaMode) => C) =
      enumOpt[C, SchemaMode]("schema-mode", SchemaMode.fromString, Choices.schemaModes, "<mode>")(
        set
      )

    def compressionOpt[C <: Command: reflect.ClassTag](set: (C, CompressionType) => C) =
      enumOpt[C, CompressionType](
        "compression",
        CompressionType.fromString,
        Choices.compressions,
        "<type>"
      )(set)

    def tableOrJsonFormatOpt[C <: Command: reflect.ClassTag](set: (C, OutputFormat) => C) =
      enumOpt[C, OutputFormat]("format", parseTableOrJson, Choices.tableOrJson, "<fmt>")(set)

    // --page-size / --no-dictionary for every command that writes parquet.
    def pageSizeOpt[C <: Command: reflect.ClassTag](
        get: C => WriterOptions,
        set: (C, WriterOptions) => C
    ) =
      opt[String]("page-size")
        .valueName("<size>")
        .validate(x =>
          scala.util.Try(parseSize(x)).toEither match {
            case Left(e) => failure(e.getMessage)
            case Right(n) if n <= 0 || n > Int.MaxValue =>
              failure(s"--page-size must be between 1B and 2GB, got $x")
            case Right(_) => success
          }
        )
        .action((x, c) =>
          updateCmd[C](c, cmd => set(cmd, get(cmd).copy(pageSize = Some(parseSize(x).toInt))))
        )
        .text("Target data page size (e.g. 64KB, 1MB; default: 1MB)")

    def noDictionaryOpt[C <: Command: reflect.ClassTag](
        get: C => WriterOptions,
        set: (C, WriterOptions) => C
    ) =
      opt[Unit]("no-dictionary")
        .action((_, c) => updateCmd[C](c, cmd => set(cmd, get(cmd).copy(dictionary = false))))
        .text("Disable dictionary encoding (default: enabled)")

    def limitOpt[C <: Command: reflect.ClassTag](set: (C, Long) => C) =
      opt[Long]("limit")
        .abbr("n")
        .valueName("<n>")
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
        .valueName("<file>")
        .action((x, c) => c.copy(globalOptions = c.globalOptions.copy(configPath = Some(x))))
        .text("Path to configuration file"),
      opt[String]("profile")
        .valueName("<name>")
        .action((x, c) => c.copy(globalOptions = c.globalOptions.copy(profile = Some(x))))
        .text("AWS S3 credentials profile (from ~/.aws/credentials)"),
      opt[String]("region")
        .valueName("<region>")
        .action((x, c) => c.copy(globalOptions = c.globalOptions.copy(region = Some(x))))
        .text("AWS S3 region (e.g. us-east-1, ap-northeast-1)"),
      opt[Int]("file-parallelism")
        .valueName("<n>")
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
        .valueName("<mode>")
        .action((x, c) =>
          c.copy(globalOptions =
            c.globalOptions.copy(colorMode = ColorMode.fromString(x).getOrElse(ColorMode.Auto))
          )
        )
        .validate(x =>
          if ColorMode.fromString(x).isDefined then success
          else failure(s"Invalid color mode: $x. Use ${orList(Choices.colors)}")
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
            .valueName("<col,...>")
            .action((x, c) => updateCmd[ReadCommand](c, _.copy(columns = Some(x.toList))))
            .text("Comma-separated list of columns to display"),
          opt[String]("filter")
            .abbr("f")
            .valueName("<expr>")
            .action((x, c) => updateCmd[ReadCommand](c, _.copy(filter = Some(x))))
            .text("Filter expression for rows"),
          enumOpt[ReadCommand, OutputFormat](
            "format",
            OutputFormat.fromString,
            Choices.formats,
            "<fmt>"
          )((cmd, f) => cmd.copy(format = f))
            .text(
              "Output format: table, json, csv, pretty, markdown, ndjson, ltsv (default: table)"
            ),
          opt[Int]("parallel")
            .valueName("<n>")
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
            Choices.inputFormats,
            "<fmt>"
          )((cmd, f) => cmd.copy(inputFormat = f))
            .text("Input file format: json, ndjson, csv, ltsv (default: json)"),
          compressionOpt[WriteCommand]((cmd, ct) => cmd.copy(compression = ct))
            .abbr("c")
            .text(
              "Compression type: none, snappy, gzip, lzo, brotli, lz4, zstd"
            ),
          opt[String]("row-group-size")
            .valueName("<size>")
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
          pageSizeOpt[WriteCommand](_.writer, (cmd, w) => cmd.copy(writer = w)),
          noDictionaryOpt[WriteCommand](_.writer, (cmd, w) => cmd.copy(writer = w)),
          opt[String]("schema")
            .valueName("<contract.json>")
            .action((x, c) => updateCmd[WriteCommand](c, _.copy(schemaFile = Some(x))))
            .text(
              "Write with the column names, types and nullability in this contract (the output of `schema --format json`) instead of inferring them from the input"
            ),
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
          opt[String]("assert")
            .unbounded()
            .valueName("<check>")
            .validate(x =>
              StatsAssertion.parse(x).fold(e => failure(s"Invalid --assert: $e"), _ => success)
            )
            .action((x, c) =>
              StatsAssertion
                .parse(x)
                .fold(
                  _ => c,
                  a => updateCmd[ValidateCommand](c, v => v.copy(asserts = v.asserts :+ a))
                )
            )
            .text(
              "Data-quality check on footer statistics, repeatable: 'rows > 0', 'id.nulls == 0', 'amount.min >= 0', 'day.max <= \"2026-12-31\"'"
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
          pageSizeOpt[ConvertCommand](_.writer, (cmd, w) => cmd.copy(writer = w)),
          noDictionaryOpt[ConvertCommand](_.writer, (cmd, w) => cmd.copy(writer = w)),
          opt[String]("schema")
            .valueName("<contract.json>")
            .action((x, c) => updateCmd[ConvertCommand](c, _.copy(schemaFile = Some(x))))
            .text(
              "Write parquet from text input with this contract's schema (the output of `schema --format json`) instead of inferring one; not for parquet input"
            ),
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
            .valueName("<file>")
            .required()
            .action((x, c) => updateCmd[MergeCommand](c, _.copy(outputPath = x)))
            .text("Output parquet file path"),
          compressionOpt[MergeCommand]((cmd, ct) => cmd.copy(compression = ct))
            .abbr("c")
            .text("Output compression (default: snappy)"),
          schemaModeOpt[MergeCommand]((cmd, m) => cmd.copy(schemaMode = m))
            .text("Schema compatibility mode: strict (default) or union"),
          pageSizeOpt[MergeCommand](_.writer, (cmd, w) => cmd.copy(writer = w)),
          noDictionaryOpt[MergeCommand](_.writer, (cmd, w) => cmd.copy(writer = w)),
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
              else failure(s"Unsupported shell: $x. Use ${orList(Choices.shells)}")
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

  // "a, b, or c" for error messages.
  private def orList(choices: List[String]): String =
    if choices.sizeIs < 2 then choices.mkString
    else s"${choices.init.mkString(", ")}, or ${choices.last}"

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
