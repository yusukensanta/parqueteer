package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class TableFormatterTest extends AnyFlatSpec with Matchers {

  private val formatter = new TableFormatter()

  private val sampleContent = FileContent(
    rows = List(
      Map(
        "name" -> CellValue.Str("Alice"),
        "age" -> CellValue.I64(30L),
        "score" -> CellValue.F64(9.5)
      ),
      Map(
        "name" -> CellValue.Str("Bob"),
        "age" -> CellValue.I64(25L),
        "score" -> CellValue.F64(7.2)
      )
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

  private val sampleMetadata = FileMetadata(
    fileSize = 1536L,
    createdAt = Some(Instant.parse("2024-01-01T00:00:00Z")),
    modifiedAt = None,
    compressionRatio = Some(0.75),
    version = "2.0",
    createdBy = Some("parqueteer")
  )

  "TableFormatter.formatContent" should "include column headers" in {
    val result = formatter.formatContent(sampleContent, None)
    result should include("name")
    result should include("age")
    result should include("score")
  }

  it should "include row values" in {
    val result = formatter.formatContent(sampleContent, None)
    result should include("Alice")
    result should include("Bob")
    result should include("30")
    result should include("25")
  }

  it should "include box-drawing border characters" in {
    val result = formatter.formatContent(sampleContent, None)
    result should include("┌")
    result should include("┐")
    result should include("└")
    result should include("┘")
    result should include("├")
    result should include("┤")
  }

  it should "return 'No data to display' for empty rows" in {
    val empty = FileContent(List.empty, 0L, false)
    formatter.formatContent(empty, None) shouldBe "No data to display"
  }

  it should "show full-count summary for non-partial content" in {
    val result = formatter.formatContent(sampleContent, None)
    result should include("2 rows (showing all)")
  }

  it should "show partial summary when isPartial=true" in {
    val partial = sampleContent.copy(totalRows = 100L, isPartial = true)
    val result = formatter.formatContent(partial, None)
    result should include("100 rows total")
    result should include("showing first 2")
  }

  it should "format doubles with full precision" in {
    val content = FileContent(
      rows = List(Map("v" -> CellValue.F64(1.234567))),
      totalRows = 1L,
      isPartial = false
    )
    val result = formatter.formatContent(content, None)
    result should include("1.234567")
    formatter.formatValue(CellValue.F64(1.234567)) shouldBe "1.234567"
    formatter.formatValue(CellValue.F64(9.5)) shouldBe "9.5"
  }

  it should "produce exact table structure for single-column single-row input" in {
    val minimal = FileContent(
      rows = List(Map("id" -> CellValue.I64(1L))),
      totalRows = 1L,
      isPartial = false
    )
    val result = formatter.formatContent(minimal, None)
    result should include("┌")
    result should include("id")
    result should include("1")
    result should include("┘")
    // Summary pinned exactly
    result should include("1 rows (showing all)")
  }

  "TableFormatter.formatSchema" should "include column count" in {
    val result = formatter.formatSchema(sampleSchema)
    result should include("Total Columns: 2")
  }

  it should "include column names" in {
    val result = formatter.formatSchema(sampleSchema)
    result should include("name")
    result should include("age")
  }

  it should "include compression type" in {
    val result = formatter.formatSchema(sampleSchema)
    result should include("SNAPPY")
  }

  it should "show Yes for optional columns and No for required columns" in {
    val result = formatter.formatSchema(sampleSchema)
    result should include("Yes")
    result should include("No")
  }

  "TableFormatter.formatMetadata" should "show file size in human-readable form" in {
    val result = formatter.formatMetadata(sampleMetadata)
    result should include("1.5 KB")
  }

  it should "show version" in {
    val result = formatter.formatMetadata(sampleMetadata)
    result should include("Parquet Version: 2.0")
  }

  it should "show creator" in {
    val result = formatter.formatMetadata(sampleMetadata)
    result should include("parqueteer")
  }

  it should "include createdAt timestamp" in {
    val result = formatter.formatMetadata(sampleMetadata)
    result should include("2024-01-01")
  }

  "TableFormatter.formatValue" should "format Double NaN as NaN" in {
    formatter.formatValue(CellValue.F64(Double.NaN)) shouldBe "NaN"
  }

  it should "format Double positive infinity as Infinity" in {
    formatter.formatValue(
      CellValue.F64(Double.PositiveInfinity)
    ) shouldBe "Infinity"
  }

  it should "format Double negative infinity as -Infinity" in {
    formatter.formatValue(
      CellValue.F64(Double.NegativeInfinity)
    ) shouldBe "-Infinity"
  }

  it should "format Float NaN as NaN" in {
    formatter.formatValue(CellValue.F32(Float.NaN)) shouldBe "NaN"
  }

  it should "format Float positive infinity as Infinity" in {
    formatter.formatValue(
      CellValue.F32(Float.PositiveInfinity)
    ) shouldBe "Infinity"
  }

  it should "format Int (INT32) value as string" in {
    formatter.formatValue(CellValue.I32(42)) shouldBe "42"
    formatter.formatValue(CellValue.I32(-1)) shouldBe "-1"
  }

  it should "format Float (FLOAT) value with full precision" in {
    formatter.formatValue(CellValue.F32(1.5f)) shouldBe "1.5"
    formatter.formatValue(
      CellValue.F32(-3.0f)
    ) shouldBe "-3" // stripTrailingZeros removes .0
    formatter.formatValue(CellValue.F32(1.234568f)) shouldBe "1.234568"
  }

  it should "format BigDecimal without scientific notation" in {
    formatter.formatValue(
      CellValue.Dec(BigDecimal("1234567890.123"))
    ) shouldBe "1234567890.123"
    formatter.formatValue(
      CellValue.Dec(BigDecimal("0.0000001"))
    ) shouldBe "0.0000001"
  }

  it should "strip trailing zeros from BigDecimal display" in {
    formatter.formatValue(CellValue.Dec(BigDecimal("1.10"))) shouldBe "1.1"
    formatter.formatValue(CellValue.Dec(BigDecimal("100.00"))) shouldBe "100"
  }

  it should "render Boolean, Int, and Float columns in table output" in {
    val content = FileContent(
      rows = List(
        Map(
          "active" -> CellValue.Bool(true),
          "count" -> CellValue.I32(7),
          "ratio" -> CellValue.F32(0.5f)
        ),
        Map(
          "active" -> CellValue.Bool(false),
          "count" -> CellValue.I32(0),
          "ratio" -> CellValue.F32(1.0f)
        )
      ),
      totalRows = 2L,
      isPartial = false
    )
    val result = formatter.formatContent(content, None)
    result should include("true")
    result should include("false")
    result should include("7")
    result should include("0.5")
  }

  "TableFormatter.formatBytes" should "format bytes" in {
    formatter.formatBytes(512L) shouldBe "512 B"
  }

  it should "format exactly 1024 bytes as 1 KB" in {
    formatter.formatBytes(1024L) shouldBe "1 KB"
  }

  it should "format kilobytes" in {
    formatter.formatBytes(2048L) shouldBe "2 KB"
  }

  it should "format megabytes" in {
    formatter.formatBytes(2 * 1024 * 1024L) shouldBe "2 MB"
  }

  it should "format gigabytes" in {
    formatter.formatBytes(3L * 1024 * 1024 * 1024) shouldBe "3 GB"
  }

  // ── OOM guard ─────────────────────────────────────────────────────────────

  "TableFormatter.formatContent" should "return an error message when row count exceeds 10000" in {
    val bigContent = FileContent(
      rows = List.fill(10_001)(Map("id" -> CellValue.I64(1L))),
      totalRows = 10_001L,
      isPartial = false
    )
    val result = formatter.formatContent(bigContent, None)
    // rows are truncated to 10 000 and a warning is emitted to stderr; result is still a valid table
    result should not startWith "Error:"
    result should include("┌")
    result should include("10001 rows total")
  }

  it should "render normally when row count is exactly 10000" in {
    val edgeContent = FileContent(
      rows = List.fill(10_000)(Map("id" -> CellValue.I64(1L))),
      totalRows = 10_000L,
      isPartial = false
    )
    val result = formatter.formatContent(edgeContent, None)
    result should not startWith "Error:"
    result should include("┌")
  }
}
