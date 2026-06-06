package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{CellValue, FileContent}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LTSVFormatterTest extends AnyFlatSpec with Matchers {

  private val fmt = new LTSVFormatter()

  "LTSVFormatter.formatContent" should "produce one LTSV line per row" in {
    val content = FileContent(
      rows = List(
        Map("id" -> CellValue.I64(1L), "name" -> CellValue.Str("Alice")),
        Map("id" -> CellValue.I64(2L), "name" -> CellValue.Str("Bob"))
      ),
      totalRows = 2L
    )
    val lines = fmt.formatContent(content, None).split("\n")
    lines should have length 2
    lines(0) should include("id:1")
    lines(0) should include("name:Alice")
    lines(1) should include("id:2")
    lines(1) should include("name:Bob")
  }

  it should "use tab as field separator" in {
    val content = FileContent(
      rows = List(Map("a" -> CellValue.Str("x"), "b" -> CellValue.Str("y"))),
      totalRows = 1L
    )
    val line = fmt.formatContent(content, None)
    line should include("\t")
    line.split("\t") should have length 2
  }

  it should "render CellValue.Null as empty value" in {
    val content = FileContent(
      rows = List(Map("x" -> CellValue.Null)),
      totalRows = 1L
    )
    fmt.formatContent(content, None) shouldBe "x:null\n"
  }

  it should "sanitize label chars illegal in LTSV" in {
    val content = FileContent(
      rows = List(Map("bad col!" -> CellValue.Str("v"))),
      totalRows = 1L
    )
    val line = fmt.formatContent(content, None)
    line should not include "!"
    line should include(":")
  }

  it should "replace tabs in values with spaces" in {
    val content = FileContent(
      rows = List(Map("k" -> CellValue.Str("a\tb"))),
      totalRows = 1L
    )
    val line = fmt.formatContent(content, None)
    line should not include "\t\t"
    line shouldBe "k:a b\n"
  }

  it should "replace newlines in values with spaces" in {
    val content = FileContent(
      rows = List(Map("k" -> CellValue.Str("line1\nline2"))),
      totalRows = 1L
    )
    fmt.formatContent(content, None) shouldBe "k:line1 line2\n"
  }

  it should "round-trip with LTSVParser for simple string values" in {
    import io.github.yusukensanta.parqueteer.core.util.LTSVParser
    val rows = List(
      Map(
        "host" -> CellValue.Str("example.com"),
        "status" -> CellValue.I64(200L)
      )
    )
    val content = FileContent(rows = rows, totalRows = 1L)
    val ltsv = fmt.formatContent(content, None)
    val parsed = LTSVParser.parse(ltsv)
    parsed should have size 1
    parsed(0)("host") shouldBe CellValue.Str("example.com")
    parsed(0)("status") shouldBe CellValue.I64(200L)
  }

  it should "emit only data rows when isPartial is true (summary goes to stderr)" in {
    val content = FileContent(
      rows = List(Map("k" -> CellValue.Str("v"))),
      totalRows = 1000L,
      isPartial = true
    )
    val result = fmt.formatContent(content, None)
    val lines = result.split("\n")
    lines should have length 1
    lines(0) should startWith("k:")
    result should not include "#"
  }

  it should "not append a comment when isPartial is false" in {
    val content = FileContent(
      rows = List(Map("k" -> CellValue.Str("v"))),
      totalRows = 1L,
      isPartial = false
    )
    val lines = fmt.formatContent(content, None).split("\n")
    lines should have length 1
    lines(0) should not startWith "#"
  }

  it should "end with a newline (POSIX text file convention)" in {
    val content = FileContent(
      rows =
        List(Map("a" -> CellValue.Str("1")), Map("a" -> CellValue.Str("2"))),
      totalRows = 2L
    )
    fmt.formatContent(content, None) should endWith("\n")
  }

  it should "produce empty string for empty rows" in {
    val content = FileContent(rows = Nil, totalRows = 0L)
    fmt.formatContent(content, None) shouldBe ""
  }
}
