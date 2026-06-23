package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.CellValue
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DataFileReaderTest extends AnyFlatSpec with Matchers {

  "coerceJsonValue" should "convert string to CellValue" in {
    DataFileReader.coerceJsonValue(Json.fromString("hello")) shouldBe CellValue.Str("hello")
  }

  it should "convert true boolean" in {
    DataFileReader.coerceJsonValue(Json.fromBoolean(true)) shouldBe CellValue.Bool(true)
  }

  it should "convert false boolean" in {
    DataFileReader.coerceJsonValue(Json.fromBoolean(false)) shouldBe CellValue.Bool(false)
  }

  it should "convert null to Null" in {
    DataFileReader.coerceJsonValue(Json.Null) shouldBe CellValue.Null
  }

  it should "convert integer to I64" in {
    DataFileReader.coerceJsonValue(Json.fromInt(42)) shouldBe CellValue.I64(42L)
  }

  it should "convert long to I64" in {
    DataFileReader.coerceJsonValue(Json.fromLong(9999999999L)) shouldBe CellValue.I64(9999999999L)
  }

  it should "convert float-like double to F64" in {
    val j = io.circe.parser.parse("3.14").toOption.get
    DataFileReader.coerceJsonValue(j) shouldBe CellValue.F64(3.14)
  }

  it should "convert whole number with dot to F64 within safe integer range" in {
    val j      = io.circe.parser.parse("1.0").toOption.get
    val result = DataFileReader.coerceJsonValue(j)
    result shouldBe a[CellValue.F64]
  }

  it should "convert large whole decimal beyond F64 safe range to I64" in {
    val j      = io.circe.parser.parse("9007199254740993.0").toOption.get
    val result = DataFileReader.coerceJsonValue(j)
    result shouldBe CellValue.I64(9007199254740993L)
  }

  it should "convert large non-whole decimal beyond F64 safe range to Dec" in {
    val j      = io.circe.parser.parse("9007199254740993.5").toOption.get
    val result = DataFileReader.coerceJsonValue(j)
    result shouldBe a[CellValue.Dec]
  }

  it should "convert scientific notation with whole mantissa to I64" in {
    val j      = io.circe.parser.parse("1.0e3").toOption.get
    val result = DataFileReader.coerceJsonValue(j)
    result shouldBe CellValue.I64(1000L)
  }

  it should "convert scientific notation with fractional mantissa to F64" in {
    val j      = io.circe.parser.parse("1.5e2").toOption.get
    val result = DataFileReader.coerceJsonValue(j)
    result shouldBe CellValue.F64(150.0)
  }

  it should "convert integer beyond Long to Dec" in {
    val j      = io.circe.parser.parse("99999999999999999999").toOption.get
    val result = DataFileReader.coerceJsonValue(j)
    result shouldBe a[CellValue.Dec]
  }

  it should "convert JSON object to Str" in {
    val j      = Json.obj("key" -> Json.fromString("value"))
    val result = DataFileReader.coerceJsonValue(j)
    result shouldBe a[CellValue.Str]
  }

  it should "convert JSON array to Str" in {
    val j      = Json.arr(Json.fromInt(1), Json.fromInt(2))
    val result = DataFileReader.coerceJsonValue(j)
    result shouldBe a[CellValue.Str]
  }

  it should "infer date strings via TypeInferrer" in {
    val result = DataFileReader.coerceJsonValue(Json.fromString("2024-01-15"))
    result shouldBe a[CellValue.Date]
  }

  it should "keep numeric strings as Str via TypeInferrer" in {
    val result = DataFileReader.coerceJsonValue(Json.fromString("42"))
    result shouldBe CellValue.Str("42")
  }

  it should "convert zero to I64" in {
    DataFileReader.coerceJsonValue(Json.fromInt(0)) shouldBe CellValue.I64(0L)
  }

  it should "convert negative integer to I64" in {
    DataFileReader.coerceJsonValue(Json.fromInt(-7)) shouldBe CellValue.I64(-7L)
  }

  it should "convert negative double to F64" in {
    val j = io.circe.parser.parse("-2.5").toOption.get
    DataFileReader.coerceJsonValue(j) shouldBe CellValue.F64(-2.5)
  }
}
