package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.CompressionType
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.example.data.Group

private[repositories] object ParquetWriteOps {

  def writeRowToGroup(
      group: Group,
      row: Map[String, Any],
      schema: MessageType
  ): Unit = {
    row.foreach { case (key, value) =>
      val fieldIndex = schema.getFieldIndex(key)
      if (fieldIndex >= 0 && value != null) {
        val fieldTypeName =
          schema.getType(fieldIndex).asPrimitiveType().getPrimitiveTypeName
        value match {
          case i: Int =>
            fieldTypeName match {
              case PrimitiveTypeName.INT64  => group.add(fieldIndex, i.toLong)
              case PrimitiveTypeName.DOUBLE => group.add(fieldIndex, i.toDouble)
              case PrimitiveTypeName.FLOAT  => group.add(fieldIndex, i.toFloat)
              case _                        => group.add(fieldIndex, i)
            }
          case l: Long =>
            fieldTypeName match {
              case PrimitiveTypeName.DOUBLE => group.add(fieldIndex, l.toDouble)
              case PrimitiveTypeName.FLOAT  => group.add(fieldIndex, l.toFloat)
              case _                        => group.add(fieldIndex, l)
            }
          case d: Double  => group.add(fieldIndex, d)
          case f: Float   => group.add(fieldIndex, f)
          case b: Boolean => group.add(fieldIndex, b)
          case s: String  => group.add(fieldIndex, s)
          case date: java.time.LocalDate =>
            group.add(fieldIndex, date.toEpochDay.toInt)
          case ts: java.time.Instant => group.add(fieldIndex, ts.toEpochMilli)
          case other                 => group.add(fieldIndex, other.toString)
        }
      }
    }
  }

  def convertCompressionType(
      compressionType: CompressionType
  ): CompressionCodecName = {
    compressionType match {
      case CompressionType.Uncompressed => CompressionCodecName.UNCOMPRESSED
      case CompressionType.Snappy       => CompressionCodecName.SNAPPY
      case CompressionType.Gzip         => CompressionCodecName.GZIP
      case CompressionType.Lzo          => CompressionCodecName.LZO
      case CompressionType.Brotli       => CompressionCodecName.BROTLI
      case CompressionType.Lz4          => CompressionCodecName.LZ4
      case CompressionType.Zstd         => CompressionCodecName.ZSTD
    }
  }
}
