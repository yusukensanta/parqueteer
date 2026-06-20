package io.github.yusukensanta.parqueteer.core.repositories

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName

class StatsComputerTest extends AnyFlatSpec with Matchers {

  "computeTypedMinMax" should "return (None, None) for empty statistics list" in {
    val result = StatsComputer.computeTypedMinMax(Nil, PrimitiveTypeName.INT32, null)
    result shouldBe (None, None)
  }

  "formatStatVal" should "format Binary values as UTF-8 strings" in {
    val bin = org.apache.parquet.io.api.Binary.fromString("hello")
    StatsComputer.formatStatVal(bin, PrimitiveTypeName.BINARY) shouldBe "hello"
  }

  it should "format non-Binary values with toString" in {
    StatsComputer.formatStatVal(42, PrimitiveTypeName.INT32) shouldBe "42"
  }

  it should "format FIXED_LEN_BYTE_ARRAY Binary as UTF-8" in {
    val bin = org.apache.parquet.io.api.Binary.fromString("test")
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
}
