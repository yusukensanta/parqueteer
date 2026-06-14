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
  private val decimalCoercionWarnedCols =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
  private val longToDoubleWarnedCols =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

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
          case CellValue.Dec(bd) =>
            Option(fieldType.getLogicalTypeAnnotation) match {
              case Some(
                    dec: LogicalTypeAnnotation.DecimalLogicalTypeAnnotation
                  ) =>
                val scale = dec.getScale
                val scaled =
                  bd.setScale(scale, scala.math.BigDecimal.RoundingMode.HALF_UP)
                val unscaled = scaled.underlying().unscaledValue()
                fieldType.getPrimitiveTypeName match {
                  case PrimitiveTypeName.INT32 =>
                    group.add(fieldIndex, unscaled.intValueExact())
                  case PrimitiveTypeName.INT64 =>
                    group.add(fieldIndex, unscaled.longValueExact())
                  case _ =>
                    group.add(
                      fieldIndex,
                      Binary.fromConstantByteArray(unscaled.toByteArray)
                    )
                }
              case _ =>
                if (decimalCoercionWarnedCols.add(key))
                  logger.warn(
                    s"Writing DECIMAL to non-DECIMAL column '$key' as DOUBLE — precision may be lost " +
                      "for values with more than 15 significant digits."
                  )
                group.add(fieldIndex, bd.toDouble)
            }
          case _ if isBinaryField =>
            logger.warn(
              s"Coercing ${value.getClass.getSimpleName} to string for BINARY column '$key' — schema type mismatch."
            )
            group.add(fieldIndex, value.display)
          case CellValue.I32(i) =>
            if (fieldType.getPrimitiveTypeName == PrimitiveTypeName.DOUBLE)
              group.add(fieldIndex, i.toDouble)
            else
              group.add(fieldIndex, i)
          case CellValue.I64(l) =>
            if (fieldType.getPrimitiveTypeName == PrimitiveTypeName.DOUBLE) {
              // Long values outside (-2^53, 2^53) cannot be represented exactly as Double.
              if (
                (l > 9007199254740992L || l < -9007199254740992L) &&
                longToDoubleWarnedCols.add(key)
              )
                logger.warn(
                  s"Column '$key': Long value $l exceeds Double precision range (2^53) — " +
                    "coercing to DOUBLE loses precision. Schema widening produced DOUBLE from a " +
                    "mixed integer/float column; consider using consistent numeric types."
                )
              group.add(fieldIndex, l.toDouble)
            } else
              group.add(fieldIndex, l)
          case CellValue.F64(d) => group.add(fieldIndex, d)
          case CellValue.F32(f) =>
            if (fieldType.getPrimitiveTypeName == PrimitiveTypeName.DOUBLE)
              group.add(fieldIndex, f.toDouble)
            else
              group.add(fieldIndex, f)
          case CellValue.Bool(b) => group.add(fieldIndex, b)
          case CellValue.Date(d) =>
            val fieldName = schema.getType(fieldIndex).getName
            val asInt =
              try Math.toIntExact(d.toEpochDay)
              catch {
                case _: ArithmeticException =>
                  throw new IllegalArgumentException(
                    s"Date value '$d' for column '$fieldName' overflows INT32 epoch-day range (~year 5,881,580 max)"
                  )
              }
            group.add(fieldIndex, asInt)
          case CellValue.Ts(i) =>
            val timeUnit = Option(fieldType.getLogicalTypeAnnotation).collect {
              case ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation =>
                ts.getUnit
            }
            val fieldName = schema.getType(fieldIndex).getName
            val encoded = timeUnit match {
              case Some(LogicalTypeAnnotation.TimeUnit.MICROS) =>
                try
                  Math.addExact(
                    Math.multiplyExact(i.getEpochSecond, 1_000_000L),
                    i.getNano / 1000L
                  )
                catch {
                  case _: ArithmeticException =>
                    throw new IllegalArgumentException(
                      s"Timestamp value '$i' for column '$fieldName' overflows INT64 microsecond range"
                    )
                }
              case Some(LogicalTypeAnnotation.TimeUnit.NANOS) =>
                try
                  Math.addExact(
                    Math.multiplyExact(i.getEpochSecond, 1_000_000_000L),
                    i.getNano.toLong
                  )
                catch {
                  case _: ArithmeticException =>
                    throw new IllegalArgumentException(
                      s"Timestamp value '$i' for column '$fieldName' overflows INT64 nanosecond range"
                    )
                }
              case _ => i.toEpochMilli
            }
            group.add(fieldIndex, encoded)
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
