package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.*
import org.apache.parquet.column.ColumnDescriptor
import org.apache.parquet.hadoop.metadata.{BlockMetaData, ColumnChunkMetaData}
import org.apache.parquet.schema.MessageType
import java.time.Instant
import scala.jdk.CollectionConverters.*

/**
 * Pure translation of a parsed footer into a [[ParquetMetadataReport]].
 * Separate from HadoopParquetRepository so it can be tested against
 * in-memory footers without any file system involved.
 */
private[repositories] object MetadataReader {

  def build(
      footer: FooterInfo,
      fileSize: Long,
      modifiedAt: Option[Instant]
  ): ParquetMetadataReport = {
    val descriptors = footer.schema.getColumns.asScala.toList
    ParquetMetadataReport(
      file = FileDetail(
        fileSize = fileSize,
        modifiedAt = modifiedAt,
        version = footer.version,
        createdBy = Option(footer.createdBy).filter(_.nonEmpty),
        rowCount = footer.blocks.map(_.getRowCount).sum,
        rowGroupCount = footer.blocks.size,
        columnCount = descriptors.size
      ),
      keyValueMetadata = footer.metadata.getFileMetaData.getKeyValueMetaData.asScala.toList
        .sortBy(_._1),
      messageSchema = footer.schema.toString,
      columns = descriptors.map(columnSchema(footer.schema, _)),
      rowGroups = footer.blocks.zipWithIndex.map { case (block, idx) => rowGroup(block, idx) },
      schemaTree = Some(SchemaTypeReader.tree(footer.schema))
    )
  }

  private def columnSchema(root: MessageType, col: ColumnDescriptor): ColumnSchemaDetail = {
    val pt = col.getPrimitiveType
    ColumnSchemaDetail(
      path = col.getPath.mkString("."),
      physicalType = pt.getPrimitiveTypeName.name,
      logicalType = Option(pt.getLogicalTypeAnnotation).map(_.toString),
      repetition = pt.getRepetition.name,
      maxDefinitionLevel = col.getMaxDefinitionLevel,
      maxRepetitionLevel = col.getMaxRepetitionLevel,
      // Only FIXED_LEN_BYTE_ARRAY carries a meaningful length; others report 0.
      typeLength = Option(pt.getTypeLength).filter(_ > 0),
      fieldId = Option(pt.getId).map(_.intValue),
      groups = SchemaTypeReader.groupsAbove(root, col.getPath)
    )
  }

  private def rowGroup(block: BlockMetaData, index: Int): RowGroupDetail = {
    val chunks = block.getColumns.asScala.toList
    RowGroupDetail(
      index = index,
      rowCount = block.getRowCount,
      startOffset = block.getStartingPos,
      compressedBytes = chunks.map(_.getTotalSize).sum,
      uncompressedBytes = block.getTotalByteSize,
      columns = chunks.map(columnChunk)
    )
  }

  private def columnChunk(chunk: ColumnChunkMetaData): ColumnChunkDetail = {
    val stats = Option(chunk.getStatistics)
    val (minValue, maxValue) = stats
      .filter(s => !s.isEmpty && s.hasNonNullValue)
      .map(s =>
        StatsComputer.computeTypedMinMax(
          List(s),
          chunk.getPrimitiveType.getPrimitiveTypeName,
          chunk.getPrimitiveType.getLogicalTypeAnnotation
        )
      )
      .getOrElse((None, None))
    ColumnChunkDetail(
      path = chunk.getPath.toDotString,
      codec = chunk.getCodec.name,
      encodings = chunk.getEncodings.asScala.map(_.name).toList.sorted,
      valueCount = chunk.getValueCount,
      compressedBytes = chunk.getTotalSize,
      uncompressedBytes = chunk.getTotalUncompressedSize,
      dataPageOffset = chunk.getFirstDataPageOffset,
      dictionaryPageOffset = Option.when(chunk.hasDictionaryPage)(chunk.getDictionaryPageOffset),
      nullCount = stats.filter(_.isNumNullsSet).map(_.getNumNulls),
      minValue = minValue,
      maxValue = maxValue,
      hasBloomFilter = chunk.getBloomFilterOffset >= 0,
      hasColumnIndex = chunk.getColumnIndexReference != null,
      hasOffsetIndex = chunk.getOffsetIndexReference != null
    )
  }
}
