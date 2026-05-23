package io.github.yusukensanta.parqueteer.core.util

import io.github.yusukensanta.parqueteer.core.models.CellValue
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JsonEncoderTest extends AnyFlatSpec with Matchers {

  "JsonEncoder.encode" should "encode CellValue.Null as Json.Null" in {
    JsonEncoder.encode(CellValue.Null) shouldBe Json.Null
  }

  it should "encode CellValue.Str" in {
    JsonEncoder.encode(CellValue.Str("hello")) shouldBe Json.fromString("hello")
  }

  it should "encode CellValue.I32" in {
    JsonEncoder.encode(CellValue.I32(42)) shouldBe Json.fromInt(42)
  }

  it should "encode CellValue.I64" in {
    JsonEncoder.encode(CellValue.I64(100L)) shouldBe Json.fromLong(100L)
  }

  it should "encode CellValue.F64" in {
    JsonEncoder.encode(CellValue.F64(3.14)) shouldBe Json.fromDoubleOrNull(3.14)
  }

  it should "encode CellValue.F64(NaN) as string \"NaN\"" in {
    JsonEncoder.encode(CellValue.F64(Double.NaN)) shouldBe Json.fromString(
      "NaN"
    )
  }

  it should "encode CellValue.F64(+Infinity) as string \"Infinity\"" in {
    JsonEncoder.encode(
      CellValue.F64(Double.PositiveInfinity)
    ) shouldBe Json.fromString("Infinity")
  }

  it should "encode CellValue.F64(-Infinity) as string \"-Infinity\"" in {
    JsonEncoder.encode(
      CellValue.F64(Double.NegativeInfinity)
    ) shouldBe Json.fromString("-Infinity")
  }

  it should "encode CellValue.Bool(true)" in {
    JsonEncoder.encode(CellValue.Bool(true)) shouldBe Json.fromBoolean(true)
  }

  it should "encode CellValue.Bool(false)" in {
    JsonEncoder.encode(CellValue.Bool(false)) shouldBe Json.fromBoolean(false)
  }

  it should "encode CellValue.Bytes as Base64 string" in {
    val bytes = "hello".getBytes
    val result = JsonEncoder.encode(CellValue.Bytes(bytes))
    result shouldBe Json.fromString(
      java.util.Base64.getEncoder.encodeToString(bytes)
    )
  }

  it should "encode CellValue.F32" in {
    JsonEncoder.encode(CellValue.F32(1.5f)) shouldBe Json.fromFloatOrNull(1.5f)
  }

  it should "encode CellValue.F32(NaN) as string \"NaN\"" in {
    JsonEncoder.encode(CellValue.F32(Float.NaN)) shouldBe Json.fromString("NaN")
  }

  it should "encode CellValue.F32(+Infinity) as string \"Infinity\"" in {
    JsonEncoder.encode(
      CellValue.F32(Float.PositiveInfinity)
    ) shouldBe Json.fromString("Infinity")
  }

  it should "encode CellValue.F32(-Infinity) as string \"-Infinity\"" in {
    JsonEncoder.encode(
      CellValue.F32(Float.NegativeInfinity)
    ) shouldBe Json.fromString("-Infinity")
  }

  it should "encode CellValue.Dec as BigDecimal JSON" in {
    JsonEncoder.encode(
      CellValue.Dec(BigDecimal("3.14"))
    ) shouldBe Json.fromBigDecimal(BigDecimal("3.14"))
  }

  it should "encode CellValue.Date as ISO date string" in {
    val date = java.time.LocalDate.of(2024, 1, 15)
    JsonEncoder.encode(CellValue.Date(date)) shouldBe Json.fromString(
      "2024-01-15"
    )
  }

  it should "encode CellValue.Ts as ISO instant string" in {
    val instant = java.time.Instant.parse("2024-01-15T12:00:00Z")
    JsonEncoder.encode(CellValue.Ts(instant)) shouldBe Json.fromString(
      instant.toString
    )
  }
}
