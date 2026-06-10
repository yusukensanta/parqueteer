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
import org.apache.parquet.schema.{MessageType, LogicalTypeAnnotation}
import java.time.temporal.ChronoUnit
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.column.page.PageReadStore
import org.apache.parquet.io.ColumnIOFactory
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import scala.jdk.CollectionConverters._

private[repositories] object ParquetRecordDecoder {
  private val logger =
    org.slf4j.LoggerFactory.getLogger(getClass)
  private val warnedVariants =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  private def epochPlusSafe(l: Long, unit: ChronoUnit): CellValue =
    try CellValue.Ts(java.time.Instant.EPOCH.plus(l, unit))
    catch {
      case _: ArithmeticException | _: java.time.DateTimeException =>
        logger.warn(
          s"Timestamp value $l $unit overflows Instant range — falling back to raw INT64"
        )
        CellValue.I64(l)
    }

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
    case other =>
      val cls = other.getClass.getName
      if (warnedVariants.add(cls))
        logger.warn(
          s"Unknown parquet4s Value variant $cls — falling back to toString. " +
            "Upgrade parqueteer to add explicit support."
        )
      CellValue.Str(other.toString)
  }

  def convertRecordToMap(record: RowParquetRecord): Map[String, CellValue] =
    record.iterator
      .map { case (key, value) => key -> decodeValue(value) }
      .to(scala.collection.immutable.ListMap)

  /** Pre-compute which BINARY columns carry raw bytes (no string annotation).
    * Call once per file/schema and pass to convertRecordToMapWithSchema to
    * avoid rebuilding this Set for every row.
    */
  def rawBinaryFieldsFor(schema: MessageType): Set[String] =
    schema.getFields.asScala.collect {
      case f
          if f.isPrimitive &&
            f.asPrimitiveType().getPrimitiveTypeName ==
            PrimitiveTypeName.BINARY &&
            !(Option(f.asPrimitiveType().getLogicalTypeAnnotation) match {
              case Some(
                    _: LogicalTypeAnnotation.StringLogicalTypeAnnotation |
                    _: LogicalTypeAnnotation.EnumLogicalTypeAnnotation |
                    _: LogicalTypeAnnotation.JsonLogicalTypeAnnotation
                  ) =>
                true
              case _ => false
            }) =>
        f.getName
    }.toSet

  // Schema-aware variant: unannotated BINARY fields are decoded as Bytes, not Str.
  // Mirrors the decodeGroup logic used by the parallel read path.
  def convertRecordToMapWithSchema(
      record: RowParquetRecord,
      rawBinaryFields: Set[String]
  ): Map[String, CellValue] =
    record.iterator
      .map { case (key, value) =>
        val cell =
          if (rawBinaryFields.contains(key))
            value match {
              case BinaryValue(bin) => CellValue.Bytes(bin.getBytes)
              case _                => decodeValue(value)
            }
          else decodeValue(value)
        key -> cell
      }
      .to(scala.collection.immutable.ListMap)

  def convertRecordToMapWithSchema(
      record: RowParquetRecord,
      schema: MessageType
  ): Map[String, CellValue] =
    convertRecordToMapWithSchema(record, rawBinaryFieldsFor(schema))

  /** Pre-compute a per-column transformer from the schema. Call once per file
    * and pass to applyTemporalTransformer for each row to avoid O(N×K) map
    * rebuilds (K = temporal column count, N = row count).
    */
  def buildTemporalTransformer(
      schema: MessageType
  ): Map[String, CellValue => CellValue] =
    schema.getFields.asScala
      .filter(_.isPrimitive)
      .flatMap { field =>
        val pt = field.asPrimitiveType()
        val name = field.getName
        Option(pt.getLogicalTypeAnnotation).flatMap {
          case _: LogicalTypeAnnotation.DateLogicalTypeAnnotation =>
            Some(
              name -> ((v: CellValue) =>
                v match {
                  case CellValue.I32(i) =>
                    CellValue.Date(java.time.LocalDate.ofEpochDay(i.toLong))
                  case other => other
                }
              )
            )
          case ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation
              if ts.getUnit == LogicalTypeAnnotation.TimeUnit.MILLIS =>
            Some(
              name -> ((v: CellValue) =>
                v match {
                  case CellValue.I64(l) =>
                    CellValue.Ts(java.time.Instant.ofEpochMilli(l))
                  case other => other
                }
              )
            )
          case ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation
              if ts.getUnit == LogicalTypeAnnotation.TimeUnit.MICROS =>
            Some(
              name -> ((v: CellValue) =>
                v match {
                  case CellValue.I64(l) => epochPlusSafe(l, ChronoUnit.MICROS)
                  case other            => other
                }
              )
            )
          case ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation
              if ts.getUnit == LogicalTypeAnnotation.TimeUnit.NANOS =>
            Some(
              name -> ((v: CellValue) =>
                v match {
                  case CellValue.I64(l) => epochPlusSafe(l, ChronoUnit.NANOS)
                  case other            => other
                }
              )
            )
          case _ => None
        }
      }
      .toMap

  def applyTemporalTransformer(
      row: Map[String, CellValue],
      transformer: Map[String, CellValue => CellValue]
  ): Map[String, CellValue] =
    if (transformer.isEmpty) row
    else row.map { case (k, v) => k -> transformer.get(k).fold(v)(_(v)) }

  def postProcessTemporalFields(
      row: Map[String, CellValue],
      schema: MessageType
  ): Map[String, CellValue] =
    applyTemporalTransformer(row, buildTemporalTransformer(schema))

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
    val result = List.newBuilder[Map[String, CellValue]]
    var i = 0L
    while (i < rowCount) {
      val group = recordReader.read()
      if (group != null) result += decodeGroup(group, requestedSchema)
      i += 1
    }
    result.result()
  }

  def decodeGroup(
      group: Group,
      schema: MessageType
  ): Map[String, CellValue] = {
    val builder =
      scala.collection.immutable.ListMap.newBuilder[String, CellValue]
    var i = 0
    while (i < schema.getFieldCount) {
      if (
        group.getFieldRepetitionCount(i) > 0 && !schema.getType(i).isPrimitive
      ) {
        val name = schema.getType(i).getName
        if (warnedVariants.add(s"nested:$name"))
          logger.warn(
            s"Column '$name' is a nested group type — nested types are not yet " +
              "supported; emitting Null. Upgrade parqueteer to add explicit support."
          )
        builder += name -> CellValue.Null
      } else if (
        group.getFieldRepetitionCount(i) == 0 && schema.getType(i).isPrimitive
      ) {
        builder += schema.getType(i).getName -> CellValue.Null
      } else if (schema.getType(i).isPrimitive) {
        val name = schema.getType(i).getName
        val repCount = group.getFieldRepetitionCount(i)
        if (repCount > 1 && warnedVariants.add(s"repeated:$name"))
          logger.warn(
            s"Column '$name' has $repCount repeated values — only the first is returned. " +
              "Repeated primitive fields are not yet fully supported."
          )
        val fieldType = schema.getType(i).asPrimitiveType()
        val logicalType = Option(fieldType.getLogicalTypeAnnotation)
        val value: CellValue =
          (fieldType.getPrimitiveTypeName, logicalType) match {
            case (
                  PrimitiveTypeName.INT32,
                  Some(dec: LogicalTypeAnnotation.DecimalLogicalTypeAnnotation)
                ) =>
              CellValue.Dec(
                scala.math.BigDecimal(
                  new java.math.BigDecimal(
                    java.math.BigInteger.valueOf(group.getInteger(i, 0).toLong),
                    dec.getScale
                  )
                )
              )
            case (
                  PrimitiveTypeName.INT64,
                  Some(dec: LogicalTypeAnnotation.DecimalLogicalTypeAnnotation)
                ) =>
              CellValue.Dec(
                scala.math.BigDecimal(
                  new java.math.BigDecimal(
                    java.math.BigInteger.valueOf(group.getLong(i, 0)),
                    dec.getScale
                  )
                )
              )
            case (
                  PrimitiveTypeName.INT32,
                  Some(_: LogicalTypeAnnotation.DateLogicalTypeAnnotation)
                ) =>
              CellValue.Date(
                java.time.LocalDate.ofEpochDay(group.getInteger(i, 0).toLong)
              )
            case (
                  PrimitiveTypeName.INT64,
                  Some(ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation)
                ) if ts.getUnit == LogicalTypeAnnotation.TimeUnit.MILLIS =>
              CellValue.Ts(java.time.Instant.ofEpochMilli(group.getLong(i, 0)))
            case (
                  PrimitiveTypeName.INT64,
                  Some(ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation)
                ) if ts.getUnit == LogicalTypeAnnotation.TimeUnit.MICROS =>
              epochPlusSafe(group.getLong(i, 0), ChronoUnit.MICROS)
            case (
                  PrimitiveTypeName.INT64,
                  Some(ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation)
                ) if ts.getUnit == LogicalTypeAnnotation.TimeUnit.NANOS =>
              epochPlusSafe(group.getLong(i, 0), ChronoUnit.NANOS)
            case (PrimitiveTypeName.INT32, _) =>
              CellValue.I32(group.getInteger(i, 0))
            case (PrimitiveTypeName.INT64, _) =>
              CellValue.I64(group.getLong(i, 0))
            case (PrimitiveTypeName.FLOAT, _) =>
              CellValue.F32(group.getFloat(i, 0))
            case (PrimitiveTypeName.DOUBLE, _) =>
              CellValue.F64(group.getDouble(i, 0))
            case (PrimitiveTypeName.BOOLEAN, _) =>
              CellValue.Bool(group.getBoolean(i, 0))
            case (
                  PrimitiveTypeName.BINARY,
                  Some(dec: LogicalTypeAnnotation.DecimalLogicalTypeAnnotation)
                ) =>
              CellValue.Dec(
                scala.math.BigDecimal(
                  new java.math.BigDecimal(
                    new java.math.BigInteger(group.getBinary(i, 0).getBytes),
                    dec.getScale
                  )
                )
              )
            case (
                  PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY,
                  Some(dec: LogicalTypeAnnotation.DecimalLogicalTypeAnnotation)
                ) =>
              CellValue.Dec(
                scala.math.BigDecimal(
                  new java.math.BigDecimal(
                    new java.math.BigInteger(group.getBinary(i, 0).getBytes),
                    dec.getScale
                  )
                )
              )
            // Only STRING/ENUM/JSON-annotated BINARY is valid UTF-8 text; raw BINARY
            // must be returned as Bytes to avoid corrupting non-UTF-8 data on round-trip.
            case (
                  PrimitiveTypeName.BINARY,
                  Some(
                    _: LogicalTypeAnnotation.StringLogicalTypeAnnotation |
                    _: LogicalTypeAnnotation.EnumLogicalTypeAnnotation |
                    _: LogicalTypeAnnotation.JsonLogicalTypeAnnotation
                  )
                ) =>
              CellValue.Str(group.getBinary(i, 0).toStringUsingUTF8)
            case (PrimitiveTypeName.BINARY, _) =>
              CellValue.Bytes(group.getBinary(i, 0).getBytes)
            case _ => CellValue.Str(group.getValueToString(i, 0))
          }
        builder += name -> value
      }
      i += 1
    }
    builder.result()
  }
}
