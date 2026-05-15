package io.github.yusukensanta.parqueteer.core.util

import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JsonEncoderTest extends AnyFlatSpec with Matchers {

  "JsonEncoder.encodeAny" should "encode null as Json.Null" in {
    JsonEncoder.encodeAny(null) shouldBe Json.Null
  }

  it should "encode String" in {
    JsonEncoder.encodeAny("hello") shouldBe Json.fromString("hello")
  }

  it should "encode Int" in {
    JsonEncoder.encodeAny(42: Int) shouldBe Json.fromInt(42)
  }

  it should "encode Long" in {
    JsonEncoder.encodeAny(100L) shouldBe Json.fromLong(100L)
  }

  it should "encode Double" in {
    JsonEncoder.encodeAny(3.14) shouldBe Json.fromDoubleOrNull(3.14)
  }

  it should "encode NaN Double as Json.Null" in {
    JsonEncoder.encodeAny(Double.NaN) shouldBe Json.Null
  }

  it should "encode Infinity Double as Json.Null" in {
    JsonEncoder.encodeAny(Double.PositiveInfinity) shouldBe Json.Null
  }

  it should "encode Boolean" in {
    JsonEncoder.encodeAny(true) shouldBe Json.fromBoolean(true)
    JsonEncoder.encodeAny(false) shouldBe Json.fromBoolean(false)
  }

  it should "encode List recursively" in {
    val result = JsonEncoder.encodeAny(List("a", "b"))
    result shouldBe Json.arr(Json.fromString("a"), Json.fromString("b"))
  }

  it should "encode Map as JSON object" in {
    val result = JsonEncoder.encodeAny(Map("k" -> "v"))
    result shouldBe Json.obj("k" -> Json.fromString("v"))
  }

  it should "encode Some(value) as the inner value" in {
    JsonEncoder.encodeAny(Some("x")) shouldBe Json.fromString("x")
  }

  it should "encode None as Json.Null" in {
    JsonEncoder.encodeAny(None) shouldBe Json.Null
  }

  it should "encode Array[Byte] as Base64 string" in {
    val bytes = "hello".getBytes
    val result = JsonEncoder.encodeAny(bytes)
    result.asString.get should not be empty
  }

  it should "encode unknown types as toString" in {
    JsonEncoder.encodeAny(
      java.util.UUID.fromString("00000000-0000-0000-0000-000000000000")
    ) shouldBe
      Json.fromString("00000000-0000-0000-0000-000000000000")
  }
}
