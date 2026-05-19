package io.github.yusukensanta.parqueteer.cli

object HelpFormatter {

  def topLevelHelp(): String = {
    s"""parqueteer ${io.github.yusukensanta.parqueteer.BuildInfo.version} - A modern CLI toolkit for Apache Parquet files
       |
       |USAGE:
       |  parqueteer [OPTIONS] <COMMAND>
       |
       |COMMANDS:
       |  read         Display parquet file content
       |  info         Show file metadata and schema information
       |  write        Create parquet file from input data
       |  validate     Verify parquet file integrity
       |  convert      Convert between parquet and other formats
       |  merge        Combine multiple parquet files into one
       |  stats        Show column statistics (min, max, null count)
       |  schema       Compare schemas of two parquet files
       |  config       Show or validate configuration
       |  completions  Generate shell completion scripts
       |
       |GLOBAL OPTIONS:
       |  -h, --help         Show this help message
       |  -V, --version      Show version information
       |  -v, --verbose      Enable verbose output
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
       |  parqueteer info s3://bucket/data.parquet
       |  parqueteer convert data.csv data.parquet
       |""".stripMargin
  }

  def readHelp(): String = {
    """
       |USAGE:
       |  parqueteer read [OPTIONS] <FILE>
       |
       |ARGUMENTS:
       |  <FILE>    Path to parquet file (local, s3://, gs://, abfss://)
       |
       |OPTIONS:
       |  -n, --limit <N>           Maximum number of rows to display
       |  -c, --columns <COLS>      Comma-separated list of columns to display
       |  -f, --filter <EXPR>       Filter expression for rows
       |      --format <FORMAT>     Output format: table, json, csv, pretty (default: table)
       |      --parallel <N>        Number of parallel threads for reading (default: 1)
       |      --stream              Stream rows progressively (safe for large files)
       |  -h, --help                Show this help message
       |
       |FILTER EXPRESSIONS:
       |  Comparison:  age > 25, name = "Alice", active != false
       |  BETWEEN:     age BETWEEN 25 AND 35
       |  IN:          status IN ("active", "pending")
       |  IS NULL:     email IS NULL, phone IS NOT NULL
       |  Nested:      user.address.city = "NYC"
       |  Logical:     age > 18 AND active = true, NOT deleted = true
       |
       |EXAMPLES:
       |  parqueteer read data.parquet --limit 10
       |  parqueteer read data.parquet --columns name,age,city
       |  parqueteer read data.parquet --filter "age BETWEEN 25 AND 35" --format json
       |  parqueteer read s3://bucket/data.parquet --limit 100
       |""".stripMargin
  }

  def infoHelp(): String = {
    """
       |USAGE:
       |  parqueteer info [OPTIONS] <FILE>
       |
       |ARGUMENTS:
       |  <FILE>    Path to parquet file
       |
       |OPTIONS:
       |      --format <FORMAT>     Output format: table, json (default: table)
       |  -s, --schema             Show schema information only
       |  -m, --metadata           Show metadata information only
       |  -h, --help               Show this help message
       |
       |EXAMPLES:
       |  parqueteer info data.parquet
       |  parqueteer info data.parquet --schema
       |  parqueteer info data.parquet --format json
       |""".stripMargin
  }

  def writeHelp(): String = {
    """
       |USAGE:
       |  parqueteer write [OPTIONS] <INPUT> <OUTPUT>
       |
       |ARGUMENTS:
       |  <INPUT>     Input data file path (JSON or CSV)
       |  <OUTPUT>    Output parquet file path
       |
       |OPTIONS:
       |      --input-format <FMT>   Input file format: json, csv (default: json)
       |  -c, --compression <TYPE>   Compression: none, snappy, gzip, lzo, brotli, lz4, zstd
       |      --row-group-size <N>   Row group size (e.g., 128MB)
       |      --dry-run              Preview what would be written without writing
       |  -h, --help                 Show this help message
       |
       |EXAMPLES:
       |  parqueteer write data.json output.parquet
       |  parqueteer write data.csv output.parquet --input-format csv --compression gzip
       |  parqueteer write data.json output.parquet --dry-run
       |""".stripMargin
  }

  def validateHelp(): String = {
    """
       |USAGE:
       |  parqueteer validate [OPTIONS] <FILE>
       |
       |ARGUMENTS:
       |  <FILE>    Path to parquet file
       |
       |OPTIONS:
       |  -h, --help    Show this help message
       |
       |EXAMPLES:
       |  parqueteer validate data.parquet
       |""".stripMargin
  }

  def convertHelp(): String = {
    """
       |USAGE:
       |  parqueteer convert [OPTIONS] <INPUT> <OUTPUT>
       |
       |ARGUMENTS:
       |  <INPUT>     Input file path
       |  <OUTPUT>    Output file path
       |
       |OPTIONS:
       |      --compression <TYPE>   Compression type for output
       |  -n, --limit <N>            Maximum number of rows to convert
       |      --dry-run              Preview what would be converted without converting
       |  -h, --help                 Show this help message
       |
       |EXAMPLES:
       |  parqueteer convert data.csv data.parquet
       |  parqueteer convert data.parquet data.json --limit 1000
       |  parqueteer convert data.csv data.parquet --compression zstd
       |""".stripMargin
  }

  def mergeHelp(): String = {
    """
       |USAGE:
       |  parqueteer merge [OPTIONS] <INPUT...> --output <OUTPUT>
       |
       |ARGUMENTS:
       |  <INPUT...>   Two or more input parquet files
       |
       |OPTIONS:
       |  -o, --output <FILE>           Output parquet file path (required)
       |  -c, --compression <TYPE>      Compression: none, snappy, gzip, lzo, brotli, lz4, zstd
       |      --schema-mode <MODE>      Schema compatibility: strict (default) or union
       |  -h, --help                    Show this help message
       |
       |SCHEMA MODES:
       |  strict   All input files must have identical schemas (default)
       |  union    Merge schemas; missing columns filled with null
       |
       |EXAMPLES:
       |  parqueteer merge a.parquet b.parquet --output merged.parquet
       |  parqueteer merge a.parquet b.parquet --output out.parquet --schema-mode union
       |""".stripMargin
  }

  def statsHelp(): String = {
    """
       |USAGE:
       |  parqueteer stats [OPTIONS] <FILE>
       |
       |ARGUMENTS:
       |  <FILE>    Path to parquet file
       |
       |OPTIONS:
       |      --format <FORMAT>     Output format: table, json (default: table)
       |  -h, --help                Show this help message
       |
       |EXAMPLES:
       |  parqueteer stats data.parquet
       |  parqueteer stats data.parquet --format json
       |""".stripMargin
  }

  def schemaHelp(): String = {
    """
       |USAGE:
       |  parqueteer schema [OPTIONS] <FILE1> <FILE2>
       |
       |ARGUMENTS:
       |  <FILE1>   First parquet file path
       |  <FILE2>   Second parquet file path
       |
       |OPTIONS:
       |      --format <FORMAT>     Output format: table, json (default: table)
       |  -h, --help                Show this help message
       |
       |OUTPUT SYMBOLS:
       |  +  column added in FILE2
       |  -  column removed in FILE2
       |  ~  column type or nullability changed
       |  =  unchanged columns
       |
       |EXAMPLES:
       |  parqueteer schema old.parquet new.parquet
       |  parqueteer schema old.parquet new.parquet --format json
       |""".stripMargin
  }

  def configHelp(): String = {
    """
       |USAGE:
       |  parqueteer config [OPTIONS]
       |
       |OPTIONS:
       |      --validate    Validate the configuration file
       |  -h, --help        Show this help message
       |
       |ENVIRONMENT VARIABLES:
       |  PARQUETEER_CONFIG           Path to config file
       |  PARQUETEER_DEFAULT_FORMAT   Default output format
       |  PARQUETEER_COLOR            Color mode: auto, always, never
       |  PARQUETEER_VERBOSE          Enable verbose output (true/false)
       |  PARQUETEER_MAX_ROWS         Default row limit
       |  NO_COLOR                    Disable color output
       |
       |EXAMPLES:
       |  parqueteer config
       |  parqueteer config --validate
       |""".stripMargin
  }

  def commandHelp(command: String): Option[String] = {
    command.toLowerCase match {
      case "read"     => Some(readHelp())
      case "info"     => Some(infoHelp())
      case "write"    => Some(writeHelp())
      case "validate" => Some(validateHelp())
      case "convert"  => Some(convertHelp())
      case "merge"    => Some(mergeHelp())
      case "stats"    => Some(statsHelp())
      case "schema"   => Some(schemaHelp())
      case "config"   => Some(configHelp())
      case _          => None
    }
  }
}
