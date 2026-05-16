package io.github.yusukensanta.parqueteer.core.models

import java.time.Instant

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
