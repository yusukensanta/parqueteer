package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OutputFormatterTest extends AnyFlatSpec with Matchers {

  private val simpleContent = FileContent(
    rows = List(Map("id" -> CellValue.I64(1L), "val" -> CellValue.Str("x"))),
    totalRows = 1L,
    isPartial = false
  )

  "OutputFormatter.apply" should "return a TableFormatter for Table" in {
    val fmt = OutputFormatter(OutputFormat.Table)
    fmt shouldBe a[TableFormatter]
    fmt.formatContent(simpleContent, None) should include("id")
  }

  it should "return a JSONFormatter for JSON" in {
    val fmt = OutputFormatter(OutputFormat.JSON)
    fmt shouldBe a[JSONFormatter]
    fmt.formatContent(simpleContent, None) should include("\"id\"")
  }

  it should "return a CSVFormatter for CSV" in {
    val fmt = OutputFormatter(OutputFormat.CSV)
    fmt shouldBe a[CSVFormatter]
    fmt.formatContent(simpleContent, None) should include("id")
  }

  it should "return a PrettyFormatter for Pretty" in {
    val fmt = OutputFormatter(OutputFormat.Pretty, useColors = false)
    fmt shouldBe a[PrettyFormatter]
    fmt.formatContent(simpleContent, None) should include("id")
  }

  it should "return a MarkdownFormatter for Markdown" in {
    val fmt = OutputFormatter(OutputFormat.Markdown)
    fmt shouldBe a[MarkdownFormatter]
    fmt.formatContent(simpleContent, None) should include("|")
  }

  it should "return a NDJSONFormatter for NDJSON" in {
    val fmt = OutputFormatter(OutputFormat.NDJSON)
    fmt shouldBe a[NDJSONFormatter]
    fmt.formatContent(simpleContent, None) should include("{")
  }

  it should "return a LTSVFormatter for LTSV" in {
    val fmt = OutputFormatter(OutputFormat.LTSV)
    fmt shouldBe a[LTSVFormatter]
    fmt.formatContent(simpleContent, None) should include(":")
  }

  "OutputFormatter.extractColumns" should "order by schema when schema provided" in {
    val schema = ParquetSchema(
      columns = List(
        ColumnInfo("b", "INT64", isOptional = false, 1, 0, "SNAPPY"),
        ColumnInfo("a", "INT64", isOptional = false, 1, 0, "SNAPPY")
      ),
      rowGroupCount = 1L,
      totalRowCount = 1L
    )
    val rows         = List(Map("a" -> CellValue.I64(1L), "b" -> CellValue.I64(2L)))
    val fmt          = new TableFormatter()
    val result       = fmt.formatContent(FileContent(rows, 1L, false), Some(schema))
    val firstColIdx  = result.indexOf("b")
    val secondColIdx = result.indexOf("a")
    firstColIdx should be < secondColIdx
  }

  it should "use row insertion order when no schema" in {
    val rows = List(
      Map("z" -> CellValue.I64(1L), "a" -> CellValue.I64(2L)),
      Map("z" -> CellValue.I64(3L), "a" -> CellValue.I64(4L))
    )
    val fmt    = new TableFormatter()
    val result = fmt.formatContent(FileContent(rows, 2L, false), None)
    val zIdx   = result.indexOf("z")
    val aIdx   = result.indexOf("a")
    zIdx should be < aIdx
  }

  it should "union columns from multiple rows when no schema" in {
    val rows = List(
      Map("a" -> CellValue.I64(1L)),
      Map("b" -> CellValue.Str("x"))
    )
    val fmt    = OutputFormatter(OutputFormat.Table)
    val result = fmt.formatContent(FileContent(rows, 2L, false), None)
    result should include("a")
    result should include("b")
  }
}
