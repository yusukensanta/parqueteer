package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.CellValue
import org.apache.parquet.column.statistics.Statistics
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.LogicalTypeAnnotation
import org.apache.parquet.schema.LogicalTypeAnnotation.*
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName

/**
 * Pure computation of typed min/max statistics from Parquet column chunks.
 * Extracted from HadoopParquetRepository for testability and separation of concerns.
 */
object StatsComputer {

  def computeTypedMinMax(
      withValues: List[Statistics[?]],
      typeName: PrimitiveTypeName,
      logicalType: LogicalTypeAnnotation
  ): (Option[String], Option[String]) =
    logicalType match {
      case _: DateLogicalTypeAnnotation =>
        val (mn, mx) = numericMinMax[Int](
          withValues,
          { case n: java.lang.Integer => n.intValue() }
        )
        (
          mn.map(v => java.time.LocalDate.ofEpochDay(v.toLong).toString),
          mx.map(v => java.time.LocalDate.ofEpochDay(v.toLong).toString)
        )

      case ts: TimestampLogicalTypeAnnotation =>
        def rawToInstant(raw: String): String = {
          val v = raw.toLong
          ts.getUnit match {
            case LogicalTypeAnnotation.TimeUnit.MICROS =>
              java.time.Instant
                .ofEpochSecond(
                  Math.floorDiv(v, 1_000_000L),
                  Math.floorMod(v, 1_000_000L) * 1000L
                )
                .toString
            case LogicalTypeAnnotation.TimeUnit.NANOS =>
              java.time.Instant
                .ofEpochSecond(
                  Math.floorDiv(v, 1_000_000_000L),
                  Math.floorMod(v, 1_000_000_000L)
                )
                .toString
            case _ => java.time.Instant.ofEpochMilli(v).toString
          }
        }
        val (mn, mx) = numericMinMax[Long](
          withValues,
          { case n: java.lang.Long => n.longValue() }
        )
        (mn.map(rawToInstant), mx.map(rawToInstant))

      case dec: DecimalLogicalTypeAnnotation =>
        val scale = dec.getScale
        def applyScale(raw: String): String =
          new java.math.BigDecimal(
            new java.math.BigInteger(raw),
            scale
          ).toPlainString
        if typeName == PrimitiveTypeName.INT32 then {
          val (mn, mx) = numericMinMax[Int](
            withValues,
            { case n: java.lang.Integer => n.intValue() }
          )
          (mn.map(applyScale), mx.map(applyScale))
        } else if typeName == PrimitiveTypeName.INT64 then {
          val (mn, mx) = numericMinMax[Long](
            withValues,
            { case n: java.lang.Long => n.longValue() }
          )
          (mn.map(applyScale), mx.map(applyScale))
        } else {
          def fromBin(v: Any): Option[scala.math.BigDecimal] =
            PartialFunction.condOpt(v) { case bin: Binary =>
              scala.math.BigDecimal(
                new java.math.BigDecimal(
                  new java.math.BigInteger(bin.getBytes),
                  scale
                )
              )
            }
          val mins =
            withValues.flatMap(s => Option(s.genericGetMin()).flatMap(fromBin))
          val maxs =
            withValues.flatMap(s => Option(s.genericGetMax()).flatMap(fromBin))
          (
            mins.minOption.map(_.underlying.toPlainString),
            maxs.maxOption.map(_.underlying.toPlainString)
          )
        }

      case _ =>
        typeName match {
          case PrimitiveTypeName.INT32 =>
            numericMinMax[Int](
              withValues,
              { case n: java.lang.Integer => n.intValue() }
            )
          case PrimitiveTypeName.INT64 =>
            numericMinMax[Long](
              withValues,
              { case n: java.lang.Long => n.longValue() }
            )
          case PrimitiveTypeName.FLOAT =>
            numericMinMax[Float](
              withValues,
              { case n: java.lang.Float => n.floatValue() },
              filter = v => !v.isNaN
            )
          case PrimitiveTypeName.DOUBLE =>
            numericMinMax[Double](
              withValues,
              { case n: java.lang.Double => n.doubleValue() },
              filter = v => !v.isNaN
            )
          case PrimitiveTypeName.BOOLEAN =>
            val mins = withValues.flatMap(s =>
              Option(s.genericGetMin()).collect { case b: java.lang.Boolean =>
                b.booleanValue()
              }
            )
            val maxs = withValues.flatMap(s =>
              Option(s.genericGetMax()).collect { case b: java.lang.Boolean =>
                b.booleanValue()
              }
            )
            (mins.minOption.map(_.toString), maxs.maxOption.map(_.toString))
          case PrimitiveTypeName.INT96 =>
            def decodeInt96Stat(v: Any): Option[String] = v match {
              case b: Binary if b.length() == 12 =>
                ParquetRecordDecoder.decodeInt96Binary(b.getBytes) match {
                  case CellValue.Ts(inst) => Some(inst.toString)
                  case _                  => None
                }
              case _ => None
            }
            val minStrs =
              withValues.flatMap(s => Option(s.genericGetMin()).flatMap(decodeInt96Stat))
            val maxStrs =
              withValues.flatMap(s => Option(s.genericGetMax()).flatMap(decodeInt96Stat))
            (minStrs.minOption, maxStrs.maxOption)
          case _ =>
            if typeName == PrimitiveTypeName.BINARY ||
              typeName == PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY
            then {
              implicit val binOrd: Ordering[Binary] =
                (a, b) => java.util.Arrays.compare(a.getBytes, b.getBytes)
              val mins = withValues.flatMap(s =>
                Option(s.genericGetMin()).collect { case b: Binary =>
                  b
                }
              )
              val maxs = withValues.flatMap(s =>
                Option(s.genericGetMax()).collect { case b: Binary =>
                  b
                }
              )
              (
                mins.minOption.map(_.toStringUsingUTF8),
                maxs.maxOption.map(_.toStringUsingUTF8)
              )
            } else {
              val minVal = withValues
                .flatMap(s => Option(s.genericGetMin()).map(v => formatStatVal(v, typeName)))
                .minOption
              val maxVal = withValues
                .flatMap(s => Option(s.genericGetMax()).map(v => formatStatVal(v, typeName)))
                .maxOption
              (minVal, maxVal)
            }
        }
    }

  private[repositories] def numericMinMax[T: Ordering](
      withValues: List[Statistics[?]],
      extract: PartialFunction[Any, T],
      filter: T => Boolean = (_: T) => true
  ): (Option[String], Option[String]) = {
    val mins =
      withValues.flatMap(s => Option(s.genericGetMin()).collect(extract).filter(filter))
    val maxs =
      withValues.flatMap(s => Option(s.genericGetMax()).collect(extract).filter(filter))
    (mins.minOption.map(_.toString), maxs.maxOption.map(_.toString))
  }

  private[repositories] def formatStatVal(
      value: Any,
      typeName: PrimitiveTypeName
  ): String =
    typeName match {
      case PrimitiveTypeName.BINARY | PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY =>
        value match {
          case b: Binary => b.toStringUsingUTF8
          case other     => other.toString
        }
      case _ => value.toString
    }
}
