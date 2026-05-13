package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class TableFormatterTest extends AnyFlatSpec with Matchers {

  private val formatter = new TableFormatter()

  private val sampleContent = FileContent(
    rows = List(
      Map("name" -> "Alice", "age" -> 30L, "score" -> 9.5),
      Map("name" -> "Bob", "age" -> 25L, "score" -> 7.2)
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

  it should "format doubles to 2 decimal places" in {
    val result = formatter.formatContent(sampleContent, None)
    result should include("9.50")
    result should include("7.20")
  }

  it should "produce exact table structure for single-column single-row input" in {
    val minimal = FileContent(
      rows = List(Map("id" -> 1L)),
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
    result should include("1.50 KB")
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

  "TableFormatter.formatBytes" should "format bytes" in {
    formatter.formatBytes(512L) shouldBe "512.00 B"
  }

  it should "format exactly 1024 bytes as 1.00 KB" in {
    formatter.formatBytes(1024L) shouldBe "1.00 KB"
  }

  it should "format kilobytes" in {
    formatter.formatBytes(2048L) shouldBe "2.00 KB"
  }

  it should "format megabytes" in {
    formatter.formatBytes(2 * 1024 * 1024L) shouldBe "2.00 MB"
  }

  it should "format gigabytes" in {
    formatter.formatBytes(3L * 1024 * 1024 * 1024) shouldBe "3.00 GB"
  }
}
