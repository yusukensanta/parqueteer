package io.github.yusukensanta.parqueteer.cli

object HelpFormatter {

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
