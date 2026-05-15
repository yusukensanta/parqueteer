package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  FileContent,
  ParquetSchema,
  ColumnInfo,
  FileMetadata
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class MarkdownFormatterTest extends AnyFlatSpec with Matchers {

  private val formatter = new MarkdownFormatter()

  private val rows = List(
    Map("id" -> 1L, "name" -> "Alice"),
    Map("id" -> 2L, "name" -> "Bob")
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
    val pipeRow = List(Map("col" -> "a|b"))
    val result = formatter.formatContent(FileContent(pipeRow, 1L), None)
    result should include("a\\|b")
  }

  "MarkdownFormatter.formatSchema" should "produce a markdown header and table" in {
    val schema = ParquetSchema(
      columns =
        List(ColumnInfo("id", "INT64", isOptional = false, 1, 0, "SNAPPY")),
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
