package io.github.yusukensanta.parqueteer.core.models

import scala.concurrent.duration.{Duration, FiniteDuration}

/** Controls how rows are read: row limit, column projection, filter, output format, parallelism. */
case class ReadConfig(
    maxRows: Option[Long] = None,
    columns: Option[List[String]] = None,
    filter: Option[String] = None,
    outputFormat: OutputFormat = OutputFormat.Table,
    parallelism: Int = 1,
    readTimeout: FiniteDuration = Duration(5, "minutes")
)

enum OutputFormat:
  case Table, JSON, CSV, Pretty, Markdown, NDJSON, LTSV

object OutputFormat:

  /** Case-insensitive lookup by CLI name: table, json, csv, pretty, markdown, ndjson, ltsv. */
  def fromString(s: String): Option[OutputFormat] =
    values.find(_.toString.equalsIgnoreCase(s))

/** Controls Parquet write: compression codec, row group size, page size, dictionary encoding. */
case class WriteConfig(
    compressionType: CompressionType = CompressionType.Snappy,
    rowGroupSize: Long = WriteConfig.DefaultRowGroupSize,
    pageSize: Int = 1024 * 1024,
    enableDictionary: Boolean = true
)

object WriteConfig {
  val DefaultRowGroupSize: Long = 128L * 1024 * 1024
}

enum CompressionType:
  case Uncompressed, Snappy, Gzip, Lzo, Brotli, Lz4, Zstd

  def codecName: String = this match
    case Uncompressed => "UNCOMPRESSED"
    case Snappy       => "SNAPPY"
    case Gzip         => "GZIP"
    case Lzo          => "LZO"
    case Brotli       => "BROTLI"
    case Lz4          => "LZ4"
    case Zstd         => "ZSTD"

object CompressionType:

  /** Case-insensitive lookup by CLI name; accepts "none"/"uncompressed" and "gz"/"gzip". */
  def fromString(s: String): Option[CompressionType] = s.toLowerCase match
    case "none" | "uncompressed" => Some(Uncompressed)
    case "snappy"                => Some(Snappy)
    case "gzip" | "gz"           => Some(Gzip)
    case "lzo"                   => Some(Lzo)
    case "brotli"                => Some(Brotli)
    case "lz4"                   => Some(Lz4)
    case "zstd"                  => Some(Zstd)
    case _                       => None

case class ConversionConfig(
    writeConfig: WriteConfig = WriteConfig(),
    maxRows: Option[Long] = None
)
