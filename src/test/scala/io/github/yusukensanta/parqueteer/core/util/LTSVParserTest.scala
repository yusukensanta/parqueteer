package io.github.yusukensanta.parqueteer.core.util

import io.github.yusukensanta.parqueteer.core.models.CellValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LTSVParserTest extends AnyFlatSpec with Matchers {

  "LTSVParser.parse" should "parse a single record" in {
    val rows = LTSVParser.parse("host:192.168.0.1\tstatus:200\tsize:1234\n")
    rows should have size 1
    rows(0)("host") shouldBe CellValue.Str("192.168.0.1")
    rows(0)("status") shouldBe CellValue.I64(200L)
    rows(0)("size") shouldBe CellValue.I64(1234L)
  }

  it should "parse multiple records" in {
    val rows = LTSVParser.parse("a:1\tb:hello\na:2\tb:world\n")
    rows should have size 2
    rows(0)("a") shouldBe CellValue.I64(1L)
    rows(1)("b") shouldBe CellValue.Str("world")
  }

  it should "return empty list for empty input" in {
    LTSVParser.parse("") shouldBe empty
  }

  it should "skip blank lines" in {
    val rows = LTSVParser.parse("x:1\n\ny:2\n")
    rows should have size 2
  }

  it should "handle CRLF line endings" in {
    val rows = LTSVParser.parse("a:1\tb:2\r\na:3\tb:4\r\n")
    rows should have size 2
    rows(0)("a") shouldBe CellValue.I64(1L)
    rows(1)("a") shouldBe CellValue.I64(3L)
  }

  it should "infer typed values: bool, date, float, null" in {
    val rows =
      LTSVParser.parse("flag:true\tscore:3.14\tdob:2024-01-15\tempty:\n")
    rows(0)("flag") shouldBe CellValue.Bool(true)
    rows(0)("score") shouldBe CellValue.F64(3.14)
    rows(0)("dob") shouldBe CellValue.Date(java.time.LocalDate.of(2024, 1, 15))
    rows(0)("empty") shouldBe CellValue.Null
  }

  it should "allow colons in values (split on first colon only)" in {
    val rows = LTSVParser.parse("url:http://example.com/path\n")
    rows(0)("url") shouldBe CellValue.Str("http://example.com/path")
  }

  it should "allow labels with digits, underscores, dots, hyphens" in {
    val rows = LTSVParser.parse("field_1:a\tfield.2:b\tfield-3:c\n")
    rows(0)("field_1") shouldBe CellValue.Str("a")
    rows(0)("field.2") shouldBe CellValue.Str("b")
    rows(0)("field-3") shouldBe CellValue.Str("c")
  }

  it should "throw IllegalArgumentException for a field without colon" in {
    an[IllegalArgumentException] should be thrownBy {
      LTSVParser.parse("nocolon\n")
    }
  }

  it should "throw IllegalArgumentException for invalid label characters" in {
    an[IllegalArgumentException] should be thrownBy {
      LTSVParser.parse("bad label:value\n")
    }
  }

  it should "allow records with different labels (self-describing)" in {
    val rows = LTSVParser.parse("id:1\tname:Alice\nid:2\tscore:99\n")
    rows(0).keys should contain("name")
    rows(0).keys should not contain "score"
    rows(1).keys should contain("score")
  }

  it should "preserve label insertion order (not sort alphabetically)" in {
    val rows = LTSVParser.parse("z:3\ta:1\tm:2\n")
    rows(0).keys.toList shouldBe List("z", "a", "m")
  }
}
