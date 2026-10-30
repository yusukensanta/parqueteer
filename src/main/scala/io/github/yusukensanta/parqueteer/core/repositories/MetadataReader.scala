package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.*
import org.apache.parquet.column.ColumnDescriptor
import org.apache.parquet.column.{Encoding, EncodingStats}
import org.apache.parquet.format
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
      modifiedAt: Option[Instant],
      raw: Option[format.FileMetaData] = None
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
      rowGroups = footer.blocks.zipWithIndex.map { case (block, idx) =>
        rowGroup(
          block,
          idx,
          raw.flatMap(r => Option(r.getRow_groups).map(_.asScala.toList)).flatMap(_.lift(idx)),
          descriptors
        )
      },
      schemaTree = Some(SchemaTypeReader.tree(footer.schema)),
      extended = raw.map(extendedFile)
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

  private def rowGroup(
      block: BlockMetaData,
      index: Int,
      rawGroup: Option[format.RowGroup],
      descriptors: List[ColumnDescriptor]
  ): RowGroupDetail = {
    val chunks = block.getColumns.asScala.toList
    RowGroupDetail(
      index = index,
      rowCount = block.getRowCount,
      startOffset = block.getStartingPos,
      compressedBytes = chunks.map(_.getTotalSize).sum,
      uncompressedBytes = block.getTotalByteSize,
      columns = chunks.zipWithIndex.map { case (chunk, i) =>
        columnChunk(
          chunk,
          rawGroup.flatMap(g => Option(g.getColumns).map(_.asScala.toList)).flatMap(_.lift(i))
        )
      },
      extended = rawGroup.map(extendedRowGroup(block, _, descriptors))
    )
  }

  private def columnChunk(
      chunk: ColumnChunkMetaData,
      rawChunk: Option[format.ColumnChunk]
  ): ColumnChunkDetail = {
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
      hasOffsetIndex = chunk.getOffsetIndexReference != null,
      extended = rawChunk.map(rc => extendedChunk(chunk, Option(rc.getMeta_data)))
    )
  }

  private def extendedFile(raw: format.FileMetaData): ExtendedFileDetail =
    ExtendedFileDetail(
      columnOrders = Option(raw.getColumn_orders).toList.flatMap(_.asScala).map { co =>
        if co.isSetTYPE_ORDER then "TYPE_DEFINED_ORDER" else "UNDEFINED"
      },
      encryptionAlgorithm = Option(raw.getEncryption_algorithm).map { a =>
        if a.isSetAES_GCM_V1 then "AES_GCM_V1"
        else if a.isSetAES_GCM_CTR_V1 then "AES_GCM_CTR_V1"
        else "UNKNOWN"
      },
      hasFooterSigningKey = raw.isSetFooter_signing_key_metadata
    )

  private def extendedRowGroup(
      block: BlockMetaData,
      raw: format.RowGroup,
      descriptors: List[ColumnDescriptor]
  ): ExtendedRowGroupDetail =
    ExtendedRowGroupDetail(
      ordinal = Option.when(raw.isSetOrdinal)(raw.getOrdinal.toInt),
      fileOffset = Option.when(raw.isSetFile_offset)(raw.getFile_offset),
      rowIndexOffset = Option.when(block.getRowIndexOffset >= 0)(block.getRowIndexOffset),
      sortingColumns = Option(raw.getSorting_columns).toList.flatMap(_.asScala).map { sc =>
        SortingColumnDetail(
          column = descriptors
            .lift(sc.getColumn_idx)
            .fold(s"#${sc.getColumn_idx}")(_.getPath.mkString(".")),
          descending = sc.isDescending,
          nullsFirst = sc.isNulls_first
        )
      }
    )

  private def extendedChunk(
      chunk: ColumnChunkMetaData,
      rawMeta: Option[format.ColumnMetaData]
  ): ExtendedChunkDetail = {
    val encStats = Option(chunk.getEncodingStats)
    def counts(
        encodings: EncodingStats => java.util.Set[Encoding],
        pages: (EncodingStats, Encoding) => Int
    ): List[EncodingPageCount] =
      encStats.toList.flatMap { es =>
        encodings(es).asScala.toList
          .map(e => EncodingPageCount(e.name, pages(es, e)))
          .sortBy(_.encoding)
      }
    val rawStats = rawMeta.flatMap(m => Option(m.getStatistics))
    def region(
        ref: org.apache.parquet.internal.hadoop.metadata.IndexReference
    ): Option[FileRegion] =
      Option(ref).map(r => FileRegion(r.getOffset, Some(r.getLength.toLong)))
    ExtendedChunkDetail(
      dictionaryPages = counts(_.getDictionaryEncodings, _.getNumDictionaryPagesEncodedAs(_)),
      dataPages = counts(_.getDataEncodings, _.getNumDataPagesEncodedAs(_)),
      usesV2Pages = encStats.map(_.usesV2Pages),
      distinctCount = rawStats.filter(_.isSetDistinct_count).map(_.getDistinct_count),
      isMinExact = rawStats.filter(_.isSetIs_min_value_exact).map(_.isIs_min_value_exact),
      isMaxExact = rawStats.filter(_.isSetIs_max_value_exact).map(_.isIs_max_value_exact),
      bloomFilter = Option.when(chunk.getBloomFilterOffset >= 0)(
        FileRegion(
          chunk.getBloomFilterOffset,
          Option.when(chunk.getBloomFilterLength >= 0)(chunk.getBloomFilterLength.toLong)
        )
      ),
      columnIndex = region(chunk.getColumnIndexReference),
      offsetIndex = region(chunk.getOffsetIndexReference),
      sizeStatistics = Option(chunk.getSizeStatistics).filter(_.isValid).map { ss =>
        SizeStatisticsDetail(
          unencodedByteArrayDataBytes = Option(ss.getUnencodedByteArrayDataBytes)
            .flatMap(o => Option.when(o.isPresent)(o.get.longValue)),
          repetitionLevelHistogram = ss.getRepetitionLevelHistogram.asScala.toList.map(_.longValue),
          definitionLevelHistogram = ss.getDefinitionLevelHistogram.asScala.toList.map(_.longValue)
        )
      },
      isEncrypted = chunk.isEncrypted
    )
  }
}
