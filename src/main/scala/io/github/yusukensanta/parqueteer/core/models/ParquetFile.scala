package io.github.yusukensanta.parqueteer.core.models

import java.time.Instant

/** Aggregate view of a Parquet file: location, schema, metadata, content, and row groups. */
case class ParquetFile(
    location: StorageLocation,
    schema: Option[ParquetSchema] = None,
    metadata: Option[FileMetadata] = None,
    content: Option[FileContent] = None,
    rowGroups: List[RowGroupInfo] = Nil
)

case class RowGroupInfo(
    index: Int,
    rowCount: Long,
    compressedBytes: Long,
    uncompressedBytes: Long
)

case class FileMetadata(
    fileSize: Long,
    createdAt: Option[Instant],
    modifiedAt: Option[Instant],
    compressionRatio: Option[Double],
    version: String,
    createdBy: Option[String],
    compressionType: Option[String] = None,
    avgRowGroupSizeBytes: Option[Long] = None
)

/** Decoded rows from a Parquet file, with total-row count and partial-read indicator. */
case class FileContent(
    rows: List[Map[String, CellValue]],
    totalRows: Long,
    isPartial: Boolean = false
)
