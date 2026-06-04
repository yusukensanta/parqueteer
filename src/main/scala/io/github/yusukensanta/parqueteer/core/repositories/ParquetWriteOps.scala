package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  CompressionType,
  ParqueteerError
}
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.{LogicalTypeAnnotation, MessageType}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.example.data.Group

private[repositories] object ParquetWriteOps {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)
  private val decimalWarnedOnce =
    new java.util.concurrent.atomic.AtomicBoolean(false)

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
              throw new ParqueteerError.RowSchemaMismatchException(
                s"Column '$key' in input row not found in schema. The schema was inferred from the first batch of rows — all rows must contain a consistent set of columns."
              )
          }
        val fieldType = schema.getType(fieldIndex).asPrimitiveType()
        val isBinaryField =
          fieldType.getPrimitiveTypeName == PrimitiveTypeName.BINARY
        value match {
          case CellValue.Str(s) => group.add(fieldIndex, s)
          case CellValue.Null   => ()
          case CellValue.Bytes(b) =>
            group.add(fieldIndex, Binary.fromConstantByteArray(b))
          case _ if isBinaryField => group.add(fieldIndex, value.display)
          case CellValue.I32(i)   => group.add(fieldIndex, i)
          case CellValue.I64(l)   => group.add(fieldIndex, l)
          case CellValue.F64(d)   => group.add(fieldIndex, d)
          case CellValue.F32(f)   => group.add(fieldIndex, f)
          case CellValue.Bool(b)  => group.add(fieldIndex, b)
          case CellValue.Date(d) =>
            group.add(fieldIndex, Math.toIntExact(d.toEpochDay))
          case CellValue.Ts(i) =>
            val isMicros = Option(fieldType.getLogicalTypeAnnotation).exists {
              case ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation =>
                ts.getUnit == LogicalTypeAnnotation.TimeUnit.MICROS
              case _ => false
            }
            val encoded =
              if (isMicros) i.getEpochSecond * 1_000_000L + i.getNano / 1000L
              else i.toEpochMilli
            group.add(fieldIndex, encoded)
          case CellValue.Dec(bd) =>
            if (decimalWarnedOnce.compareAndSet(false, true))
              logger.warn(
                "Writing DECIMAL as DOUBLE — precision may be lost for values " +
                  "with more than 15 significant digits. Future parqueteer versions " +
                  "will write native Parquet DECIMAL encoding."
              )
            group.add(fieldIndex, bd.toDouble)
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
