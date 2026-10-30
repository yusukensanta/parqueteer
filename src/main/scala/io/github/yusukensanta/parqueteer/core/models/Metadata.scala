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
    schemaTree: Option[SchemaNode] = None
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
    columns: List[ColumnChunkDetail]
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
    hasOffsetIndex: Boolean
) {
  def hasDictionary: Boolean = dictionaryPageOffset.isDefined
}
