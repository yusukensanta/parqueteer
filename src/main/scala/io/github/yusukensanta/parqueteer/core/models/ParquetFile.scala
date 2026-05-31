package io.github.yusukensanta.parqueteer.core.models

import java.time.Instant
import scala.concurrent.duration.{Duration, FiniteDuration}

case class ParquetFile(
    location: StorageLocation,
    schema: Option[ParquetSchema] = None,
    metadata: Option[FileMetadata] = None,
    content: Option[FileContent] = None
)

case class ParquetSchema(
    columns: List[ColumnInfo],
    rowGroupCount: Long,
    totalRowCount: Long
)

case class ColumnInfo(
    name: String,
    dataType: String,
    isOptional: Boolean,
    maxDefinitionLevel: Int,
    maxRepetitionLevel: Int,
    compressionType: String
)

case class FileMetadata(
    fileSize: Long,
    createdAt: Option[Instant],
    modifiedAt: Option[Instant],
    compressionRatio: Option[Double],
    version: String,
    createdBy: Option[String]
)

case class FileContent(
    rows: List[Map[String, CellValue]],
    totalRows: Long,
    isPartial: Boolean = false
)

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

case class ColumnStats(
    name: String,
    dataType: String,
    nullCount: Long,
    minValue: Option[String],
    maxValue: Option[String]
)

case class FileStats(
    columns: List[ColumnStats],
    totalRows: Long,
    rowGroupCount: Long
)

enum SchemaMode:
  case Strict, Union

case class ValidationResult(
    isValid: Boolean,
    issues: List[String]
)

case class ColumnChange(
    name: String,
    fromType: String,
    toType: String,
    fromOptional: Boolean,
    toOptional: Boolean
)

case class SchemaDiff(
    added: List[ColumnInfo],
    removed: List[ColumnInfo],
    changed: List[ColumnChange],
    unchanged: List[String]
) {
  def identical: Boolean = added.isEmpty && removed.isEmpty && changed.isEmpty
}

case class ConversionConfig(
    writeConfig: WriteConfig = WriteConfig(),
    maxRows: Option[Long] = None
)

case class FieldSummary(name: String, dataType: String, isOptional: Boolean)
