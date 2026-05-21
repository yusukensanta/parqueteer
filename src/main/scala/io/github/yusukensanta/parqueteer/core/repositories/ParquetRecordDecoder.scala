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
import org.apache.parquet.schema.{MessageType, OriginalType}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.column.page.PageReadStore
import org.apache.parquet.io.ColumnIOFactory
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import scala.jdk.CollectionConverters._

private[repositories] object ParquetRecordDecoder {

  def decodeValue(value: Value): Any = value match {
    case NullValue           => null
    case BooleanValue(b)     => b
    case IntValue(i)         => i
    case LongValue(l)        => l
    case FloatValue(f)       => f
    case DoubleValue(d)      => d
    case BinaryValue(binary) => binary.toStringUsingUTF8
    case DateTimeValue(l, _) => java.time.Instant.ofEpochMilli(l).toString
    case DecimalValue(bigInt, fmt) =>
      scala.math.BigDecimal(new java.math.BigDecimal(bigInt, fmt.scale))
    case _ => value.toString
  }

  def convertRecordToMap(record: RowParquetRecord): Map[String, Any] =
    record.iterator.map { case (key, value) => key -> decodeValue(value) }.toMap

  def postProcessTemporalFields(
      row: Map[String, Any],
      schema: MessageType
  ): Map[String, Any] = {
    schema.getFields.asScala.foldLeft(row) { (acc, field) =>
      if (!field.isPrimitive) acc
      else {
        val pt = field.asPrimitiveType()
        val originalType = pt.getOriginalType
        (pt.getPrimitiveTypeName, originalType) match {
          case (PrimitiveTypeName.INT32, OriginalType.DATE) =>
            acc.get(field.getName) match {
              case Some(i: Int) =>
                acc + (field.getName -> java.time.LocalDate
                  .ofEpochDay(i.toLong)
                  .toString)
              case _ => acc
            }
          case _ => acc
        }
      }
    }
  }

  def decodePageStore(
      pageStore: PageReadStore,
      fileSchema: MessageType,
      requestedSchema: MessageType
  ): List[Map[String, Any]] = {
    val columnIO =
      new ColumnIOFactory().getColumnIO(requestedSchema, fileSchema)
    val converter = new GroupRecordConverter(requestedSchema)
    val recordReader = columnIO.getRecordReader(pageStore, converter)
    val rowCount = pageStore.getRowCount
    (0L until rowCount).map { _ =>
      val group = recordReader.read()
      if (group == null) Map.empty[String, Any]
      else decodeGroup(group, requestedSchema)
    }.toList
  }

  def decodeGroup(
      group: Group,
      schema: MessageType
  ): Map[String, Any] = {
    val builder = Map.newBuilder[String, Any]
    var i = 0
    while (i < schema.getFieldCount) {
      if (group.getFieldRepetitionCount(i) > 0 && schema.getType(i).isPrimitive) {
        val name = schema.getType(i).getName
        val fieldType = schema.getType(i).asPrimitiveType()
        val originalType = fieldType.getOriginalType
        val value: Any = fieldType.getPrimitiveTypeName match {
          case PrimitiveTypeName.INT32
              if originalType == OriginalType.DECIMAL =>
            val scale = fieldType.getDecimalMetadata.getScale
            scala.math.BigDecimal(
              new java.math.BigDecimal(
                java.math.BigInteger.valueOf(group.getInteger(i, 0).toLong),
                scale
              )
            )
          case PrimitiveTypeName.INT64
              if originalType == OriginalType.DECIMAL =>
            val scale = fieldType.getDecimalMetadata.getScale
            scala.math.BigDecimal(
              new java.math.BigDecimal(
                java.math.BigInteger.valueOf(group.getLong(i, 0)),
                scale
              )
            )
          case PrimitiveTypeName.INT32 if originalType == OriginalType.DATE =>
            java.time.LocalDate
              .ofEpochDay(group.getInteger(i, 0).toLong)
              .toString
          case PrimitiveTypeName.INT64
              if originalType == OriginalType.TIMESTAMP_MILLIS =>
            java.time.Instant.ofEpochMilli(group.getLong(i, 0)).toString
          case PrimitiveTypeName.INT64
              if originalType == OriginalType.TIMESTAMP_MICROS =>
            val micros = group.getLong(i, 0)
            java.time.Instant
              .ofEpochSecond(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L)
              .toString
          case PrimitiveTypeName.INT32   => group.getInteger(i, 0)
          case PrimitiveTypeName.INT64   => group.getLong(i, 0)
          case PrimitiveTypeName.FLOAT   => group.getFloat(i, 0)
          case PrimitiveTypeName.DOUBLE  => group.getDouble(i, 0)
          case PrimitiveTypeName.BOOLEAN => group.getBoolean(i, 0)
          case PrimitiveTypeName.BINARY =>
            group.getBinary(i, 0).toStringUsingUTF8
          case _ => group.getValueToString(i, 0)
        }
        builder += name -> value
      }
      i += 1
    }
    builder.result()
  }
}
