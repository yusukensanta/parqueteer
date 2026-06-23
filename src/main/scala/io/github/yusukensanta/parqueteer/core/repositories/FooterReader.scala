package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.{ColumnInfo, ParquetSchema}
import org.apache.parquet.hadoop.metadata.{BlockMetaData, ParquetMetadata}
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.format.converter.ParquetMetadataConverter
import org.apache.parquet.schema.{LogicalTypeAnnotation, MessageType}
import org.apache.parquet.schema.LogicalTypeAnnotation.*
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.schema.Type.Repetition
import java.io.{ByteArrayInputStream, IOException}
import java.nio.{ByteBuffer, ByteOrder}
import scala.jdk.CollectionConverters.*
import scala.util.Using

private[repositories] object FooterReader {

  private val parquetMagic      = Array[Byte]('P', 'A', 'R', '1')
  private val metadataConverter = new ParquetMetadataConverter()
  private val MaxFooterBytes    = 256 * 1024 * 1024

  def readFooterBytes(inputFile: HadoopInputFile): Array[Byte] =
    Using.resource(inputFile.newStream()) { stream =>
      val fileLen = inputFile.getLength
      if fileLen < 12 then
        throw new IOException(
          s"File too small to be a valid Parquet file (${fileLen} bytes)"
        )
      val tail = new Array[Byte](8)
      stream.seek(fileLen - 8)
      stream.readFully(tail)
      if tail(4) != parquetMagic(0) || tail(5) != parquetMagic(1) ||
        tail(6) != parquetMagic(2) || tail(7) != parquetMagic(3)
      then
        throw new IOException(
          "File does not end with PAR1 magic bytes — not a valid Parquet file"
        )
      val footerLen = ByteBuffer
        .wrap(tail, 0, 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .getInt
      if footerLen <= 0 || footerLen > fileLen - 8 || footerLen > MaxFooterBytes
      then
        throw new IOException(
          s"Invalid Parquet footer length: $footerLen (file length: $fileLen, max: ${MaxFooterBytes}B)"
        )
      stream.seek(fileLen - 8 - footerLen)
      val footerBytes = new Array[Byte](footerLen)
      stream.readFully(footerBytes)
      footerBytes
    }

  def parseFooter(footerBytes: Array[Byte]): ParquetMetadata =
    metadataConverter.readParquetMetadata(
      new ByteArrayInputStream(footerBytes),
      ParquetMetadataConverter.NO_FILTER
    )

  def parseRawMeta(footerBytes: Array[Byte]): (String, String) = {
    val raw = org.apache.parquet.format.Util.readFileMetaData(
      new ByteArrayInputStream(footerBytes)
    )
    (
      if raw.version == 2 then "2.0" else "1.0",
      Option(raw.created_by).getOrElse("")
    )
  }

  def buildParquetSchema(
      msgSchema: MessageType,
      blocks: List[BlockMetaData]
  ): ParquetSchema = {
    val chunkMap = blocks.headOption
      .map(
        _.getColumns.asScala
          .map(c => c.getPath.toDotString -> c)
          .toMap
      )
      .getOrElse(Map.empty)
    val columns = msgSchema.getColumns.asScala.map { col =>
      val colPath = col.getPath.mkString(".")
      val pt      = col.getPrimitiveType
      val chunk   = chunkMap.get(colPath)
      ColumnInfo(
        name = colPath,
        dataType = logicalTypeName(pt.getPrimitiveTypeName, pt.getLogicalTypeAnnotation),
        isOptional = pt.getRepetition == Repetition.OPTIONAL,
        maxDefinitionLevel = col.getMaxDefinitionLevel,
        maxRepetitionLevel = col.getMaxRepetitionLevel,
        compressionType = chunk.map(_.getCodec.name()).getOrElse("UNKNOWN"),
        encodings = chunk
          .map(_.getEncodings.asScala.map(_.name()).toList.sorted.distinct)
          .getOrElse(Nil)
      )
    }.toList
    ParquetSchema(
      columns = columns,
      rowGroupCount = blocks.size.toLong,
      totalRowCount = blocks.map(_.getRowCount).sum
    )
  }

  def logicalTypeName(
      primitive: PrimitiveTypeName,
      annotation: LogicalTypeAnnotation
  ): String =
    if annotation == null then primitive.name()
    else
      annotation match {
        case _: DateLogicalTypeAnnotation => "DATE"
        case ts: TimestampLogicalTypeAnnotation =>
          ts.getUnit match {
            case LogicalTypeAnnotation.TimeUnit.MICROS => "TIMESTAMP_MICROS"
            case LogicalTypeAnnotation.TimeUnit.NANOS  => "TIMESTAMP_NANOS"
            case _                                     => "TIMESTAMP_MILLIS"
          }
        case _: StringLogicalTypeAnnotation => "STRING"
        case _: EnumLogicalTypeAnnotation   => "STRING"
        case _: JsonLogicalTypeAnnotation   => "STRING"
        case dec: DecimalLogicalTypeAnnotation =>
          s"DECIMAL(${dec.getPrecision},${dec.getScale})"
        case _ =>
          primitive match {
            case PrimitiveTypeName.INT96                => "TIMESTAMP(INT96)"
            case PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY => "BINARY"
            case _                                      => primitive.name()
          }
      }
}
