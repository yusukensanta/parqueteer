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
  DecimalValue,
  ListParquetRecord
}
import io.github.yusukensanta.parqueteer.core.models.CellValue
import org.apache.parquet.schema.{MessageType, LogicalTypeAnnotation, GroupType}
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
    // A repeated primitive field (no LIST annotation) arrives as a ListParquetRecord.
    // Take the first element to match the parallel decodeGroup path behaviour.
    case list: ListParquetRecord =>
      val sz = list.size
      if (sz > 1 && warnedVariants.add("ListParquetRecord.multivalue"))
        logger.warn(
          s"A repeated primitive field arrived as ListParquetRecord with $sz values — " +
            "only the first element is returned. Repeated primitive fields are not yet fully supported."
        )
      list.headOption.fold[CellValue](CellValue.Null)(decodeValue)
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

  def int96FieldsFor(schema: MessageType): Set[String] =
    schema.getFields.asScala.collect {
      case f
          if f.isPrimitive &&
            f.asPrimitiveType()
              .getPrimitiveTypeName == PrimitiveTypeName.INT96 =>
        f.getName
    }.toSet

  // 12-byte Spark/Hive INT96 layout: bytes 0-7 = nanos of day (int64 LE), bytes 8-11 = Julian
  // day (int32 LE). Julian day 2440588 = 1970-01-01. Shared by both sequential and parallel paths.
  private[repositories] def decodeInt96Binary(bytes: Array[Byte]): CellValue = {
    val buf =
      java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val nanosOfDay = buf.getLong(0)
    val julianDay = buf.getInt(8).toLong - 2440588L
    val totalNanos =
      try
        Some(
          Math.addExact(
            Math.multiplyExact(julianDay, 86400_000_000_000L),
            nanosOfDay
          )
        )
      catch {
        case _: ArithmeticException =>
          logger.warn(
            s"INT96 Julian-day arithmetic overflow (julianDay=$julianDay, nanosOfDay=$nanosOfDay) — emitting Null"
          )
          None
      }
    totalNanos.fold[CellValue](CellValue.Null)(
      epochPlusSafe(_, ChronoUnit.NANOS)
    )
  }

  // Schema-aware variant: unannotated BINARY fields decoded as Bytes; INT96 fields decoded
  // directly from raw bytes (parquet4s delivers INT96 as BinaryValue, not DateTimeValue).
  def convertRecordToMapWithSchema(
      record: RowParquetRecord,
      rawBinaryFields: Set[String],
      int96Fields: Set[String] = Set.empty
  ): Map[String, CellValue] =
    record.iterator
      .map { case (key, value) =>
        val cell =
          if (int96Fields.contains(key))
            value match {
              case BinaryValue(bin) => decodeInt96Binary(bin.getBytes)
              case _                => decodeValue(value)
            }
          else if (rawBinaryFields.contains(key))
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
    convertRecordToMapWithSchema(
      record,
      rawBinaryFieldsFor(schema),
      int96FieldsFor(schema)
    )

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
        Option(pt.getLogicalTypeAnnotation) match {
          case Some(_: LogicalTypeAnnotation.DateLogicalTypeAnnotation) =>
            Some(
              name -> ((v: CellValue) =>
                v match {
                  case CellValue.I32(i) =>
                    CellValue.Date(java.time.LocalDate.ofEpochDay(i.toLong))
                  case other => other
                }
              )
            )
          case Some(ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation)
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
          case Some(ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation)
              if ts.getUnit == LogicalTypeAnnotation.TimeUnit.MICROS =>
            Some(
              name -> ((v: CellValue) =>
                v match {
                  case CellValue.I64(l) => epochPlusSafe(l, ChronoUnit.MICROS)
                  case other            => other
                }
              )
            )
          case Some(ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation)
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

  // Standard Parquet LIST layout: outer group (LIST) → repeated group (list) → element.
  // Extracts all elements and returns CellValue.Str("[e1, e2, ...]").
  private def decodeListField(
      group: Group,
      fieldIndex: Int,
      listGroupType: GroupType
  ): CellValue = {
    val outerGroup = group.getGroup(fieldIndex, 0)
    val elementCount = outerGroup.getFieldRepetitionCount(0)
    if (elementCount == 0) return CellValue.Str("[]")
    val repeatedType = listGroupType.getType(0).asGroupType()
    val elemType = repeatedType.getType(0)
    val elements = (0 until elementCount).map { j =>
      val wrapper = outerGroup.getGroup(0, j)
      if (wrapper.getFieldRepetitionCount(0) > 0 && elemType.isPrimitive)
        wrapper.getValueToString(0, 0)
      else "null"
    }
    CellValue.Str(elements.mkString("[", ", ", "]"))
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
        val groupType = schema.getType(i).asGroupType()
        Option(groupType.getLogicalTypeAnnotation) match {
          case Some(_: LogicalTypeAnnotation.ListLogicalTypeAnnotation) =>
            val listVal =
              try decodeListField(group, i, groupType)
              catch {
                case ex: Exception =>
                  if (warnedVariants.add(s"list-layout:$name"))
                    logger.warn(
                      s"Column '$name' has a non-standard LIST layout — emitting Null. Cause: ${ex.getMessage}"
                    )
                  CellValue.Null
              }
            builder += name -> listVal
          case _ =>
            if (warnedVariants.add(s"nested:$name"))
              logger.warn(
                s"Column '$name' is a nested group type (STRUCT/MAP) — emitting Null. " +
                  "Nested columns are readable but not round-trippable: convert and merge will " +
                  "reject files containing nested columns."
              )
            builder += name -> CellValue.Null
        }
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
            case (PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, _) =>
              CellValue.Bytes(group.getBinary(i, 0).getBytes)
            case (PrimitiveTypeName.INT96, _) =>
              decodeInt96Binary(group.getInt96(i, 0).getBytes)
            case _ => CellValue.Str(group.getValueToString(i, 0))
          }
        builder += name -> value
      } else {
        // repCount == 0 && !isPrimitive: null/absent nested field.
        // Emit Null so the key is always present, matching the sequential path.
        builder += schema.getType(i).getName -> CellValue.Null
      }
      i += 1
    }
    builder.result()
  }
}
