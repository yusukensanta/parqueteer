package io.github.yusukensanta.parqueteer.cli

object HelpFormatter {

  /** Generate top-level help showing all subcommands */
  def topLevelHelp(): String = {
    s"""parqueteer 0.1.0 - A modern CLI toolkit for Apache Parquet files
       |
       |USAGE:
       |  parqueteer [OPTIONS] <COMMAND>
       |
       |COMMANDS:
       |  read        Display parquet file content
       |  info        Show file metadata and schema information
       |  write       Create parquet file from input data
       |  validate    Verify parquet file integrity
       |  convert     Convert between parquet and other formats
       |
       |GLOBAL OPTIONS:
       |  -h, --help         Show this help message
       |  -V, --version      Show version information
       |  -v, --verbose      Enable verbose output
       |      --config       Path to configuration file
       |      --profile      Cloud credentials profile to use
       |      --region       Cloud region to use
       |      --no-color     Disable colored output
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
    s"""
       |USAGE:
       |  parqueteer read [OPTIONS] <FILE>
       |
       |ARGUMENTS:
       |  <FILE>    Path to parquet file (local, s3://, gs://, abfss://)
       |
       |OPTIONS:
       |  -n, --max-rows <N>        Maximum number of rows to display
       |  -c, --columns <COLS>      Comma-separated list of columns to display
       |  -f, --filter <EXPR>       Filter expression for rows (e.g., "age > 25")
       |      --format <FORMAT>     Output format: table, json, csv, pretty (default: table)
       |  -h, --help                Show this help message
       |
       |EXAMPLES:
       |  # Display first 10 rows as a table
       |  parqueteer read data.parquet --max-rows 10
       |
       |  # Show specific columns only
       |  parqueteer read data.parquet --columns name,age,city
       |
       |  # Filter rows and output as JSON
       |  parqueteer read data.parquet --filter "age > 25" --format json
       |
       |  # Read from S3
       |  parqueteer read s3://bucket/data.parquet --max-rows 100
       |""".stripMargin
  }

  /** Generate help for the 'info' command */
  def infoHelp(): String = {
    s"""
       |USAGE:
       |  parqueteer info [OPTIONS] <FILE>
       |
       |ARGUMENTS:
       |  <FILE>    Path to parquet file
       |
       |OPTIONS:
       |      --format <FORMAT>     Output format: table, json (default: table)
       |  -s, --no-schema          Don't show schema information
       |  -m, --no-metadata        Don't show metadata information
       |  -h, --help               Show this help message
       |
       |EXAMPLES:
       |  # Show all metadata and schema
       |  parqueteer info data.parquet
       |
       |  # Show only schema
       |  parqueteer info data.parquet --no-metadata
       |
       |  # Output as JSON
       |  parqueteer info data.parquet --format json
       |""".stripMargin
  }

  /** Generate help for the 'write' command */
  def writeHelp(): String = {
    s"""
       |USAGE:
       |  parqueteer write [OPTIONS] <OUTPUT>
       |
       |ARGUMENTS:
       |  <OUTPUT>    Output parquet file path
       |
       |OPTIONS:
       |  -i, --input <FILE>         Input data file path (required)
       |      --input-format <FMT>   Input file format: json, csv, tsv (default: json)
       |  -c, --compression <TYPE>   Compression: none, snappy, gzip, lzo, brotli, lz4, zstd
       |      --row-group-size <N>   Row group size (e.g., 128MB)
       |  -h, --help                 Show this help message
       |
       |EXAMPLES:
       |  # Convert JSON to Parquet with snappy compression
       |  parqueteer write output.parquet --input data.json
       |
       |  # Convert CSV with gzip compression
       |  parqueteer write output.parquet --input data.csv --input-format csv --compression gzip
       |
       |  # Specify row group size
       |  parqueteer write output.parquet --input data.json --row-group-size 256MB
       |""".stripMargin
  }

  /** Generate help for the 'validate' command */
  def validateHelp(): String = {
    s"""
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
    s"""
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
       |  -h, --help                 Show this help message
       |
       |EXAMPLES:
       |  # Convert CSV to Parquet
       |  parqueteer convert data.csv data.parquet
       |
       |  # Convert Parquet to JSON with row limit
       |  parqueteer convert data.parquet data.json --max-rows 1000
       |
       |  # Convert with specific compression
       |  parqueteer convert data.csv data.parquet --compression zstd
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
      case _          => None
    }
  }
}
