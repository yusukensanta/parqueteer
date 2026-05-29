package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CSVFormatterTest extends AnyFlatSpec with Matchers {

  private val formatter = new CSVFormatter()

  private val sampleContent = FileContent(
    rows = List(
      Map("age" -> CellValue.I64(30L), "name" -> CellValue.Str("Alice")),
      Map("age" -> CellValue.I64(25L), "name" -> CellValue.Str("Bob"))
    ),
    totalRows = 2L,
    isPartial = false
  )

  private val sampleSchema = ParquetSchema(
    columns = List(
      ColumnInfo("name", "BINARY", isOptional = true, 1, 0, "SNAPPY"),
      ColumnInfo("age", "INT64", isOptional = false, 1, 0, "SNAPPY")
    ),
    rowGroupCount = 1L,
    totalRowCount = 2L
  )

  "CSVFormatter.formatContent" should "produce header row from column names" in {
    val result = formatter.formatContent(sampleContent, None)
    val lines = result.split("\r\n")
    lines.head should include("age")
    lines.head should include("name")
  }

  it should "produce correct number of lines (header + 2 data)" in {
    val result = formatter.formatContent(sampleContent, None)
    val lines = result.strip().split("\r\n")
    lines.length shouldBe 3
  }

  it should "contain row values" in {
    val result = formatter.formatContent(sampleContent, None)
    result should include("Alice")
    result should include("Bob")
    result should include("30")
  }

  it should "return empty string for empty rows" in {
    val empty = FileContent(List.empty, 0L, false)
    formatter.formatContent(empty, None) shouldBe ""
  }

  it should "quote fields containing commas" in {
    val withComma = FileContent(
      rows = List(Map("desc" -> CellValue.Str("hello, world"))),
      totalRows = 1L,
      isPartial = false
    )
    val result = formatter.formatContent(withComma, None)
    result should include(""""hello, world"""")
  }

  it should "escape internal quotes by doubling them" in {
    val withQuote = FileContent(
      rows = List(Map("desc" -> CellValue.Str("""say "hi""""))),
      totalRows = 1L,
      isPartial = false
    )
    val result = formatter.formatContent(withQuote, None)
    result should include(""""say ""hi""""")
  }

  it should "render null as empty field" in {
    val withNull = FileContent(
      rows = List(Map("key" -> CellValue.Null)),
      totalRows = 1L,
      isPartial = false
    )
    val result = formatter.formatContent(withNull, None)
    val lines = result.split("\r\n", -1)
    lines.length shouldBe 3
    lines(0) shouldBe "key"
    lines(1) shouldBe ""
  }

  it should "render Double NaN as NaN string" in {
    val content = FileContent(
      rows = List(Map("val" -> CellValue.F64(Double.NaN))),
      totalRows = 1L,
      isPartial = false
    )
    val lines = formatter.formatContent(content, None).strip().split("\r\n")
    lines(1) shouldBe "NaN"
  }

  it should "render Double Infinity as Infinity string" in {
    val content = FileContent(
      rows = List(Map("val" -> CellValue.F64(Double.PositiveInfinity))),
      totalRows = 1L,
      isPartial = false
    )
    val lines = formatter.formatContent(content, None).strip().split("\r\n")
    lines(1) shouldBe "Infinity"
  }

  it should "render BigDecimal without scientific notation" in {
    val content = FileContent(
      rows = List(Map("amount" -> CellValue.Dec(BigDecimal("0.0000001")))),
      totalRows = 1L,
      isPartial = false
    )
    val result = formatter.formatContent(content, None)
    result should include("0.0000001")
    result should not include "E"
  }

  it should "render Float values as plain decimal string" in {
    val content = FileContent(
      rows = List(Map("ratio" -> CellValue.F32(1.5f))),
      totalRows = 1L,
      isPartial = false
    )
    val lines = formatter.formatContent(content, None).strip().split("\r\n")
    lines(1) shouldBe "1.5"
  }

  it should "render Boolean values as true/false" in {
    val content = FileContent(
      rows = List(
        Map("active" -> CellValue.Bool(true)),
        Map("active" -> CellValue.Bool(false))
      ),
      totalRows = 2L,
      isPartial = false
    )
    val result = formatter.formatContent(content, None)
    result should include("true")
    result should include("false")
  }

  "CSVFormatter.formatSchema" should "include header with Column Name and Data Type" in {
    val result = formatter.formatSchema(sampleSchema)
    result should include("Column Name")
    result should include("Data Type")
  }

  it should "include column rows" in {
    val result = formatter.formatSchema(sampleSchema)
    result should include("name")
    result should include("BINARY")
  }
}
