package io.github.yusukensanta.parqueteer.cli

import CliSpec.OptSpec

/**
 * Per-command help. The prose (summary, usage, arguments, examples) is written
 * here; every OPTIONS section is rendered from the parser via CliSpec, so a
 * flag can't be added to the parser and forgotten in its help.
 */
object HelpFormatter {

  private val HelpWidth = 100

  private val helpOption = OptSpec("help", Some("h"), None, "Show this help message")

  /** The OPTIONS section of `command`'s help: its parser options, then --help. */
  private[cli] def optionsSection(command: String): String =
    renderOptions(CliSpec.command(command).fold(Nil)(_.options) :+ helpOption)

  /** Two aligned columns, descriptions word-wrapped to HelpWidth with a hanging indent. */
  private[cli] def renderOptions(options: List[OptSpec]): String = {
    def flags(o: OptSpec): String =
      o.short.fold("    ")(c => s"-$c, ") + s"--${o.long}" + o.valueName.fold("")(v => s" $v")
    val column = options.map(flags(_).length).maxOption.getOrElse(0) + 3
    options
      .map(o => wrap(s"  ${flags(o).padTo(column, ' ')}", o.text, indent = 2 + column))
      .mkString("\n")
  }

  private def wrap(prefix: String, text: String, indent: Int): String = {
    val lines = scala.collection.mutable.ListBuffer(new StringBuilder(prefix))
    // A quoted example ('rows > 0') is kept on one line.
    """'[^']*'\S*|\S+""".r.findAllIn(text).foreach { word =>
      val line = lines.last
      if line.length > indent && line.length + 1 + word.length > HelpWidth then
        lines += new StringBuilder(" " * indent).append(word)
      else {
        if line.length > indent then line.append(' ')
        line.append(word)
      }
    }
    lines.map(_.toString).mkString("\n")
  }

  def subcommandHelp(command: String): Option[String] = command match {
    case "read"        => Some(readHelp())
    case "info"        => Some(infoHelp())
    case "write"       => Some(writeHelp())
    case "validate"    => Some(validateHelp())
    case "convert"     => Some(convertHelp())
    case "schema"      => Some(schemaHelp())
    case "schema diff" => Some(schemaDiffHelp())
    case "merge"       => Some(mergeHelp())
    case "stats"       => Some(statsHelp())
    case "count"       => Some(countHelp())
    case "completions" => Some(completionsHelp())
    case "config"      => Some(configHelp())
    case _             => None
  }

  private def readHelp(): String =
    s"""parqueteer read - Display parquet file content
       |
       |USAGE:
       |  parqueteer read [OPTIONS] <file>
       |
       |ARGUMENTS:
       |  <file>    Path to parquet file (local, s3://, gs://, abfss://)
       |
       |OPTIONS:
${optionsSection("read")}
       |
       |EXAMPLES:
       |  parqueteer read data.parquet
       |  parqueteer read data.parquet --limit 100
       |  parqueteer read data.parquet --format json --columns id,name
       |  parqueteer read s3://bucket/data.parquet --filter 'age > 30'
       |""".stripMargin

  private def infoHelp(): String =
    s"""parqueteer info - Show file metadata (size, dates, writer version, compression ratio)
       |
       |USAGE:
       |  parqueteer info [OPTIONS] <file>
       |
       |ARGUMENTS:
       |  <file>    Path to parquet file (local, s3://, gs://, abfss://)
       |
       |OPTIONS:
${optionsSection("info")}
       |
       |EXAMPLES:
       |  parqueteer info data.parquet
       |  parqueteer info data.parquet --verbose
       |  parqueteer info data.parquet --format json --verbose
       |  parqueteer info s3://bucket/data.parquet
       |""".stripMargin

  private def writeHelp(): String =
    s"""parqueteer write - Create parquet file from input data
       |
       |USAGE:
       |  parqueteer write [OPTIONS] <input> <output>
       |
       |ARGUMENTS:
       |  <input>    Input data file path (JSON, NDJSON, CSV, or LTSV)
       |  <output>   Output parquet file path
       |
       |OPTIONS:
${optionsSection("write")}
       |
       |EXAMPLES:
       |  parqueteer write data.json output.parquet
       |  parqueteer write data.csv output.parquet --input-format csv --compression zstd
       |  parqueteer write data.json output.parquet --dry-run
       |  parqueteer write events.ndjson out.parquet --input-format ndjson --schema contract.json
       |""".stripMargin

  private def validateHelp(): String =
    s"""parqueteer validate - Verify parquet file integrity
       |
       |USAGE:
       |  parqueteer validate [OPTIONS] <file>
       |
       |ARGUMENTS:
       |  <file>    Path to parquet file (local, s3://, gs://, abfss://)
       |
       |OPTIONS:
${optionsSection("validate")}
       |
       |EXAMPLES:
       |  parqueteer validate data.parquet
       |  parqueteer validate data.parquet --verbose
       |  parqueteer validate data.parquet --deep
       |  parqueteer schema data.parquet --format json > contract.json
       |  parqueteer validate 's3://bucket/daily/*.parquet' --expect-schema contract.json
       |  parqueteer validate data.parquet --assert 'rows > 0' --assert 'id.nulls == 0'
       |""".stripMargin

  private def convertHelp(): String =
    s"""parqueteer convert - Convert between parquet and other formats
       |
       |USAGE:
       |  parqueteer convert [OPTIONS] <input> <output>
       |
       |ARGUMENTS:
       |  <input>    Input file path
       |  <output>   Output file path
       |
       |SUPPORTED CONVERSIONS:
       |  parquet → parquet   (recompress / subset rows)
       |  parquet → json
       |  parquet → ndjson
       |  parquet → csv
       |  json    → parquet
       |  ndjson  → parquet
       |  csv     → parquet
       |  ltsv    → parquet
       |
       |OPTIONS:
${optionsSection("convert")}
       |
       |EXAMPLES:
       |  parqueteer convert data.parquet out.json
       |  parqueteer convert data.parquet out.parquet --compression zstd
       |  parqueteer convert data.csv out.parquet
       |  parqueteer convert data.csv out.parquet --schema contract.json
       |""".stripMargin

  private def schemaHelp(): String =
    s"""parqueteer schema - Show column structure (names, types, nullability, compression)
       |
       |USAGE:
       |  parqueteer schema [OPTIONS] <file>
       |  parqueteer schema diff <file1> <file2>
       |
       |ARGUMENTS:
       |  <file>    Path to parquet file (local, s3://, gs://, abfss://)
       |
       |SUBCOMMANDS:
       |  diff      Compare schemas of two parquet files
       |
       |OPTIONS:
${optionsSection("schema")}
       |
       |EXAMPLES:
       |  parqueteer schema data.parquet
       |  parqueteer schema data.parquet --format json
       |  parqueteer schema diff old.parquet new.parquet
       |""".stripMargin

  private def schemaDiffHelp(): String =
    s"""parqueteer schema diff - Compare schemas of two parquet files
       |
       |USAGE:
       |  parqueteer schema diff [OPTIONS] <file1> <file2>
       |
       |ARGUMENTS:
       |  <file1>   First parquet file path
       |  <file2>   Second parquet file path
       |
       |OPTIONS:
${optionsSection("schema diff")}
       |
       |EXAMPLES:
       |  parqueteer schema diff old.parquet new.parquet
       |  parqueteer schema diff old.parquet new.parquet --format json
       |""".stripMargin

  private def mergeHelp(): String =
    s"""parqueteer merge - Merge multiple parquet files into one
       |
       |USAGE:
       |  parqueteer merge [OPTIONS] <input>... --output <output>
       |
       |ARGUMENTS:
       |  <input>...   Two or more input parquet file paths
       |
       |OPTIONS:
${optionsSection("merge")}
       |
       |EXAMPLES:
       |  parqueteer merge a.parquet b.parquet --output merged.parquet
       |  parqueteer merge *.parquet --output merged.parquet --compression zstd
       |  parqueteer merge a.parquet b.parquet --output out.parquet --schema-mode union
       |""".stripMargin

  private def statsHelp(): String =
    s"""parqueteer stats - Show column statistics (min, max, null count) from row group metadata
       |
       |USAGE:
       |  parqueteer stats [OPTIONS] <file>
       |
       |ARGUMENTS:
       |  <file>    Path to parquet file (local, s3://, gs://, abfss://)
       |
       |OPTIONS:
${optionsSection("stats")}
       |
       |EXAMPLES:
       |  parqueteer stats data.parquet
       |  parqueteer stats data.parquet --format json
       |""".stripMargin

  private def countHelp(): String =
    s"""parqueteer count - Print total row count from footer metadata (no data scan)
       |
       |USAGE:
       |  parqueteer count [OPTIONS] <file>
       |
       |ARGUMENTS:
       |  <file>    Path to parquet file (local, s3://, gs://, abfss://)
       |
       |OPTIONS:
${optionsSection("count")}
       |
       |EXAMPLES:
       |  parqueteer count data.parquet
       |  parqueteer count data.parquet --format json
       |  [ $$(parqueteer count data.parquet) -gt 0 ] && echo "non-empty"
       |""".stripMargin

  private def completionsHelp(): String =
    s"""parqueteer completions - Generate shell completion scripts
       |
       |USAGE:
       |  parqueteer completions <shell>
       |
       |ARGUMENTS:
       |  <shell>   Shell type: bash, zsh, fish
       |
       |OPTIONS:
${optionsSection("completions")}
       |
       |EXAMPLES:
       |  parqueteer completions bash >> ~/.bashrc
       |  parqueteer completions zsh >> ~/.zshrc
       |  parqueteer completions fish > ~/.config/fish/completions/parqueteer.fish
       |""".stripMargin

  private def configHelp(): String =
    s"""parqueteer config - Show or validate configuration
       |
       |USAGE:
       |  parqueteer config [OPTIONS]
       |
       |OPTIONS:
${optionsSection("config")}
       |
       |EXAMPLES:
       |  parqueteer config
       |  parqueteer config --validate
       |""".stripMargin

  def topLevelHelp(): String =
    s"""parqueteer ${io.github.yusukensanta.parqueteer.BuildInfo.version} - A modern CLI toolkit for Apache Parquet files
       |
       |USAGE:
       |  parqueteer [OPTIONS] <COMMAND>
       |
       |INSPECTION COMMANDS:
       |  info             File metadata (size, dates, writer version, compression ratio)
       |  schema           Column structure (names, types, nullability, compression)
       |  schema diff      Compare column structures of two parquet files
       |  stats            Column statistics (min, max, null count) from row group metadata
       |  count            Total row count from footer metadata (no data scan)
       |
       |DATA COMMANDS:
       |  read             Display parquet file content
       |  write            Create parquet file from input data
       |  convert          Convert between parquet and other formats
       |  merge            Combine multiple parquet files into one
       |  validate         Verify parquet file integrity
       |
       |OTHER:
       |  config           Show or validate configuration
       |  completions      Generate shell completion scripts
       |
       |GLOBAL OPTIONS:
${renderOptions(CliSpec.globalOptions)}
       |
       |For detailed command usage, run:
       |  parqueteer <COMMAND> --help
       |
       |EXAMPLES:
       |  parqueteer read data.parquet
       |  parqueteer info data.parquet
       |  parqueteer schema data.parquet
       |  parqueteer stats data.parquet
       |  parqueteer schema diff old.parquet new.parquet
       |""".stripMargin
}
