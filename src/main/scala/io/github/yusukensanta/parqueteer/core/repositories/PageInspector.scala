package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.*
import org.apache.parquet.column.statistics.Statistics
import org.apache.parquet.format.{PageHeader, PageType, Util}
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData
import org.apache.parquet.io.InputFile
import scala.util.Try

/**
 * Reads the parts of a column chunk that live outside the footer: the column
 * index / offset index (page-level statistics and locations) and the page
 * headers themselves. Needs the file, unlike [[MetadataReader]].
 */
private[repositories] object PageInspector {

  /** Column index and offset index of `chunk`, joined per page; `None` if it has neither. */
  def pageIndex(reader: ParquetFileReader, chunk: ColumnChunkMetaData): Option[PageIndexDetail] = {
    val column = Try(Option(reader.readColumnIndex(chunk))).toOption.flatten
    val offset = Try(Option(reader.readOffsetIndex(chunk))).toOption.flatten
    if column.isEmpty && offset.isEmpty then None
    else {
      val pageCount =
        offset.map(_.getPageCount).orElse(column.map(_.getNullPages.size)).getOrElse(0)
      val pages = (0 until pageCount).toList.map { i =>
        val isNull = column.map(_.getNullPages.get(i).booleanValue)
        val (min, max) = column match {
          case Some(ci) if !isNull.contains(true) =>
            typedMinMax(chunk, ci.getMinValues.get(i), ci.getMaxValues.get(i), nullCount(ci, i))
          case _ => (None, None)
        }
        IndexedPage(
          page = i,
          offset = offset.map(_.getOffset(i)),
          compressedBytes = offset.map(_.getCompressedPageSize(i)),
          firstRowIndex = offset.map(_.getFirstRowIndex(i)),
          isNullPage = isNull,
          nullCount = column.flatMap(nullCount(_, i)),
          minValue = min,
          maxValue = max
        )
      }
      Some(PageIndexDetail(column.flatMap(c => Option(c.getBoundaryOrder)).map(_.name), pages))
    }
  }

  private def nullCount(ci: org.apache.parquet.internal.column.columnindex.ColumnIndex, i: Int) =
    Option(ci.getNullCounts).filter(_.size > i).map(_.get(i).longValue)

  // Reuses the chunk-level min/max formatter by wrapping the page's raw bytes
  // in a Statistics object, so page values render exactly like chunk values.
  private def typedMinMax(
      chunk: ColumnChunkMetaData,
      min: java.nio.ByteBuffer,
      max: java.nio.ByteBuffer,
      nulls: Option[Long]
  ): (Option[String], Option[String]) = {
    def bytes(b: java.nio.ByteBuffer) = {
      val dup = b.duplicate()
      val arr = new Array[Byte](dup.remaining)
      dup.get(arr)
      arr
    }
    Try {
      val pt      = chunk.getPrimitiveType
      val builder = Statistics.getBuilderForReading(pt).withMin(bytes(min)).withMax(bytes(max))
      val stats   = nulls.fold(builder)(builder.withNumNulls).build()
      StatsComputer.computeTypedMinMax(
        List(stats),
        pt.getPrimitiveTypeName,
        pt.getLogicalTypeAnnotation
      )
    }.getOrElse((None, None))
  }

  /**
   * Walks the chunk's byte range reading each page header and skipping its
   * payload, so no page is decompressed.
   */
  def pageHeaders(file: InputFile, chunk: ColumnChunkMetaData): List[PageHeaderDetail] = {
    val start = chunk.getStartingPos
    val end   = start + chunk.getTotalSize
    val out   = List.newBuilder[PageHeaderDetail]
    val in    = file.newStream()
    try {
      in.seek(start)
      var pos = in.getPos
      while pos < end do {
        val header = Util.readPageHeader(in)
        val after  = in.getPos
        out += describe(header, pos, (after - pos).toInt)
        in.seek(after + header.getCompressed_page_size)
        pos = in.getPos
      }
    } finally in.close()
    out.result()
  }

  private def describe(h: PageHeader, offset: Long, headerBytes: Int): PageHeaderDetail = {
    val base = PageHeaderDetail(
      pageType = h.getType.name,
      offset = offset,
      headerBytes = headerBytes,
      compressedBytes = h.getCompressed_page_size,
      uncompressedBytes = h.getUncompressed_page_size,
      valueCount = None,
      encoding = None,
      rowCount = None,
      nullCount = None,
      hasStatistics = false,
      hasCrc = h.isSetCrc
    )
    h.getType match {
      case PageType.DATA_PAGE =>
        val d = h.getData_page_header
        base.copy(
          valueCount = Some(d.getNum_values),
          encoding = Some(d.getEncoding.name),
          hasStatistics = d.isSetStatistics
        )
      case PageType.DATA_PAGE_V2 =>
        val d = h.getData_page_header_v2
        base.copy(
          valueCount = Some(d.getNum_values),
          encoding = Some(d.getEncoding.name),
          rowCount = Some(d.getNum_rows),
          nullCount = Some(d.getNum_nulls),
          hasStatistics = d.isSetStatistics
        )
      case PageType.DICTIONARY_PAGE =>
        val d = h.getDictionary_page_header
        base.copy(valueCount = Some(d.getNum_values), encoding = Some(d.getEncoding.name))
      case _ => base
    }
  }

  /** Restricts to the requested columns (dot paths); `None` keeps every column. */
  def selected(chunk: ColumnChunkMetaData, columns: Option[List[String]]): Boolean =
    columns.forall(_.contains(chunk.getPath.toDotString))
}
