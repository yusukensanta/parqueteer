package io.github.yusukensanta.parqueteer.core.models

import java.time.Instant

/**
 * Everything the Parquet footer says about a file, without scanning any data.
 * Unlike [[ParquetSchema]] (first row group only, a handful of fields per
 * column), this keeps the per-row-group, per-column-chunk detail.
 */
case class ParquetMetadataReport(
    file: FileDetail,
    keyValueMetadata: List[(String, String)],
    messageSchema: String,
    columns: List[ColumnSchemaDetail],
    rowGroups: List[RowGroupDetail],
    schemaTree: Option[SchemaNode] = None,
    extended: Option[ExtendedFileDetail] = None
)

/**
 * One node of the schema tree: the root message, a group or a primitive leaf.
 * `physicalType`/`typeLength` are set for leaves only.
 */
case class SchemaNode(
    name: String,
    repetition: Option[String],
    physicalType: Option[String],
    typeLength: Option[Int],
    logicalType: Option[LogicalTypeDetail],
    convertedType: Option[String],
    fieldId: Option[Int],
    children: List[SchemaNode]
)

case class FileDetail(
    fileSize: Long,
    modifiedAt: Option[Instant],
    version: String,
    createdBy: Option[String],
    rowCount: Long,
    rowGroupCount: Int,
    columnCount: Int
)

/** Schema-level facts for one leaf column: physical/logical type, levels, field id. */
case class ColumnSchemaDetail(
    path: String,
    physicalType: String,
    logicalType: Option[String],
    repetition: String,
    maxDefinitionLevel: Int,
    maxRepetitionLevel: Int,
    typeLength: Option[Int],
    fieldId: Option[Int],
    groups: List[GroupInfo] = Nil
)

case class RowGroupDetail(
    index: Int,
    rowCount: Long,
    startOffset: Long,
    compressedBytes: Long,
    uncompressedBytes: Long,
    columns: List[ColumnChunkDetail],
    extended: Option[ExtendedRowGroupDetail] = None
)

/** One column's data inside one row group. */
case class ColumnChunkDetail(
    path: String,
    codec: String,
    encodings: List[String],
    valueCount: Long,
    compressedBytes: Long,
    uncompressedBytes: Long,
    dataPageOffset: Long,
    dictionaryPageOffset: Option[Long],
    nullCount: Option[Long],
    minValue: Option[String],
    maxValue: Option[String],
    hasBloomFilter: Boolean,
    hasColumnIndex: Boolean,
    hasOffsetIndex: Boolean,
    extended: Option[ExtendedChunkDetail] = None,
    pageIndex: Option[PageIndexDetail] = None,
    pages: Option[List[PageHeaderDetail]] = None
) {
  def hasDictionary: Boolean = dictionaryPageOffset.isDefined
}

// ── Extended detail (`metadata --extended`) ────────────────────────────────

/** File-level footer fields that are not needed day to day. */
case class ExtendedFileDetail(
    columnOrders: List[String],
    encryptionAlgorithm: Option[String],
    hasFooterSigningKey: Boolean
)

case class SortingColumnDetail(column: String, descending: Boolean, nullsFirst: Boolean)

case class ExtendedRowGroupDetail(
    ordinal: Option[Int],
    fileOffset: Option[Long],
    rowIndexOffset: Option[Long],
    sortingColumns: List[SortingColumnDetail]
)

/** Number of pages written with a given encoding. */
case class EncodingPageCount(encoding: String, pages: Int)

/** Where an index or bloom filter lives in the file. */
case class FileRegion(offset: Long, length: Option[Long])

/** Per-chunk size statistics: unencoded bytes and level histograms. */
case class SizeStatisticsDetail(
    unencodedByteArrayDataBytes: Option[Long],
    repetitionLevelHistogram: List[Long],
    definitionLevelHistogram: List[Long]
)

case class ExtendedChunkDetail(
    dictionaryPages: List[EncodingPageCount],
    dataPages: List[EncodingPageCount],
    usesV2Pages: Option[Boolean],
    distinctCount: Option[Long],
    isMinExact: Option[Boolean],
    isMaxExact: Option[Boolean],
    bloomFilter: Option[FileRegion],
    columnIndex: Option[FileRegion],
    offsetIndex: Option[FileRegion],
    sizeStatistics: Option[SizeStatisticsDetail],
    isEncrypted: Boolean
)

// ── Page-level detail (`metadata --index`, `metadata --pages`) ─────────────

/** Which optional, costlier parts of the metadata report to read. */
case class MetadataOptions(
    extended: Boolean = false,
    index: Boolean = false,
    pages: Boolean = false,
    columns: Option[List[String]] = None
) {
  def needsFileReader: Boolean = index || pages
}

/**
 * A chunk's column index and offset index, joined per page. Either index may be
 * absent (not every writer emits both), so every per-page field is optional.
 */
case class PageIndexDetail(boundaryOrder: Option[String], pages: List[IndexedPage])

case class IndexedPage(
    page: Int,
    offset: Option[Long],
    compressedBytes: Option[Int],
    firstRowIndex: Option[Long],
    isNullPage: Option[Boolean],
    nullCount: Option[Long],
    minValue: Option[String],
    maxValue: Option[String]
)

/** One page header as stored in the column chunk. */
case class PageHeaderDetail(
    pageType: String,
    offset: Long,
    headerBytes: Int,
    compressedBytes: Int,
    uncompressedBytes: Int,
    valueCount: Option[Int],
    encoding: Option[String],
    rowCount: Option[Int],
    nullCount: Option[Int],
    hasStatistics: Boolean,
    hasCrc: Boolean
)
