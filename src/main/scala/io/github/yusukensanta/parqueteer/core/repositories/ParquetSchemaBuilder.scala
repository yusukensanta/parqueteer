package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.*
import org.apache.parquet.schema.{LogicalTypeAnnotation, MessageType, PrimitiveType, Type, Types}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import scala.jdk.CollectionConverters.*

private[repositories] object ParquetSchemaBuilder {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  def projectSchema(
      fileSchema: MessageType,
      columns: List[String]
  ): MessageType = {
    val columnSet   = columns.toSet
    val schemaNames = fileSchema.getFields.asScala.map(_.getName).toSet
    val fields = fileSchema.getFields.asScala
      .filter(f => columnSet.contains(f.getName))
      .toList
    if fields.isEmpty then {
      val available = schemaNames.mkString(", ")
      throw new IllegalArgumentException(
        s"None of the requested columns exist in the file: ${columns
            .mkString(", ")}. Available columns: $available"
      )
    }
    val unmatched = columns.filterNot(schemaNames.contains)
    if unmatched.nonEmpty then
      logger.warn(
        "--columns requested {} which do not exist in the file and will be silently dropped; available columns: {}",
        unmatched.mkString(", "),
        schemaNames.mkString(", ")
      )
    new MessageType("root", fields.asJava)
  }

  def buildMessageType(schema: ParquetSchema): MessageType = {
    val builder = Types.buildMessage()
    schema.columns.foreach { col =>
      val repetition =
        if col.isOptional then Type.Repetition.OPTIONAL
        else Type.Repetition.REQUIRED
      val (primitive, annotation, length) = mapDeclaredType(col.dataType)
      builder.addField(
        makeField(col.name, primitive, repetition, annotation, length)
      )
    }
    builder.named("root")
  }

  // Helper to infer schema from data
  private[repositories] val MaxDecimalPrecision = 38

  // Largest DECIMAL precision that fits (with sign) in a signed 4-byte / 8-byte
  // integer: 2^31-1 ~= 2.1e9 (10 digits, but the top one isn't always usable,
  // so 9 is the safe ceiling) and 2^63-1 ~= 9.22e18 (19 digits, same reasoning
  // caps it at 18). Matches the well-known Spark/Arrow DECIMAL byte-width
  // convention.
  private[repositories] val MaxInt32DecimalPrecision = 9
  private[repositories] val MaxInt64DecimalPrecision = 18

  // Physical Parquet type to declare for a DECIMAL column of the given
  // precision, and (for FIXED_LEN_BYTE_ARRAY only) the byte length to declare
  // alongside it. This is NOT an arbitrary choice: parquet4s's reader
  // (ParquetRecordConverter.createConverter, which parqueteer's own
  // read/streamContent path uses) only supports DECIMAL backed by INT32,
  // INT64, or FIXED_LEN_BYTE_ARRAY -- plain BINARY throws
  // ParquetDecodingException("BINARY is unsupported as a decimal type") at
  // read time despite being spec-valid. Writing BINARY-backed DECIMAL here
  // would produce a file parqueteer itself could never read back.
  private[repositories] def decimalPhysicalType(
      precision: Int
  ): (PrimitiveTypeName, Option[Int]) =
    if precision <= MaxInt32DecimalPrecision then (PrimitiveTypeName.INT32, None)
    else if precision <= MaxInt64DecimalPrecision then (PrimitiveTypeName.INT64, None)
    else (PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, Some(fixedLenByteArrayLength(precision)))

  // Smallest byte width that can hold a signed two's-complement unscaled
  // value up to (10^precision - 1) in magnitude: the smallest L such that
  // 2^(8L-1) >= 10^precision. Computed with BigInteger rather than
  // math.log/math.pow to avoid floating-point rounding tipping a boundary
  // the wrong way -- an undersized field here would silently truncate data.
  private[repositories] def fixedLenByteArrayLength(precision: Int): Int = {
    val bound  = java.math.BigInteger.TEN.pow(precision)
    var length = 1
    while java.math.BigInteger.ONE.shiftLeft(8 * length - 1).compareTo(bound) < 0
    do length += 1
    length
  }

  def inferSchemaFromData(data: List[Map[String, CellValue]]): MessageType =
    inferSchemaFromRows(data.iterator)

  // Same fold as inferSchemaFromData, generalized to an Iterator so two-pass
  // streaming writers can infer a schema without ever materializing a List.
  def inferSchemaFromRows(rows: Iterator[Map[String, CellValue]]): MessageType = {
    // Single pass: accumulate per-column TypeRank from non-null values only.
    // seenKeys tracks all keys (including null-only columns) for schema output.
    val seenKeys  = scala.collection.mutable.LinkedHashSet.empty[String]
    val rankByKey = scala.collection.mutable.HashMap.empty[String, TypeRank]
    // (maxScale, maxIntDigits) per decimal column, collected in same pass.
    val decimalMetaByKey =
      scala.collection.mutable.HashMap.empty[String, (Int, Int)]
    val warnedWiden = scala.collection.mutable.Set.empty[String]
    var sawAnyRow   = false
    rows.foreach { row =>
      sawAnyRow = true
      row.foreach { case (k, v) =>
        seenKeys.add(k)
        if v != CellValue.Null then {
          val r = typeRankForValue(v)
          rankByKey.updateWith(k) {
            case Some(prev) =>
              val widened = widenTypeRanks(prev, r)
              if widened == TypeRank.String && prev != TypeRank.String && warnedWiden
                  .add(k)
              then
                logger.warn(
                  "column '{}' has mixed types ({} and {}) — falling back to STRING",
                  k,
                  prev,
                  r
                )
              Some(widened)
            case None => Some(r)
          }
          v match {
            case CellValue.Dec(bd) =>
              val scale     = bd.scale.max(0)
              val intDigits = (bd.precision - bd.scale).max(1)
              decimalMetaByKey.updateWith(k) {
                case Some((ms, mi)) => Some((ms.max(scale), mi.max(intDigits)))
                case None           => Some((scale, intDigits))
              }
            case _ =>
          }
        }
      }
    }
    if !sawAnyRow then throw new IllegalArgumentException("Cannot infer schema from empty data")

    val builder = Types.buildMessage()
    seenKeys.toList.foreach { key =>
      val rank = rankByKey.getOrElse(key, TypeRank.String)
      val (primitive, annotation, length) =
        if rank == TypeRank.Decimal then {
          val (maxScale, maxIntDigits) =
            decimalMetaByKey.getOrElse(key, (18, 20))
          val precision =
            (maxScale + maxIntDigits).min(MaxDecimalPrecision).max(1)
          val scale = maxScale.min(precision - 1).max(0)
          if maxScale + maxIntDigits > MaxDecimalPrecision then
            throw new IllegalArgumentException(
              s"Column '$key' requires precision ${maxScale + maxIntDigits} " +
                s"but Parquet DECIMAL max is $MaxDecimalPrecision. " +
                s"Reduce scale/integer-part size or split into multiple columns."
            )
          val (physical, flbaLength) = decimalPhysicalType(precision)
          (
            physical,
            Some(LogicalTypeAnnotation.decimalType(scale, precision)),
            flbaLength
          )
        } else {
          val (p, a) = rankToParquetType(rank)
          (p, a, None)
        }
      builder.addField(
        makeField(key, primitive, Type.Repetition.OPTIONAL, annotation, length)
      )
    }
    builder.named("root")
  }

  // ── private helpers ───────────────────────────────────────────────────────

  /**
   * Build a Parquet primitive field with an optional logical type annotation
   * and, for FIXED_LEN_BYTE_ARRAY fields, an optional declared byte length.
   */
  private def makeField(
      name: String,
      primitive: PrimitiveTypeName,
      repetition: Type.Repetition,
      annotation: Option[LogicalTypeAnnotation],
      length: Option[Int]
  ): PrimitiveType = {
    val base0 = Types.primitive(primitive, repetition)
    val base  = length.fold(base0)(base0.length)
    annotation.foldLeft(base)(_.as(_)).named(name)
  }

  private val decimalPattern = """^DECIMAL\((\d+),\s*(\d+)\)$""".r

  private def mapDeclaredType(
      dataType: String
  ): (PrimitiveTypeName, Option[LogicalTypeAnnotation], Option[Int]) =
    dataType.toUpperCase match {
      case "INT32" | "INT"  => (PrimitiveTypeName.INT32, None, None)
      case "INT64" | "LONG" => (PrimitiveTypeName.INT64, None, None)
      case "DOUBLE"         => (PrimitiveTypeName.DOUBLE, None, None)
      case "FLOAT"          => (PrimitiveTypeName.FLOAT, None, None)
      case "BOOLEAN"        => (PrimitiveTypeName.BOOLEAN, None, None)
      case "DATE"           => (PrimitiveTypeName.INT32, Some(dateAnnotation), None)
      case "TIMESTAMP" | "TIMESTAMP_MILLIS" =>
        (PrimitiveTypeName.INT64, Some(timestampMillisAnnotation), None)
      case "TIMESTAMP_MICROS" =>
        (PrimitiveTypeName.INT64, Some(timestampMicrosAnnotation), None)
      case "TIMESTAMP_NANOS" =>
        (PrimitiveTypeName.INT64, Some(timestampNanosAnnotation), None)
      case "STRING" => (PrimitiveTypeName.BINARY, Some(stringAnnotation), None)
      case "BINARY" => (PrimitiveTypeName.BINARY, None, None)
      case "INT96" =>
        throw new IllegalArgumentException(
          "INT96 is deprecated in the Parquet spec and not supported for writing. " +
            "Use TIMESTAMP or TIMESTAMP_MILLIS instead."
        )
      case "FIXED_LEN_BYTE_ARRAY" =>
        throw new IllegalArgumentException(
          "FIXED_LEN_BYTE_ARRAY requires a byte length and is not supported via the schema config. " +
            "Use BINARY for variable-length byte fields."
        )
      case t if t.startsWith("DECIMAL") =>
        t match {
          case decimalPattern(pStr, sStr) =>
            val precision = pStr.toInt
            val scale     = sStr.trim.toInt
            if precision < 1 || precision > MaxDecimalPrecision then
              throw new IllegalArgumentException(
                s"Invalid DECIMAL precision $precision in '$t': must be in [1, $MaxDecimalPrecision]."
              )
            if scale < 0 || scale > precision then
              throw new IllegalArgumentException(
                s"Invalid DECIMAL scale $scale in '$t': must be in [0, precision] = [0, $precision]."
              )
            val (physical, flbaLength) = decimalPhysicalType(precision)
            (
              physical,
              Some(LogicalTypeAnnotation.decimalType(scale, precision)),
              flbaLength
            )
          case _ =>
            throw new IllegalArgumentException(
              s"Invalid DECIMAL syntax '$t'. Use DECIMAL(precision,scale) e.g. DECIMAL(10,2)"
            )
        }
      case t
          if t.startsWith("STRUCT") || t
            .startsWith("MAP") || t.startsWith("LIST") =>
        throw new IllegalArgumentException(
          s"Nested type '$t' (STRUCT/MAP/LIST) is not supported for writing. " +
            "Flatten the data structure to primitive columns before writing."
        )
      case other =>
        throw new IllegalArgumentException(
          s"Unknown dataType '$other'. Supported: INT32, INT64, DOUBLE, FLOAT, BOOLEAN, DATE, TIMESTAMP, DECIMAL(p,s), STRING, BINARY"
        )
    }

  private def dateAnnotation: LogicalTypeAnnotation =
    LogicalTypeAnnotation.dateType()

  private def timestampMillisAnnotation: LogicalTypeAnnotation =
    LogicalTypeAnnotation.timestampType(
      true,
      LogicalTypeAnnotation.TimeUnit.MILLIS
    )

  private def timestampMicrosAnnotation: LogicalTypeAnnotation =
    LogicalTypeAnnotation.timestampType(
      true,
      LogicalTypeAnnotation.TimeUnit.MICROS
    )

  private def timestampNanosAnnotation: LogicalTypeAnnotation =
    LogicalTypeAnnotation.timestampType(
      true,
      LogicalTypeAnnotation.TimeUnit.NANOS
    )

  // Used only to keep rankToParquetType exhaustive; callers handle TypeRank.Decimal
  // inline (with per-column scale/precision) before invoking rankToParquetType.
  // decimalType(scale=18, precision=38) — args are (scale, precision).
  private def decimalAnnotation: LogicalTypeAnnotation =
    LogicalTypeAnnotation.decimalType(18, 38)

  private def stringAnnotation: LogicalTypeAnnotation =
    LogicalTypeAnnotation.stringType()

  /** Inferred CellValue type for schema-from-data. */
  private enum TypeRank:
    case Int, Long, Float, Double, Decimal, Boolean, Date, Timestamp, String

  private val integerRanks: Set[TypeRank] = Set(TypeRank.Int, TypeRank.Long)
  private val floatRanks: Set[TypeRank]   = Set(TypeRank.Float, TypeRank.Double)

  private val numericRanks: Set[TypeRank] =
    integerRanks | floatRanks | Set(TypeRank.Decimal)

  private def typeRankForValue(v: CellValue): TypeRank = v match {
    case CellValue.I32(_)  => TypeRank.Int
    case CellValue.I64(_)  => TypeRank.Long
    case CellValue.F32(_)  => TypeRank.Float
    case CellValue.F64(_)  => TypeRank.Double
    case CellValue.Dec(_)  => TypeRank.Decimal
    case CellValue.Bool(_) => TypeRank.Boolean
    case CellValue.Date(_) => TypeRank.Date
    case CellValue.Ts(_)   => TypeRank.Timestamp
    case _                 => TypeRank.String
  }

  /**
   * Widen two numeric ranks losslessly. Decimal beats all other numeric types
   * to avoid silent precision loss. Integer+float cross-family produces Double
   * — Float cannot represent the full Long range.
   */
  private def widenTypeRanks(a: TypeRank, b: TypeRank): TypeRank =
    if a == b then a
    else if a == TypeRank.Decimal && numericRanks(b) then TypeRank.Decimal
    else if b == TypeRank.Decimal && numericRanks(a) then TypeRank.Decimal
    else if integerRanks(a) && integerRanks(b) then
      if a == TypeRank.Long || b == TypeRank.Long then TypeRank.Long
      else TypeRank.Int
    else if floatRanks(a) && floatRanks(b) then
      if a == TypeRank.Double || b == TypeRank.Double then TypeRank.Double
      else TypeRank.Float
    else if numericRanks(a) && numericRanks(b) then TypeRank.Double
    else TypeRank.String

  private def rankToParquetType(
      rank: TypeRank
  ): (PrimitiveTypeName, Option[LogicalTypeAnnotation]) = rank match {
    case TypeRank.Int     => (PrimitiveTypeName.INT32, None)
    case TypeRank.Long    => (PrimitiveTypeName.INT64, None)
    case TypeRank.Float   => (PrimitiveTypeName.FLOAT, None)
    case TypeRank.Double  => (PrimitiveTypeName.DOUBLE, None)
    case TypeRank.Decimal => (PrimitiveTypeName.BINARY, Some(decimalAnnotation))
    case TypeRank.Boolean => (PrimitiveTypeName.BOOLEAN, None)
    case TypeRank.Date    => (PrimitiveTypeName.INT32, Some(dateAnnotation))
    case TypeRank.Timestamp =>
      (PrimitiveTypeName.INT64, Some(timestampMillisAnnotation))
    case TypeRank.String => (PrimitiveTypeName.BINARY, Some(stringAnnotation))
  }
}
