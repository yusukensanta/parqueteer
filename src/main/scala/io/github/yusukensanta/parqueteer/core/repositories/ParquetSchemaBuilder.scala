package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models._
import org.apache.parquet.schema.{
  LogicalTypeAnnotation,
  MessageType,
  PrimitiveType,
  Type,
  Types
}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import scala.jdk.CollectionConverters._

private[repositories] object ParquetSchemaBuilder {

  def projectSchema(
      fileSchema: MessageType,
      columns: List[String]
  ): MessageType = {
    val columnSet = columns.toSet
    val fields = fileSchema.getFields.asScala
      .filter(f => columnSet.contains(f.getName))
      .toList
    if (fields.isEmpty) {
      val available = fileSchema.getFields.asScala.map(_.getName).mkString(", ")
      throw new IllegalArgumentException(
        s"None of the requested columns exist in the file: ${columns
            .mkString(", ")}. Available columns: $available"
      )
    }
    new MessageType("root", fields.asJava)
  }

  def buildMessageType(schema: ParquetSchema): MessageType = {
    val builder = Types.buildMessage()
    schema.columns.foreach { col =>
      val repetition =
        if (col.isOptional) Type.Repetition.OPTIONAL
        else Type.Repetition.REQUIRED
      val (primitive, annotation) = mapDeclaredType(col.dataType)
      builder.addField(makeField(col.name, primitive, repetition, annotation))
    }
    builder.named("root")
  }

  // Helper to infer schema from data
  private val MaxDecimalPrecision = 38

  def inferSchemaFromData(data: List[Map[String, CellValue]]): MessageType = {
    if (data.isEmpty)
      throw new IllegalArgumentException("Cannot infer schema from empty data")

    // Single pass: accumulate per-column TypeRank from non-null values only.
    // seenKeys tracks all keys (including null-only columns) for schema output.
    val seenKeys = scala.collection.mutable.LinkedHashSet.empty[String]
    val rankByKey = scala.collection.mutable.HashMap.empty[String, TypeRank]
    // (maxScale, maxIntDigits) per decimal column, collected in same pass.
    val decimalMetaByKey =
      scala.collection.mutable.HashMap.empty[String, (Int, Int)]
    val warnedWiden = scala.collection.mutable.Set.empty[String]
    data.foreach { row =>
      row.foreach { case (k, v) =>
        seenKeys.add(k)
        if (v != CellValue.Null) {
          val r = typeRankForValue(v)
          rankByKey.updateWith(k) {
            case Some(prev) =>
              val widened = widenTypeRanks(prev, r)
              if (
                widened == TypeRank.String && prev != TypeRank.String && warnedWiden
                  .add(k)
              )
                Console.err.println(
                  s"[parqueteer] warning: column '$k' has mixed types ($prev and $r) — falling back to STRING"
                )
              Some(widened)
            case None => Some(r)
          }
          v match {
            case CellValue.Dec(bd) =>
              val scale = bd.scale.max(0)
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

    val builder = Types.buildMessage()
    seenKeys.toList.foreach { key =>
      val rank = rankByKey.getOrElse(key, TypeRank.String)
      val (primitive, annotation) =
        if (rank == TypeRank.Decimal) {
          val (maxScale, maxIntDigits) =
            decimalMetaByKey.getOrElse(key, (18, 20))
          val precision =
            (maxScale + maxIntDigits).min(MaxDecimalPrecision).max(1)
          val scale = maxScale.min(precision - 1).max(0)
          if (maxScale + maxIntDigits > MaxDecimalPrecision)
            throw new IllegalArgumentException(
              s"Column '$key' requires precision ${maxScale + maxIntDigits} " +
                s"but Parquet DECIMAL max is $MaxDecimalPrecision. " +
                s"Reduce scale/integer-part size or split into multiple columns."
            )
          (
            PrimitiveTypeName.BINARY,
            Some(LogicalTypeAnnotation.decimalType(scale, precision))
          )
        } else rankToParquetType(rank)
      builder.addField(
        makeField(key, primitive, Type.Repetition.OPTIONAL, annotation)
      )
    }
    builder.named("root")
  }

  // ── private helpers ───────────────────────────────────────────────────────

  /** Build a Parquet primitive field with an optional logical type annotation.
    */
  private def makeField(
      name: String,
      primitive: PrimitiveTypeName,
      repetition: Type.Repetition,
      annotation: Option[LogicalTypeAnnotation]
  ): PrimitiveType = {
    val base = Types.primitive(primitive, repetition)
    annotation.foldLeft(base)(_.as(_)).named(name)
  }

  private def mapDeclaredType(
      dataType: String
  ): (PrimitiveTypeName, Option[LogicalTypeAnnotation]) =
    dataType.toUpperCase match {
      case "INT32" | "INT"  => (PrimitiveTypeName.INT32, None)
      case "INT64" | "LONG" => (PrimitiveTypeName.INT64, None)
      case "DOUBLE"         => (PrimitiveTypeName.DOUBLE, None)
      case "FLOAT"          => (PrimitiveTypeName.FLOAT, None)
      case "BOOLEAN"        => (PrimitiveTypeName.BOOLEAN, None)
      case "DATE"           => (PrimitiveTypeName.INT32, Some(dateAnnotation))
      case "TIMESTAMP" | "TIMESTAMP_MILLIS" =>
        (PrimitiveTypeName.INT64, Some(timestampMillisAnnotation))
      case "TIMESTAMP_MICROS" =>
        (PrimitiveTypeName.INT64, Some(timestampMicrosAnnotation))
      case "TIMESTAMP_NANOS" =>
        (PrimitiveTypeName.INT64, Some(timestampNanosAnnotation))
      case "STRING" => (PrimitiveTypeName.BINARY, Some(stringAnnotation))
      case "BINARY" => (PrimitiveTypeName.BINARY, None)
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
        val decimalPattern = """^DECIMAL\((\d+),\s*(\d+)\)$""".r
        t match {
          case decimalPattern(pStr, sStr) =>
            val precision = pStr.toInt
            val scale = sStr.trim.toInt
            if (precision < 1 || precision > MaxDecimalPrecision)
              throw new IllegalArgumentException(
                s"Invalid DECIMAL precision $precision in '$t': must be in [1, $MaxDecimalPrecision]."
              )
            if (scale < 0 || scale >= precision)
              throw new IllegalArgumentException(
                s"Invalid DECIMAL scale $scale in '$t': must be in [0, precision-1] = [0, ${precision - 1}]."
              )
            (
              PrimitiveTypeName.BINARY,
              Some(LogicalTypeAnnotation.decimalType(scale, precision))
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
  private val floatRanks: Set[TypeRank] = Set(TypeRank.Float, TypeRank.Double)
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

  /** Widen two numeric ranks losslessly. Decimal beats all other numeric types
    * to avoid silent precision loss. Integer+float cross-family produces Double
    * — Float cannot represent the full Long range.
    */
  private def widenTypeRanks(a: TypeRank, b: TypeRank): TypeRank =
    if (a == b) a
    else if (a == TypeRank.Decimal && numericRanks(b)) TypeRank.Decimal
    else if (b == TypeRank.Decimal && numericRanks(a)) TypeRank.Decimal
    else if (integerRanks(a) && integerRanks(b))
      if (a == TypeRank.Long || b == TypeRank.Long) TypeRank.Long
      else TypeRank.Int
    else if (floatRanks(a) && floatRanks(b))
      if (a == TypeRank.Double || b == TypeRank.Double) TypeRank.Double
      else TypeRank.Float
    else if (numericRanks(a) && numericRanks(b))
      TypeRank.Double
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
