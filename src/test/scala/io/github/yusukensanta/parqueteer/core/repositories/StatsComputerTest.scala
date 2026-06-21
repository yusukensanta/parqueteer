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

  "formatStatVal" should "format Binary values as UTF-8 strings" in {
    val bin = Binary.fromString("hello")
    StatsComputer.formatStatVal(bin, PrimitiveTypeName.BINARY) shouldBe "hello"
  }

  it should "format non-Binary values with toString" in {
    StatsComputer.formatStatVal(42, PrimitiveTypeName.INT32) shouldBe "42"
  }

  it should "format FIXED_LEN_BYTE_ARRAY Binary as UTF-8" in {
    val bin = Binary.fromString("test")
    StatsComputer.formatStatVal(
      bin,
      PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY
    ) shouldBe "test"
  }

  "numericMinMax" should "return (None, None) for empty list" in {
    val result = StatsComputer.numericMinMax[Int](
      Nil,
      { case n: java.lang.Integer => n.intValue() }
    )
    result shouldBe (None, None)
  }

  it should "apply filter predicate" in {
    val stats = List(mkFloatStats(Float.NaN, 10.0f), mkFloatStats(3.0f, Float.NaN))
    val result = StatsComputer.numericMinMax[Float](
      stats,
      { case n: java.lang.Float => n.floatValue() },
      filter = v => !v.isNaN
    )
    result._1 shouldBe Some("3.0")
    result._2 shouldBe Some("10.0")
  }
}
