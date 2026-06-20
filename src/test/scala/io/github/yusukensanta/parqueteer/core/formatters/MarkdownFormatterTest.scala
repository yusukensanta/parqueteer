package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  ColumnInfo,
  FileContent,
  FileMetadata,
  ParquetSchema
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class MarkdownFormatterTest extends AnyFlatSpec with Matchers {

  private val formatter = new MarkdownFormatter()

  private val rows = List(
    Map("id" -> CellValue.I64(1L), "name" -> CellValue.Str("Alice")),
    Map("id" -> CellValue.I64(2L), "name" -> CellValue.Str("Bob"))
  )

  private val content =
    FileContent(rows = rows, totalRows = 2L, isPartial = false)

  private val partialContent =
    FileContent(rows = rows, totalRows = 10L, isPartial = true)

  "MarkdownFormatter.formatContent" should "produce a pipe-separated header and separator" in {
    val result = formatter.formatContent(content, None)
    result should include("| id | name |")
    result should include("| --- | --- |")
  }

  it should "include row values" in {
    val result = formatter.formatContent(content, None)
    result should include("Alice")
    result should include("Bob")
  }

  it should "show total row count when partial" in {
    val result = formatter.formatContent(partialContent, None)
    result should include("10 rows total")
    result should include("showing first 2")
  }

  it should "show simple row count when not partial" in {
    val result = formatter.formatContent(content, None)
    result should include("2 rows")
    result should not include "total"
  }

  it should "return 'No data to display' for empty content" in {
    val empty = FileContent(rows = List.empty, totalRows = 0L)
    formatter.formatContent(empty, None) shouldBe "No data to display"
  }

  it should "escape pipe characters in cell values" in {
    val pipeRow = List(Map("col" -> CellValue.Str("a|b")))
    val result  = formatter.formatContent(FileContent(pipeRow, 1L), None)
    result should include("a\\|b")
  }

  it should "escape backslashes before pipes so a\\| cell renders correctly" in {
    val row    = List(Map("col" -> CellValue.Str("a\\|b")))
    val result = formatter.formatContent(FileContent(row, 1L), None)
    result should include("a\\\\\\|b")
    result should not include "a\\|b|"
  }

  it should "render Double NaN as NaN" in {
    val result =
      formatter.formatContent(
        FileContent(List(Map("v" -> CellValue.F64(Double.NaN))), 1L),
        None
      )
    result should include("NaN")
  }

  it should "render Double Infinity as Infinity" in {
    val result = formatter.formatContent(
      FileContent(List(Map("v" -> CellValue.F64(Double.PositiveInfinity))), 1L),
      None
    )
    result should include("Infinity")
  }

  it should "render Double with full precision" in {
    val result = formatter.formatContent(
      FileContent(List(Map("v" -> CellValue.F64(1.234567))), 1L),
      None
    )
    result should include("1.234567")
  }

  it should "render BigDecimal without scientific notation" in {
    val result = formatter.formatContent(
      FileContent(
        List(Map("amount" -> CellValue.Dec(BigDecimal("0.0000001")))),
        1L
      ),
      None
    )
    result should include("0.0000001")
    result should not include "E"
  }

  it should "render Boolean values as true/false" in {
    val result = formatter.formatContent(
      FileContent(
        List(
          Map("flag" -> CellValue.Bool(true)),
          Map("flag" -> CellValue.Bool(false))
        ),
        2L
      ),
      None
    )
    result should include("true")
    result should include("false")
  }

  it should "render Int (INT32) values" in {
    val result = formatter.formatContent(
      FileContent(List(Map("count" -> CellValue.I32(42))), 1L),
      None
    )
    result should include("42")
  }

  "MarkdownFormatter.formatSchema" should "produce a markdown header and table" in {
    val schema = ParquetSchema(
      columns = List(ColumnInfo("id", "INT64", isOptional = false, 1, 0, "SNAPPY")),
      rowGroupCount = 1L,
      totalRowCount = 100L
    )
    val result = formatter.formatSchema(schema)
    result should include("## Schema")
    result should include("| Name | Type | Optional | Compression |")
    result should include("| id | INT64 | No | SNAPPY |")
  }

  "MarkdownFormatter.formatMetadata" should "produce a markdown metadata section" in {
    val metadata = FileMetadata(
      fileSize = 1024L,
      createdAt = Some(Instant.parse("2024-01-01T00:00:00Z")),
      modifiedAt = None,
      compressionRatio = Some(2.5),
      version = "parquet-mr 1.12",
      createdBy = Some("test")
    )
    val result = formatter.formatMetadata(metadata)
    result should include("## Metadata")
    result should include("1024")
    result should include("2.50")
    result should include("parquet-mr 1.12")
  }
}
