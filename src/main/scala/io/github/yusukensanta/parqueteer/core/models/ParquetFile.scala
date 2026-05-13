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
    outputFormat: OutputFormat = OutputFormat.Table
)

sealed trait OutputFormat
object OutputFormat {
  case object Table extends OutputFormat
  case object JSON extends OutputFormat
  case object CSV extends OutputFormat
  case object Pretty extends OutputFormat
}

case class WriteConfig(
    compressionType: CompressionType = CompressionType.Snappy,
    rowGroupSize: Long = 128 * 1024 * 1024, // 128MB as default
    pageSize: Long = 1024 * 1024,
    enableDictionary: Boolean = true,
    enableStatistics: Boolean = true
)

sealed trait CompressionType
object CompressionType {
  case object Uncompressed extends CompressionType
  case object Snappy extends CompressionType
  case object Gzip extends CompressionType
  case object Lzo extends CompressionType
  case object Brotli extends CompressionType
  case object Lz4 extends CompressionType
  case object Zstd extends CompressionType
}
