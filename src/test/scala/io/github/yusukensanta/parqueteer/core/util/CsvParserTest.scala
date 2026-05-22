package io.github.yusukensanta.parqueteer.core.util

import io.github.yusukensanta.parqueteer.core.formatters.CSVFormatter
import io.github.yusukensanta.parqueteer.core.models.FileContent
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CsvParserTest extends AnyFlatSpec with Matchers {

  "CsvParser.parse" should "parse basic CSV into typed rows" in {
    val rows = CsvParser.parse("a,b\n1,2\n3,4\n")
    rows should have size 2
    rows(0)("a") shouldBe 1L
    rows(0)("b") shouldBe 2L
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
    rows(0)("code") shouldBe "007"
    rows(1)("code") shouldBe "042"
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
    rows(0)("a") shouldBe 1L
    rows(0)("b") shouldBe 2L
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
    rows(0)("a") shouldBe (null: AnyRef)
    rows(0)("b") shouldBe (null: AnyRef)
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

  // ─── CSVFormatter → CsvParser round-trip ────────────────────────────────────

  private def roundTrip(
      rows: List[Map[String, Any]]
  ): List[Map[String, Any]] = {
    val csv = new CSVFormatter().formatContent(
      FileContent(rows, rows.length.toLong, isPartial = false),
      None
    )
    CsvParser.parse(csv)
  }

  "CSVFormatter → CsvParser round-trip" should "preserve string values with embedded double-quotes" in {
    val rows = List(Map("a" -> "say \"hello\"", "b" -> "normal"))
    val parsed = roundTrip(rows)
    parsed should have size 1
    parsed(0)("a") shouldBe "say \"hello\""
    parsed(0)("b") shouldBe "normal"
  }

  it should "preserve string values containing commas" in {
    val rows = List(Map("name" -> "Smith, John", "score" -> "42"))
    val parsed = roundTrip(rows)
    parsed(0)("name") shouldBe "Smith, John"
    parsed(0)("score") shouldBe 42L
  }

  it should "preserve Unicode characters" in {
    val rows = List(Map("greeting" -> "こんにちは", "lang" -> "日本語"))
    val parsed = roundTrip(rows)
    parsed(0)("greeting") shouldBe "こんにちは"
    parsed(0)("lang") shouldBe "日本語"
  }

  it should "preserve multiple rows" in {
    val rows = List(
      Map("id" -> 1L, "val" -> "a,b"),
      Map("id" -> 2L, "val" -> "c\"d")
    )
    val parsed = roundTrip(rows)
    parsed should have size 2
    parsed(0)("val") shouldBe "a,b"
    parsed(1)("val") shouldBe "c\"d"
  }
}
