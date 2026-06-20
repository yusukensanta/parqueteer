package io.github.yusukensanta.parqueteer.core.util

import io.github.yusukensanta.parqueteer.core.formatters.CSVFormatter
import io.github.yusukensanta.parqueteer.core.models.{CellValue, FileContent}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen

class CsvParserTest extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  // Generator: alpha-only strings that TypeInferrer won't re-infer as another type
  private val safeStr: Gen[String] = for {
    head <- Gen.alphaChar
    tail <- Gen.listOf(Gen.alphaChar).map(_.mkString)
    s = head.toString + tail
    if !s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")
  } yield s

  // Generator: column names safe for CSV headers (no commas, quotes, or newlines)
  private val colName: Gen[String] = Gen.identifier.filter(s =>
    s.nonEmpty && !s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")
  )

  "CsvParser.parse" should "parse basic CSV into typed rows" in {
    val rows = CsvParser.parse("a,b\n1,2\n3,4\n")
    rows should have size 2
    rows(0)("a") shouldBe CellValue.I64(1L)
    rows(0)("b") shouldBe CellValue.I64(2L)
  }

  it should "return empty list for empty input" in {
    CsvParser.parse("") shouldBe empty
  }

  it should "infer date strings" in {
    val rows = CsvParser.parse("name,dob\nAlice,1990-06-15\nBob,2001-12-01\n")
    rows(0)("dob").toString should include("1990")
  }

  it should "preserve leading zeros as strings" in {
    val rows = CsvParser.parse("code\n007\n042\n")
    rows(0)("code") shouldBe CellValue.Str("007")
    rows(1)("code") shouldBe CellValue.Str("042")
  }

  it should "reject rows with wrong field count" in {
    an[IllegalArgumentException] should be thrownBy {
      CsvParser.parse("a,b\n1,2,3\n")
    }
  }

  // ── RFC 4180 compliance: trailing empty field (#126) ─────────────────────

  it should "accept a data row with a trailing comma when header has one fewer column" in {
    val rows = CsvParser.parse("a,b\n1,2,\n")
    rows should have size 1
    rows(0)("a") shouldBe CellValue.I64(1L)
    rows(0)("b") shouldBe CellValue.I64(2L)
  }

  it should "not strip a trailing comma that is NOT the (header+1)th field" in {
    // header has 2 cols, row has 4 fields → genuine mismatch, should still throw
    an[IllegalArgumentException] should be thrownBy {
      CsvParser.parse("a,b\n1,2,3,\n")
    }
  }

  // ── RFC 4180 compliance: all-empty rows (#126) ───────────────────────────

  it should "preserve a row where every field is empty (e.g. ,,,)" in {
    val records = CsvParser.parseRfc4180("a,b,c\n,,\n")
    records should have size 2 // header + data row of all-empty fields
    records(1) shouldBe Array("", "", "")
  }

  it should "preserve an all-empty row in parse and include it as a row with null values" in {
    val rows = CsvParser.parse("a,b\n,\n")
    rows should have size 1
    rows(0)("a") shouldBe CellValue.Null
    rows(0)("b") shouldBe CellValue.Null
  }

  it should "drop genuinely blank lines (only a newline, no commas)" in {
    val records = CsvParser.parseRfc4180("a,b\n1,2\n\n3,4\n")
    records should have size 3 // header + 2 data rows, blank line skipped
  }

  // ── RFC 4180 compliance: mid-field quote (#126) ──────────────────────────

  it should "treat an unescaped quote mid-field as a literal character" in {
    val records = CsvParser.parseRfc4180("foo\"bar,baz\n")
    records should have size 1
    records(0)(0) shouldBe "foo\"bar"
    records(0)(1) shouldBe "baz"
  }

  it should "not enter quote mode when quote appears after field content" in {
    val records = CsvParser.parseRfc4180("a\",b\n")
    records should have size 1
    records(0)(0) shouldBe "a\""
    records(0)(1) shouldBe "b"
  }

  // ── RFC 4180 compliance: content after closing quote (#C6) ───────────────

  it should "throw when a non-delimiter character follows a closing quote" in {
    // "a"b is malformed — RFC 4180 §2.5 requires delimiter or EOF after closing quote
    an[IllegalArgumentException] should be thrownBy CsvParser.parseRfc4180(
      "\"a\"b,c\n"
    )
  }

  it should "not throw for a valid quoted field followed by comma" in {
    val records = CsvParser.parseRfc4180("\"a\",b\n")
    records should have size 1
    records(0)(0) shouldBe "a"
    records(0)(1) shouldBe "b"
  }

  it should "not throw for a valid quoted field followed by newline" in {
    val records = CsvParser.parseRfc4180("\"hello\"\n")
    records should have size 1
    records(0)(0) shouldBe "hello"
  }

  // ── pre-existing RFC 4180 features (regression) ──────────────────────────

  "CsvParser.parseRfc4180" should "handle quoted fields with commas" in {
    val records = CsvParser.parseRfc4180("\"a,b\",c\n\"x,y\",z\n")
    records should have size 2
    records(0)(0) shouldBe "a,b"
    records(1)(1) shouldBe "z"
  }

  it should "handle escaped quotes inside quoted fields" in {
    val records = CsvParser.parseRfc4180("\"say \"\"hi\"\"\",b\n")
    records(0)(0) shouldBe "say \"hi\""
  }

  it should "handle CRLF line endings" in {
    val records = CsvParser.parseRfc4180("a,b\r\n1,2\r\n")
    records should have size 2
    records(1)(0) shouldBe "1"
  }

  it should "handle a quoted field containing a newline" in {
    val records = CsvParser.parseRfc4180("a,\"line1\nline2\",b\n")
    records should have size 1
    records(0)(1) shouldBe "line1\nline2"
  }

  it should "handle content without a trailing newline" in {
    val records = CsvParser.parseRfc4180("a,b\n1,2")
    records should have size 2
    records(1)(0) shouldBe "1"
  }

  it should "throw IllegalArgumentException for unterminated quoted field" in {
    an[IllegalArgumentException] should be thrownBy CsvParser.parseRfc4180(
      "a,\"unterminated\n"
    )
    an[IllegalArgumentException] should be thrownBy CsvParser.parseRfc4180(
      "\"no end"
    )
  }

  // ─── CSVFormatter → CsvParser round-trip ────────────────────────────────────

  private def roundTrip(
      rows: List[Map[String, CellValue]]
  ): List[Map[String, CellValue]] = {
    val csv = new CSVFormatter().formatContent(
      FileContent(rows, rows.length.toLong, isPartial = false),
      None
    )
    CsvParser.parse(csv)
  }

  "CSVFormatter → CsvParser round-trip" should "preserve string values with embedded double-quotes" in {
    val rows =
      List(
        Map(
          "a" -> CellValue.Str("say \"hello\""),
          "b" -> CellValue.Str("normal")
        )
      )
    val parsed = roundTrip(rows)
    parsed should have size 1
    parsed(0)("a") shouldBe CellValue.Str("say \"hello\"")
    parsed(0)("b") shouldBe CellValue.Str("normal")
  }

  it should "preserve string values containing commas" in {
    val rows =
      List(
        Map(
          "name"  -> CellValue.Str("Smith, John"),
          "score" -> CellValue.Str("42")
        )
      )
    val parsed = roundTrip(rows)
    parsed(0)("name") shouldBe CellValue.Str("Smith, John")
    parsed(0)("score") shouldBe CellValue.I64(42L)
  }

  it should "preserve Unicode characters" in {
    val rows = List(
      Map(
        "greeting" -> CellValue.Str("こんにちは"),
        "lang"     -> CellValue.Str("日本語")
      )
    )
    val parsed = roundTrip(rows)
    parsed(0)("greeting") shouldBe CellValue.Str("こんにちは")
    parsed(0)("lang") shouldBe CellValue.Str("日本語")
  }

  it should "preserve multiple rows" in {
    val rows = List(
      Map("id" -> CellValue.I64(1L), "val" -> CellValue.Str("a,b")),
      Map("id" -> CellValue.I64(2L), "val" -> CellValue.Str("c\"d"))
    )
    val parsed = roundTrip(rows)
    parsed should have size 2
    parsed(0)("val") shouldBe CellValue.Str("a,b")
    parsed(1)("val") shouldBe CellValue.Str("c\"d")
  }

  // ── Property-based: CSVFormatter → CsvParser round-trip ─────────────────
  // Rows of safe alpha-only strings survive the encode→parse round-trip intact.

  "CSVFormatter → CsvParser round-trip (property)" should "preserve any safe string value" in {
    forAll(colName, colName, safeStr, safeStr) { (col1, col2, v1, v2) =>
      whenever(col1 != col2) {
        val rows =
          List(Map(col1 -> CellValue.Str(v1), col2 -> CellValue.Str(v2)))
        val parsed = roundTrip(rows)
        parsed should have size 1
        parsed(0)(col1) shouldBe CellValue.Str(v1)
        parsed(0)(col2) shouldBe CellValue.Str(v2)
      }
    }
  }

  it should "preserve row count for multi-row input" in {
    val rowGen = for {
      c  <- colName
      vs <- Gen.nonEmptyListOf(safeStr)
    } yield vs.map(v => Map(c -> CellValue.Str(v)))
    forAll(rowGen) { rows =>
      roundTrip(rows) should have size rows.size
    }
  }
}
