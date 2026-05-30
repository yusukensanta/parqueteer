package io.github.yusukensanta.parqueteer.core.repositories

import com.github.mjakubowski84.parquet4s.{
  RowParquetRecord,
  Value,
  NullValue,
  BooleanValue,
  IntValue,
  LongValue,
  FloatValue,
  DoubleValue,
  BinaryValue,
  DateTimeValue,
  DecimalValue
}
import io.github.yusukensanta.parqueteer.core.models.CellValue
import org.apache.parquet.schema.{MessageType, OriginalType}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.column.page.PageReadStore
import org.apache.parquet.io.ColumnIOFactory
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import scala.jdk.CollectionConverters._

private[repositories] object ParquetRecordDecoder {

  def decodeValue(value: Value): CellValue = value match {
    case NullValue           => CellValue.Null
    case BooleanValue(b)     => CellValue.Bool(b)
    case IntValue(i)         => CellValue.I32(i)
    case LongValue(l)        => CellValue.I64(l)
    case FloatValue(f)       => CellValue.F32(f)
    case DoubleValue(d)      => CellValue.F64(d)
    case BinaryValue(bin)    => CellValue.Str(bin.toStringUsingUTF8)
    case DateTimeValue(l, _) => CellValue.I64(l)
    case DecimalValue(bigInt, fmt) =>
      CellValue.Dec(
        scala.math.BigDecimal(new java.math.BigDecimal(bigInt, fmt.scale))
      )
    case _ => CellValue.Str(value.toString)
  }

  def convertRecordToMap(record: RowParquetRecord): Map[String, CellValue] =
    record.iterator.map { case (key, value) => key -> decodeValue(value) }.toMap

  def postProcessTemporalFields(
      row: Map[String, CellValue],
      schema: MessageType
  ): Map[String, CellValue] =
    schema.getFields.asScala.foldLeft(row) { (acc, field) =>
      if (!field.isPrimitive) acc
      else {
        val pt = field.asPrimitiveType()
        val name = field.getName
        (pt.getPrimitiveTypeName, pt.getOriginalType) match {
          case (PrimitiveTypeName.INT32, OriginalType.DATE) =>
            replaceTyped[CellValue.I32](acc, name)(i =>
              CellValue.Date(java.time.LocalDate.ofEpochDay(i.i.toLong))
            )
          case (PrimitiveTypeName.INT64, OriginalType.TIMESTAMP_MILLIS) =>
            replaceTyped[CellValue.I64](acc, name)(l =>
              CellValue.Ts(java.time.Instant.ofEpochMilli(l.l))
            )
          case (PrimitiveTypeName.INT64, OriginalType.TIMESTAMP_MICROS) =>
            replaceTyped[CellValue.I64](acc, name)(l =>
              CellValue.Ts(
                java.time.Instant.ofEpochSecond(
                  l.l / 1_000_000L,
                  (l.l % 1_000_000L) * 1_000L
                )
              )
            )
          case _ => acc
        }
      }
    }

  /** If `row(name)` exists and matches the expected variant, replace it with
    * `f(matched)`; otherwise return the row unchanged.
    */
  private inline def replaceTyped[T <: CellValue](
      row: Map[String, CellValue],
      name: String
  )(
      f: T => CellValue
  )(using ct: scala.reflect.ClassTag[T]): Map[String, CellValue] =
    row.get(name) match {
      case Some(ct(t)) => row + (name -> f(t))
      case _           => row
    }

  def decodePageStore(
      pageStore: PageReadStore,
      fileSchema: MessageType,
      requestedSchema: MessageType
  ): List[Map[String, CellValue]] = {
    val columnIO =
      new ColumnIOFactory().getColumnIO(requestedSchema, fileSchema)
    val converter = new GroupRecordConverter(requestedSchema)
    val recordReader = columnIO.getRecordReader(pageStore, converter)
    val rowCount = pageStore.getRowCount
    (0L until rowCount).map { _ =>
      val group = recordReader.read()
      if (group == null) Map.empty[String, CellValue]
      else decodeGroup(group, requestedSchema)
    }.toList
  }

  def decodeGroup(
      group: Group,
      schema: MessageType
  ): Map[String, CellValue] = {
    val builder = Map.newBuilder[String, CellValue]
    var i = 0
    while (i < schema.getFieldCount) {
      if (
        group.getFieldRepetitionCount(i) > 0 && schema.getType(i).isPrimitive
      ) {
        val name = schema.getType(i).getName
        val fieldType = schema.getType(i).asPrimitiveType()
        val originalType = fieldType.getOriginalType
        val value: CellValue = fieldType.getPrimitiveTypeName match {
          case PrimitiveTypeName.INT32
              if originalType == OriginalType.DECIMAL =>
            val scale = fieldType.getDecimalMetadata.getScale
            CellValue.Dec(
              scala.math.BigDecimal(
                new java.math.BigDecimal(
                  java.math.BigInteger.valueOf(group.getInteger(i, 0).toLong),
                  scale
                )
              )
            )
          case PrimitiveTypeName.INT64
              if originalType == OriginalType.DECIMAL =>
            val scale = fieldType.getDecimalMetadata.getScale
            CellValue.Dec(
              scala.math.BigDecimal(
                new java.math.BigDecimal(
                  java.math.BigInteger.valueOf(group.getLong(i, 0)),
                  scale
                )
              )
            )
          case PrimitiveTypeName.INT32 if originalType == OriginalType.DATE =>
            CellValue.Date(
              java.time.LocalDate.ofEpochDay(group.getInteger(i, 0).toLong)
            )
          case PrimitiveTypeName.INT64
              if originalType == OriginalType.TIMESTAMP_MILLIS =>
            CellValue.Ts(java.time.Instant.ofEpochMilli(group.getLong(i, 0)))
          case PrimitiveTypeName.INT64
              if originalType == OriginalType.TIMESTAMP_MICROS =>
            val micros = group.getLong(i, 0)
            CellValue.Ts(
              java.time.Instant
                .ofEpochSecond(
                  micros / 1_000_000L,
                  (micros % 1_000_000L) * 1_000L
                )
            )
          case PrimitiveTypeName.INT32  => CellValue.I32(group.getInteger(i, 0))
          case PrimitiveTypeName.INT64  => CellValue.I64(group.getLong(i, 0))
          case PrimitiveTypeName.FLOAT  => CellValue.F32(group.getFloat(i, 0))
          case PrimitiveTypeName.DOUBLE => CellValue.F64(group.getDouble(i, 0))
          case PrimitiveTypeName.BOOLEAN =>
            CellValue.Bool(group.getBoolean(i, 0))
          case PrimitiveTypeName.BINARY =>
            CellValue.Str(group.getBinary(i, 0).toStringUsingUTF8)
          case _ => CellValue.Str(group.getValueToString(i, 0))
        }
        builder += name -> value
      }
      i += 1
    }
    builder.result()
  }
}
