package io.github.yusukensanta.parqueteer.cli

object HelpFormatter {

  /** Generate top-level help showing all subcommands */
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
       |  schema diff  Compare schemas of two parquet files
       |  config       Show or validate configuration
       |  completions  Generate shell completion scripts
       |
       |GLOBAL OPTIONS:
       |  -h, --help         Show this help message
       |  -V, --version      Show version information
       |  -v, --verbose      Enable verbose output
       |      --config       Path to configuration file
       |      --profile      Cloud credentials profile to use
       |      --region       Cloud region to use
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

  /** Generate help for the 'read' command */
  def readHelp(): String = {
    """
       |USAGE:
       |  parqueteer read [OPTIONS] <FILE>
       |
       |ARGUMENTS:
       |  <FILE>    Path to parquet file (local, s3://, gs://, abfss://)
       |
       |OPTIONS:
       |  -n, --max-rows <N>        Maximum number of rows to display
       |  -c, --columns <COLS>      Comma-separated list of columns to display
       |  -f, --filter <EXPR>       Filter expression for rows
       |      --format <FORMAT>     Output format: table, json, csv, pretty (default: table)
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
       |  # Display first 10 rows as a table
       |  parqueteer read data.parquet --max-rows 10
       |
       |  # Show specific columns only
       |  parqueteer read data.parquet --columns name,age,city
       |
       |  # Filter rows using BETWEEN
       |  parqueteer read data.parquet --filter "age BETWEEN 25 AND 35" --format json
       |
       |  # Filter with IN operator
       |  parqueteer read data.parquet --filter 'status IN ("active", "pending")'
       |
       |  # Read from S3
       |  parqueteer read s3://bucket/data.parquet --max-rows 100
       |""".stripMargin
  }

  /** Generate help for the 'info' command */
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
       |  # Show all metadata and schema (default)
       |  parqueteer info data.parquet
       |
       |  # Show only schema
       |  parqueteer info data.parquet --schema
       |
       |  # Show only metadata
       |  parqueteer info data.parquet --metadata
       |
       |  # Output as JSON
       |  parqueteer info data.parquet --format json
       |""".stripMargin
  }

  /** Generate help for the 'write' command */
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
       |      --input-format <FMT>   Input file format: json, csv, tsv (default: json)
       |  -c, --compression <TYPE>   Compression: none, snappy, gzip, lzo, brotli, lz4, zstd
       |      --row-group-size <N>   Row group size (e.g., 128MB)
       |      --dry-run              Preview what would be written without writing
       |  -h, --help                 Show this help message
       |
       |EXAMPLES:
       |  # Convert JSON to Parquet with snappy compression
       |  parqueteer write data.json output.parquet
       |
       |  # Preview without writing
       |  parqueteer write data.json output.parquet --dry-run
       |
       |  # Convert CSV with gzip compression
       |  parqueteer write data.csv output.parquet --input-format csv --compression gzip
       |
       |  # Specify row group size
       |  parqueteer write data.json output.parquet --row-group-size 256MB
       |""".stripMargin
  }

  /** Generate help for the 'validate' command */
  def validateHelp(): String = {
    """
       |USAGE:
       |  parqueteer validate [OPTIONS] <FILE>
       |
       |ARGUMENTS:
       |  <FILE>    Path to parquet file
       |
       |OPTIONS:
       |  -v, --verbose    Show detailed validation information
       |  -h, --help       Show this help message
       |
       |EXAMPLES:
       |  # Basic validation
       |  parqueteer validate data.parquet
       |
       |  # Detailed validation output
       |  parqueteer validate data.parquet --verbose
       |""".stripMargin
  }

  /** Generate help for the 'convert' command */
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
       |  -n, --max-rows <N>         Maximum number of rows to convert
       |      --dry-run              Preview what would be converted without converting
       |  -h, --help                 Show this help message
       |
       |EXAMPLES:
       |  # Convert CSV to Parquet
       |  parqueteer convert data.csv data.parquet
       |
       |  # Preview without converting
       |  parqueteer convert data.parquet data.json --dry-run
       |
       |  # Convert Parquet to JSON with row limit
       |  parqueteer convert data.parquet data.json --max-rows 1000
       |
       |  # Convert with specific compression
       |  parqueteer convert data.csv data.parquet --compression zstd
       |""".stripMargin
  }

  /** Generate help for the 'merge' command */
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
       |  # Merge two files with default settings
       |  parqueteer merge a.parquet b.parquet --output merged.parquet
       |
       |  # Merge with union schema mode and gzip compression
       |  parqueteer merge a.parquet b.parquet --output out.parquet --schema-mode union --compression gzip
       |
       |  # Merge files from S3
       |  parqueteer merge s3://bucket/a.parquet s3://bucket/b.parquet --output s3://bucket/merged.parquet
       |""".stripMargin
  }

  /** Generate help for the 'stats' command */
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
       |  # Show column statistics as table
       |  parqueteer stats data.parquet
       |
       |  # Output statistics as JSON
       |  parqueteer stats data.parquet --format json
       |""".stripMargin
  }

  /** Get help for a specific command */
  def commandHelp(command: String): Option[String] = {
    command.toLowerCase match {
      case "read"     => Some(readHelp())
      case "info"     => Some(infoHelp())
      case "write"    => Some(writeHelp())
      case "validate" => Some(validateHelp())
      case "convert"  => Some(convertHelp())
      case "merge"    => Some(mergeHelp())
      case "stats"    => Some(statsHelp())
      case _          => None
    }
  }
}
