package io.github.yusukensanta.parqueteer.core.repositories

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.apache.parquet.column.statistics.*
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.{LogicalTypeAnnotation, PrimitiveType, Types}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName

class StatsComputerTest extends AnyFlatSpec with Matchers {

  private def intType(logical: LogicalTypeAnnotation): PrimitiveType =
    if logical == null then Types.required(PrimitiveTypeName.INT32).named("test")
    else Types.required(PrimitiveTypeName.INT32).as(logical).named("test")

  private def longType(logical: LogicalTypeAnnotation): PrimitiveType =
    if logical == null then Types.required(PrimitiveTypeName.INT64).named("test")
    else Types.required(PrimitiveTypeName.INT64).as(logical).named("test")

  private def mkIntStats(
      min: Int,
      max: Int,
      logical: LogicalTypeAnnotation = null
  ): Statistics[?] = {
    val pt    = intType(logical)
    val stats = Statistics.createStats(pt).asInstanceOf[IntStatistics]
    stats.setMinMax(min, max)
    stats
  }

  private def mkLongStats(
      min: Long,
      max: Long,
      logical: LogicalTypeAnnotation = null
  ): Statistics[?] = {
    val pt    = longType(logical)
    val stats = Statistics.createStats(pt).asInstanceOf[LongStatistics]
    stats.setMinMax(min, max)
    stats
  }

  private def mkFloatStats(min: Float, max: Float): Statistics[?] = {
    val pt    = Types.required(PrimitiveTypeName.FLOAT).named("test")
    val stats = Statistics.createStats(pt).asInstanceOf[FloatStatistics]
    stats.setMinMax(min, max)
    stats
  }

  private def mkDoubleStats(min: Double, max: Double): Statistics[?] = {
    val pt    = Types.required(PrimitiveTypeName.DOUBLE).named("test")
    val stats = Statistics.createStats(pt).asInstanceOf[DoubleStatistics]
    stats.setMinMax(min, max)
    stats
  }

  private def mkBoolStats(min: Boolean, max: Boolean): Statistics[?] = {
    val pt    = Types.required(PrimitiveTypeName.BOOLEAN).named("test")
    val stats = Statistics.createStats(pt).asInstanceOf[BooleanStatistics]
    stats.setMinMax(min, max)
    stats
  }

  private def mkBinaryStats(min: String, max: String): Statistics[?] = {
    val pt    = Types.required(PrimitiveTypeName.BINARY).named("test")
    val stats = Statistics.createStats(pt).asInstanceOf[BinaryStatistics]
    stats.setMinMax(Binary.fromString(min), Binary.fromString(max))
    stats
  }

  "computeTypedMinMax" should "return (None, None) for empty statistics list" in {
    val result = StatsComputer.computeTypedMinMax(Nil, PrimitiveTypeName.INT32, null)
    result shouldBe (None, None)
  }

  it should "compute INT32 min/max" in {
    val stats = List(mkIntStats(5, 20), mkIntStats(1, 15))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.INT32, null)
    mn shouldBe Some("1")
    mx shouldBe Some("20")
  }

  it should "compute INT64 min/max" in {
    val stats = List(mkLongStats(100L, 999L), mkLongStats(50L, 500L))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.INT64, null)
    mn shouldBe Some("50")
    mx shouldBe Some("999")
  }

  it should "compute Date (epoch-day) min/max" in {
    val dateLogical = LogicalTypeAnnotation.dateType()
    val stats       = List(mkIntStats(0, 19000))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.INT32, dateLogical)
    mn shouldBe Some("1970-01-01")
    mx.get should startWith("2022-")
  }

  it should "compute Timestamp MILLIS min/max" in {
    val tsLogical = LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.MILLIS)
    val epoch     = 1_700_000_000_000L
    val stats     = List(mkLongStats(epoch, epoch + 1000L))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.INT64, tsLogical)
    mn.get should include("2023-")
    mx.get should include("2023-")
  }

  it should "compute Timestamp MICROS min/max" in {
    val tsLogical = LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.MICROS)
    val epoch     = 1_700_000_000_000_000L
    val stats     = List(mkLongStats(epoch, epoch + 1_000_000L))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.INT64, tsLogical)
    mn.get should include("2023-")
    mx.get should include("2023-")
  }

  it should "compute Timestamp NANOS min/max" in {
    val tsLogical =
      LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.NANOS)
    val epoch = 1_700_000_000_000_000_000L
    val stats = List(mkLongStats(epoch, epoch + 1_000_000_000L))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.INT64, tsLogical)
    mn.get should include("2023-")
    mx.get should include("2023-")
  }

  it should "compute Decimal INT32 min/max" in {
    val decLogical = LogicalTypeAnnotation.decimalType(2, 9)
    val stats      = List(mkIntStats(1234, 5678))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.INT32, decLogical)
    mn shouldBe Some("12.34")
    mx shouldBe Some("56.78")
  }

  it should "compute Decimal INT64 min/max" in {
    val decLogical = LogicalTypeAnnotation.decimalType(3, 18)
    val stats      = List(mkLongStats(123456L, 789012L))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.INT64, decLogical)
    mn shouldBe Some("123.456")
    mx shouldBe Some("789.012")
  }

  it should "compute Decimal BINARY min/max" in {
    val decLogical = LogicalTypeAnnotation.decimalType(2, 10)
    val pt = Types
      .required(PrimitiveTypeName.BINARY)
      .as(decLogical)
      .named("test")
    val stats = Statistics.createStats(pt).asInstanceOf[BinaryStatistics]
    stats.setMinMax(
      Binary.fromConstantByteArray(BigInt(1050).toByteArray),
      Binary.fromConstantByteArray(BigInt(2099).toByteArray)
    )
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(List(stats), PrimitiveTypeName.BINARY, decLogical)
    mn shouldBe Some("10.50")
    mx shouldBe Some("20.99")
  }

  it should "compute FLOAT min/max" in {
    val stats = List(mkFloatStats(1.5f, 3.5f), mkFloatStats(0.5f, 2.5f))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.FLOAT, null)
    mn shouldBe Some("0.5")
    mx shouldBe Some("3.5")
  }

  it should "filter NaN from FLOAT stats" in {
    val stats = List(mkFloatStats(Float.NaN, Float.NaN), mkFloatStats(1.0f, 5.0f))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.FLOAT, null)
    mn shouldBe Some("1.0")
    mx shouldBe Some("5.0")
  }

  it should "compute DOUBLE min/max" in {
    val stats = List(mkDoubleStats(1.1, 9.9))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.DOUBLE, null)
    mn shouldBe Some("1.1")
    mx shouldBe Some("9.9")
  }

  it should "filter NaN from DOUBLE stats" in {
    val stats = List(mkDoubleStats(Double.NaN, Double.NaN), mkDoubleStats(2.0, 8.0))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.DOUBLE, null)
    mn shouldBe Some("2.0")
    mx shouldBe Some("8.0")
  }

  it should "compute BOOLEAN min/max" in {
    val stats = List(mkBoolStats(false, true))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.BOOLEAN, null)
    mn shouldBe Some("false")
    mx shouldBe Some("true")
  }

  it should "compute BINARY min/max as UTF-8" in {
    val stats = List(mkBinaryStats("apple", "zebra"), mkBinaryStats("banana", "mango"))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.BINARY, null)
    mn shouldBe Some("apple")
    mx shouldBe Some("zebra")
  }

  it should "aggregate across multiple row groups" in {
    val stats = List(mkIntStats(10, 20), mkIntStats(5, 30), mkIntStats(15, 25))
    val (mn, mx) =
      StatsComputer.computeTypedMinMax(stats, PrimitiveTypeName.INT32, null)
    mn shouldBe Some("5")
    mx shouldBe Some("30")
  }

  // ── Characterization: exact rendered strings, pinned before refactoring ──

  private def tsStats(unit: LogicalTypeAnnotation.TimeUnit, min: Long, max: Long) = {
    val logical = LogicalTypeAnnotation.timestampType(true, unit)
    StatsComputer.computeTypedMinMax(
      List(mkLongStats(min, max, logical)),
      PrimitiveTypeName.INT64,
      logical
    )
  }

  it should "render timestamps as ISO-8601 instants at the column's precision" in {
    import LogicalTypeAnnotation.TimeUnit.*
    tsStats(MILLIS, 1_700_000_000_000L, 1_700_000_000_123L) shouldBe
      (Some("2023-11-14T22:13:20Z"), Some("2023-11-14T22:13:20.123Z"))
    tsStats(MICROS, 1_700_000_000_000_001L, 1_700_000_000_000_001L)._1 shouldBe
      Some("2023-11-14T22:13:20.000001Z")
    tsStats(NANOS, 1_700_000_000_000_000_001L, 1_700_000_000_000_000_001L)._1 shouldBe
      Some("2023-11-14T22:13:20.000000001Z")
  }

  it should "render pre-epoch timestamps without truncating toward zero" in {
    import LogicalTypeAnnotation.TimeUnit.*
    tsStats(MILLIS, -1L, -1L)._1 shouldBe Some("1969-12-31T23:59:59.999Z")
    tsStats(MICROS, -1L, -1L)._1 shouldBe Some("1969-12-31T23:59:59.999999Z")
    tsStats(NANOS, -1L, -1L)._1 shouldBe Some("1969-12-31T23:59:59.999999999Z")
  }

  it should "render dates as ISO local dates, including before the epoch" in {
    StatsComputer.computeTypedMinMax(
      List(mkIntStats(-1, 19000)),
      PrimitiveTypeName.INT32,
      LogicalTypeAnnotation.dateType()
    ) shouldBe (Some("1969-12-31"), Some("2022-01-08"))
  }

  it should "render negative INT32 decimals with leading zeros" in {
    StatsComputer.computeTypedMinMax(
      List(mkIntStats(-5, 7)),
      PrimitiveTypeName.INT32,
      LogicalTypeAnnotation.decimalType(2, 9)
    ) shouldBe (Some("-0.05"), Some("0.07"))
  }

  private def binDecimalStats(
      physical: PrimitiveTypeName,
      min: BigInt,
      max: BigInt,
      logical: LogicalTypeAnnotation
  ): Statistics[?] = {
    val base = Types.required(physical)
    val pt =
      (if physical == PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY then base.length(16) else base)
        .as(logical)
        .named("test")
    val stats = Statistics.createStats(pt).asInstanceOf[BinaryStatistics]
    def bytes(v: BigInt): Array[Byte] =
      if physical == PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY then {
        val raw = v.toByteArray
        Array.fill[Byte](16 - raw.length)(if v < 0 then -1 else 0) ++ raw
      } else v.toByteArray
    stats.setMinMax(
      Binary.fromConstantByteArray(bytes(min)),
      Binary.fromConstantByteArray(bytes(max))
    )
    stats
  }

  it should "compare BINARY decimals numerically across row groups, negatives included" in {
    val dec = LogicalTypeAnnotation.decimalType(2, 10)
    StatsComputer.computeTypedMinMax(
      List(
        binDecimalStats(PrimitiveTypeName.BINARY, BigInt(-150), BigInt(99), dec),
        binDecimalStats(PrimitiveTypeName.BINARY, BigInt(5), BigInt(1000), dec)
      ),
      PrimitiveTypeName.BINARY,
      dec
    ) shouldBe (Some("-1.50"), Some("10.00"))
  }

  it should "decode FIXED_LEN_BYTE_ARRAY decimals" in {
    val dec = LogicalTypeAnnotation.decimalType(4, 30)
    StatsComputer.computeTypedMinMax(
      List(
        binDecimalStats(
          PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY,
          BigInt(-12345),
          BigInt(10).pow(25),
          dec
        )
      ),
      PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY,
      dec
    ) shouldBe (Some("-1.2345"), Some("1000000000000000000000.0000"))
  }

  it should "render STRING-annotated and FIXED_LEN_BYTE_ARRAY values as UTF-8" in {
    val str = Types
      .required(PrimitiveTypeName.BINARY)
      .as(LogicalTypeAnnotation.stringType())
      .named("test")
    val s1 = Statistics.createStats(str).asInstanceOf[BinaryStatistics]
    s1.setMinMax(Binary.fromString("b"), Binary.fromString("y"))
    StatsComputer.computeTypedMinMax(
      List(s1, mkBinaryStats("a", "x")),
      PrimitiveTypeName.BINARY,
      LogicalTypeAnnotation.stringType()
    ) shouldBe (Some("a"), Some("y"))

    val flba = Types.required(PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY).length(2).named("test")
    val s2   = Statistics.createStats(flba).asInstanceOf[BinaryStatistics]
    s2.setMinMax(Binary.fromString("ab"), Binary.fromString("cd"))
    StatsComputer.computeTypedMinMax(
      List(s2),
      PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY,
      null
    ) shouldBe
      (Some("ab"), Some("cd"))
  }

  // INT96 = 8 bytes nanos-of-day + 4 bytes Julian day, both little-endian.
  private def int96(instant: java.time.Instant): Binary = {
    val buf = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val day = Math.floorDiv(instant.getEpochSecond, 86400L)
    buf.putLong(Math.floorMod(instant.getEpochSecond, 86400L) * 1_000_000_000L + instant.getNano)
    buf.putInt((day + 2440588L).toInt)
    Binary.fromConstantByteArray(buf.array())
  }

  private def int96Stats(min: java.time.Instant, max: java.time.Instant): Statistics[?] = {
    val pt    = Types.required(PrimitiveTypeName.INT96).named("test")
    val stats = Statistics.createStats(pt).asInstanceOf[BinaryStatistics]
    stats.setMinMax(int96(min), int96(max))
    stats
  }

  it should "decode INT96 timestamps" in {
    val t0 = java.time.Instant.parse("2024-02-29T12:34:56Z")
    val t1 = java.time.Instant.parse("2025-01-01T00:00:00.000000001Z")
    StatsComputer.computeTypedMinMax(
      List(int96Stats(t0, t1)),
      PrimitiveTypeName.INT96,
      null
    ) shouldBe
      (Some("2024-02-29T12:34:56Z"), Some("2025-01-01T00:00:00.000000001Z"))
  }

  // ── Ordering fixes (these failed before the StatCodec refactor) ──────

  it should "order BINARY/STRING statistics as unsigned bytes (UTF-8 code-point order)" in {
    // 'é' is 0xC3 0xA9 in UTF-8; a signed byte compare ranks it below 'a'.
    StatsComputer.computeTypedMinMax(
      List(mkBinaryStats("a", "a"), mkBinaryStats("é", "é")),
      PrimitiveTypeName.BINARY,
      LogicalTypeAnnotation.stringType()
    ) shouldBe (Some("a"), Some("é"))
  }

  it should "order INT96 timestamps chronologically, not by their display text" in {
    // "…00Z" sorts after "…00.500Z" as text ('Z' > '.'), but is earlier in time.
    val t0 = java.time.Instant.parse("2024-01-01T00:00:00Z")
    val t1 = t0.plusMillis(500)
    StatsComputer.computeTypedMinMax(
      List(int96Stats(t1, t1), int96Stats(t0, t0)),
      PrimitiveTypeName.INT96,
      null
    ) shouldBe (Some("2024-01-01T00:00:00Z"), Some("2024-01-01T00:00:00.500Z"))
  }

  it should "skip undecodable INT96 values" in {
    val pt    = Types.required(PrimitiveTypeName.INT96).named("test")
    val stats = Statistics.createStats(pt).asInstanceOf[BinaryStatistics]
    stats.setMinMax(Binary.fromString("short"), Binary.fromString("short"))
    StatsComputer.computeTypedMinMax(List(stats), PrimitiveTypeName.INT96, null) shouldBe
      (None, None)
  }

  "minMax" should "return (None, None) for an empty list" in {
    StatsComputer.minMax(
      Nil,
      StatsComputer.StatCodec[Int]({ case n: java.lang.Integer => n.intValue() }, _.toString)
    ) shouldBe (None, None)
  }

  it should "skip values the codec does not extract" in {
    val stats = List(mkFloatStats(Float.NaN, 10.0f), mkFloatStats(3.0f, Float.NaN))
    StatsComputer.minMax(
      stats,
      StatsComputer
        .StatCodec[Float]({ case n: java.lang.Float if !n.isNaN => n.floatValue() }, _.toString)
    ) shouldBe (Some("3.0"), Some("10.0"))
  }
}
