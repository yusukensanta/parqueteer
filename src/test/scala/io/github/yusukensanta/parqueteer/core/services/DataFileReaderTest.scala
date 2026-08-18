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

  // ── withNdjsonRows / withLtsvRows / withCsvRows ──────────────────────────

  private def tempFileWithContent(suffix: String, content: String): java.io.File = {
    val f = java.nio.file.Files.createTempFile("datafilereader_test_", suffix).toFile
    java.nio.file.Files.writeString(f.toPath, content)
    f.deleteOnExit()
    f
  }

  "withNdjsonRows" should "yield the same rows as readNdjsonFile" in {
    val f         = tempFileWithContent(".ndjson", "{\"a\":1}\n{\"a\":2}\n{\"a\":3}\n")
    val viaHelper = DataFileReader.withNdjsonRows(f.getAbsolutePath, None)(_.toList).get
    val viaLegacy = DataFileReader.readNdjsonFile(f.getAbsolutePath, None).get
    viaHelper shouldBe viaLegacy
    viaHelper.map(_("a")) shouldBe List(CellValue.I64(1L), CellValue.I64(2L), CellValue.I64(3L))
  }

  it should "truncate to maxRows, same as readNdjsonFile" in {
    val f      = tempFileWithContent(".ndjson", "{\"a\":1}\n{\"a\":2}\n{\"a\":3}\n")
    val result = DataFileReader.withNdjsonRows(f.getAbsolutePath, Some(2L))(_.toList).get
    result should have size 2
  }

  it should "close the file handle after the callback returns" in {
    val f = tempFileWithContent(".ndjson", "{\"a\":1}\n")
    DataFileReader.withNdjsonRows(f.getAbsolutePath, None)(_.toList).get
    // A second read on the same path succeeding proves the first Source was closed,
    // not left open holding a lock/handle.
    DataFileReader.withNdjsonRows(f.getAbsolutePath, None)(_.toList).isSuccess shouldBe true
  }

  "withLtsvRows" should "yield the same rows as readLtsvFile" in {
    val f         = tempFileWithContent(".ltsv", "a:1\tb:x\na:2\tb:y\n")
    val viaHelper = DataFileReader.withLtsvRows(f.getAbsolutePath, None)(_.toList).get
    val viaLegacy = DataFileReader.readLtsvFile(f.getAbsolutePath, None).get
    viaHelper shouldBe viaLegacy
    viaHelper should have size 2
  }

  it should "truncate to maxRows, same as readLtsvFile" in {
    val f      = tempFileWithContent(".ltsv", "a:1\na:2\na:3\n")
    val result = DataFileReader.withLtsvRows(f.getAbsolutePath, Some(1L))(_.toList).get
    result should have size 1
  }

  "withCsvRows" should "yield the same rows as readCsvFile" in {
    val f         = tempFileWithContent(".csv", "a,b\n1,x\n2,y\n")
    val viaHelper = DataFileReader.withCsvRows(f.getAbsolutePath, None)(_.toList).get
    val viaLegacy = DataFileReader.readCsvFile(f.getAbsolutePath, None).get
    viaHelper shouldBe viaLegacy
    viaHelper should have size 2
  }

  it should "truncate to maxRows, same as readCsvFile" in {
    val f      = tempFileWithContent(".csv", "a\n1\n2\n3\n")
    val result = DataFileReader.withCsvRows(f.getAbsolutePath, Some(2L))(_.toList).get
    result should have size 2
  }

  it should "correctly parse a quoted field containing a newline read from disk" in {
    val f      = tempFileWithContent(".csv", "a,b\n1,\"line1\nline2\"\n3,4\n")
    val result = DataFileReader.withCsvRows(f.getAbsolutePath, None)(_.toList).get
    result should have size 2
    result(0)("b") shouldBe CellValue.Str("line1\nline2")
  }

  it should "close the file handle after the callback returns" in {
    val f = tempFileWithContent(".csv", "a\n1\n")
    DataFileReader.withCsvRows(f.getAbsolutePath, None)(_.toList).get
    DataFileReader.withCsvRows(f.getAbsolutePath, None)(_.toList).isSuccess shouldBe true
  }
}
