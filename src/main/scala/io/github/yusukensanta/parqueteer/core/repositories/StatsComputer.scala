package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.CellValue
import org.apache.parquet.column.statistics.Statistics
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.LogicalTypeAnnotation
import org.apache.parquet.schema.LogicalTypeAnnotation.*
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import java.time.{Instant, LocalDate}

/**
 * Pure computation of typed min/max statistics from Parquet column chunks.
 * Extracted from HadoopParquetRepository for testability and separation of concerns.
 *
 * Each column type is described by a [[StatCodec]]: how to pull a typed value
 * out of a chunk's raw min/max, how to order those values, and how to render
 * the winner. Min/max is always chosen on the typed values and rendered only
 * afterwards, so ordering never depends on the display text.
 */
object StatsComputer {

  /**
   * @param extract the typed value of a raw statistic, or no match to skip it
   *                (wrong boxed type, NaN, undecodable bytes)
   * @param render  display text of the chosen min or max
   */
  final private[repositories] case class StatCodec[T](
      extract: PartialFunction[Any, T],
      render: T => String
  )(using val ordering: Ordering[T])

  def computeTypedMinMax(
      withValues: List[Statistics[?]],
      typeName: PrimitiveTypeName,
      logicalType: LogicalTypeAnnotation
  ): (Option[String], Option[String]) =
    minMax(withValues, codecFor(typeName, logicalType))

  private[repositories] def minMax[T](
      withValues: List[Statistics[?]],
      codec: StatCodec[T]
  ): (Option[String], Option[String]) = {
    given Ordering[T] = codec.ordering
    def values(side: Statistics[?] => Any): List[T] =
      withValues.flatMap(s => Option(side(s)).collect(codec.extract))
    (
      values(_.genericGetMin()).minOption.map(codec.render),
      values(_.genericGetMax()).maxOption.map(codec.render)
    )
  }

  private[repositories] def codecFor(
      typeName: PrimitiveTypeName,
      logicalType: LogicalTypeAnnotation
  ): StatCodec[?] =
    logicalType match {
      case _: DateLogicalTypeAnnotation =>
        StatCodec(int32, d => LocalDate.ofEpochDay(d.toLong).toString)
      case ts: TimestampLogicalTypeAnnotation =>
        StatCodec(int64, v => instantOf(v, ts.getUnit).toString)
      case dec: DecimalLogicalTypeAnnotation =>
        decimalCodec(typeName, dec.getScale)
      case _ =>
        typeName match {
          case PrimitiveTypeName.INT32   => StatCodec(int32, _.toString)
          case PrimitiveTypeName.INT64   => StatCodec(int64, _.toString)
          case PrimitiveTypeName.FLOAT   => StatCodec(float32, _.toString)
          case PrimitiveTypeName.DOUBLE  => StatCodec(float64, _.toString)
          case PrimitiveTypeName.BOOLEAN => StatCodec(boolean, _.toString)
          case PrimitiveTypeName.INT96 => StatCodec(int96Instant, _.toString)(using instantOrdering)
          case _ => // BINARY, FIXED_LEN_BYTE_ARRAY
            StatCodec(binary, _.toStringUsingUTF8)(using unsignedBinaryOrdering)
        }
    }

  private def decimalCodec(typeName: PrimitiveTypeName, scale: Int): StatCodec[?] = {
    def scaled(unscaled: java.math.BigInteger): String =
      new java.math.BigDecimal(unscaled, scale).toPlainString
    typeName match {
      case PrimitiveTypeName.INT32 =>
        StatCodec(int32, v => scaled(java.math.BigInteger.valueOf(v.toLong)))
      case PrimitiveTypeName.INT64 =>
        StatCodec(int64, v => scaled(java.math.BigInteger.valueOf(v)))
      case _ => // BINARY / FIXED_LEN_BYTE_ARRAY: big-endian two's-complement unscaled value
        StatCodec[BigDecimal](
          { case b: Binary =>
            BigDecimal(new java.math.BigDecimal(new java.math.BigInteger(b.getBytes), scale))
          },
          _.underlying.toPlainString
        )
    }
  }

  // ── Raw statistic extractors ────────────────────────────────────────────

  private val int32: PartialFunction[Any, Int]  = { case n: java.lang.Integer => n.intValue() }
  private val int64: PartialFunction[Any, Long] = { case n: java.lang.Long => n.longValue() }

  private val boolean: PartialFunction[Any, Boolean] = { case b: java.lang.Boolean =>
    b.booleanValue()
  }

  // NaN min/max carries no ordering information, so it is skipped.
  private val float32: PartialFunction[Any, Float] = {
    case n: java.lang.Float if !n.isNaN => n.floatValue()
  }

  private val float64: PartialFunction[Any, Double] = {
    case n: java.lang.Double if !n.isNaN => n.doubleValue()
  }
  private val binary: PartialFunction[Any, Binary] = { case b: Binary => b }

  private val int96Instant: PartialFunction[Any, Instant] = Function.unlift {
    case b: Binary if b.length() == 12 =>
      ParquetRecordDecoder.decodeInt96Binary(b.getBytes) match {
        case CellValue.Ts(instant) => Some(instant)
        case _                     => None
      }
    case _ => None
  }

  // ── Orderings ───────────────────────────────────────────────────────────

  private val instantOrdering: Ordering[Instant] = Ordering.fromLessThan(_.isBefore(_))

  // Parquet orders BINARY/STRING statistics as unsigned bytes, which for UTF-8
  // is code-point order; a signed compare would put every non-ASCII character
  // (lead byte >= 0x80) before 'A'.
  private val unsignedBinaryOrdering: Ordering[Binary] =
    (a, b) => java.util.Arrays.compareUnsigned(a.getBytes, b.getBytes)

  private def instantOf(value: Long, unit: LogicalTypeAnnotation.TimeUnit): Instant =
    unit match {
      case LogicalTypeAnnotation.TimeUnit.MICROS =>
        Instant.ofEpochSecond(
          Math.floorDiv(value, 1_000_000L),
          Math.floorMod(value, 1_000_000L) * 1000L
        )
      case LogicalTypeAnnotation.TimeUnit.NANOS =>
        Instant.ofEpochSecond(
          Math.floorDiv(value, 1_000_000_000L),
          Math.floorMod(value, 1_000_000_000L)
        )
      case _ => Instant.ofEpochMilli(value)
    }
}
