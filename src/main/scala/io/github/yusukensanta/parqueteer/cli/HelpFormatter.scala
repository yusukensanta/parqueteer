package io.github.yusukensanta.parqueteer.cli

object HelpFormatter {

  def subcommandHelp(command: String): Option[String] = command match {
    case "read"       => Some(readHelp())
    case "info"       => Some(infoHelp())
    case "write"      => Some(writeHelp())
    case "validate"   => Some(validateHelp())
    case "convert"    => Some(convertHelp())
    case "schema"     => Some(schemaHelp())
    case "schema diff" => Some(schemaDiffHelp())
    case "merge"      => Some(mergeHelp())
    case "stats"      => Some(statsHelp())
    case "completions" => Some(completionsHelp())
    case "config"     => Some(configHelp())
    case _            => None
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
       |  -n, --limit <n>           Maximum number of rows to display
       |  -c, --columns <col,...>   Comma-separated list of columns to display
       |  -f, --filter <expr>       Filter expression for rows
       |      --format <fmt>        Output format: table, json, csv, pretty, markdown, ndjson, ltsv (default: table)
       |      --parallel <n>        Number of parallel threads (default: 1)
       |      --stream              Stream rows progressively (memory-bounded, safe for large files)
       |  -h, --help                Show this help message
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
       |      --format <fmt>   Output format: table, json (default: table)
       |  -h, --help           Show this help message
       |
       |EXAMPLES:
       |  parqueteer info data.parquet
       |  parqueteer info data.parquet --format json
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
       |      --input-format <fmt>      Input format: json, ndjson, csv, ltsv (default: json)
       |  -c, --compression <type>      Compression: none, snappy, gzip, lzo, brotli, lz4, zstd
       |      --row-group-size <size>   Row group size (e.g., 128MB, 1.5GB)
       |      --dry-run                 Preview what would be written without writing
       |  -h, --help                    Show this help message
       |
       |EXAMPLES:
       |  parqueteer write data.json output.parquet
       |  parqueteer write data.csv output.parquet --input-format csv --compression zstd
       |  parqueteer write data.json output.parquet --dry-run
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
       |  -v, --verbose   Show detailed validation information
       |      --deep      Fully decompress all row groups (default: spot-check first, last, midpoint)
       |  -h, --help      Show this help message
       |
       |EXAMPLES:
       |  parqueteer validate data.parquet
       |  parqueteer validate data.parquet --verbose
       |  parqueteer validate data.parquet --deep
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
       |  csv     → parquet
       |
       |OPTIONS:
       |      --compression <type>   Output compression type
       |  -n, --limit <n>            Maximum number of rows to convert
       |      --dry-run              Preview what would be converted without converting
       |  -h, --help                 Show this help message
       |
       |EXAMPLES:
       |  parqueteer convert data.parquet out.json
       |  parqueteer convert data.parquet out.parquet --compression zstd
       |  parqueteer convert data.csv out.parquet
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
       |      --format <fmt>   Output format: table, json (default: table)
       |  -h, --help           Show this help message
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
       |      --format <fmt>   Output format: table, json (default: table)
       |  -h, --help           Show this help message
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
       |  -o, --output <file>         Output parquet file path (required)
       |  -c, --compression <type>    Output compression (default: snappy)
       |      --schema-mode <mode>    Schema compatibility: strict (default) or union
       |  -h, --help                  Show this help message
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
       |      --format <fmt>   Output format: table, json (default: table)
       |  -h, --help           Show this help message
       |
       |EXAMPLES:
       |  parqueteer stats data.parquet
       |  parqueteer stats data.parquet --format json
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
       |  -h, --help   Show this help message
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
       |      --validate   Validate the configuration file instead of displaying it
       |  -h, --help       Show this help message
       |
       |EXAMPLES:
       |  parqueteer config
       |  parqueteer config --validate
       |""".stripMargin

  def topLevelHelp(): String = {
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
       |  -h, --help         Show this help message
       |  -V, --version      Show version information
       |  -v, --verbose      Enable verbose output
       |  -q, --quiet        Suppress non-error output
       |      --config       Path to configuration file
       |      --profile      AWS S3 credentials profile (from ~/.aws/credentials)
       |      --region       AWS S3 region (e.g. us-east-1, ap-northeast-1)
       |      --color        Color mode: auto, always, never (default: auto)
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
}
