package io.github.yusukensanta.parqueteer.core.models

import java.time.Instant
import com.github.mjakubowski84.parquet4s.Filter

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
    rows: List[Map[String, Any]],
    totalRows: Long,
    isPartial: Boolean = false
)

case class ReadConfig(
    maxRows: Option[Long] = None,
    columns: Option[List[String]] = None,
    filter: Option[String] = None,
    parsedFilter: Option[Filter] = None,
    outputFormat: OutputFormat = OutputFormat.Table,
    parallelism: Int = 1,
    streamingMode: Boolean = false
)

enum OutputFormat:
  case Table, JSON, CSV, Pretty, Markdown, NDJSON

case class WriteConfig(
    compressionType: CompressionType = CompressionType.Snappy,
    rowGroupSize: Long = WriteConfig.DefaultRowGroupSize,
    pageSize: Long = 1024 * 1024,
    enableDictionary: Boolean = true,
    enableStatistics: Boolean = true
)

object WriteConfig {
  val DefaultRowGroupSize: Long = 128L * 1024 * 1024
}

enum CompressionType:
  case Uncompressed, Snappy, Gzip, Lzo, Brotli, Lz4, Zstd

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

case class ConversionConfig(writeConfig: WriteConfig = WriteConfig())
