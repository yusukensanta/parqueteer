package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  CompressionType
}
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.MessageType
import org.apache.parquet.example.data.Group

private[repositories] object ParquetWriteOps {

  def writeRowToGroup(
      group: Group,
      row: Map[String, CellValue],
      schema: MessageType
  ): Unit = {
    row.foreach { case (key, value) =>
      if (value != CellValue.Null) {
        val fieldIndex =
          try schema.getFieldIndex(key)
          catch {
            case _: org.apache.parquet.io.InvalidRecordException =>
              throw new IllegalArgumentException(
                s"Column '$key' in input row not found in schema. The schema was inferred from the first batch of rows — all rows must contain a consistent set of columns."
              )
          }
        value match {
          case CellValue.I32(i)  => group.add(fieldIndex, i)
          case CellValue.I64(l)  => group.add(fieldIndex, l)
          case CellValue.F64(d)  => group.add(fieldIndex, d)
          case CellValue.F32(f)  => group.add(fieldIndex, f)
          case CellValue.Bool(b) => group.add(fieldIndex, b)
          case CellValue.Str(s)  => group.add(fieldIndex, s)
          case CellValue.Date(d) => group.add(fieldIndex, d.toEpochDay.toInt)
          case CellValue.Ts(i)   => group.add(fieldIndex, i.toEpochMilli)
          case CellValue.Dec(bd) => group.add(fieldIndex, bd.toString)
          case CellValue.Bytes(b) =>
            group.add(fieldIndex, Binary.fromConstantByteArray(b))
          case CellValue.Null => ()
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
