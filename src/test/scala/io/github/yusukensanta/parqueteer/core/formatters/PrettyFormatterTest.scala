package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class PrettyFormatterTest extends AnyFlatSpec with Matchers {

  private val content = FileContent(
    rows = List(
      Map("name" -> CellValue.Str("Alice"), "score" -> CellValue.F64(9.5))
    ),
    totalRows = 1L,
    isPartial = false
  )

  private def stripAnsi(s: String): String =
    s.replaceAll("\\x1B\\[[^m]*m", "")

  "PrettyFormatter" should "emit ANSI codes when useColors = true" in {
    val result =
      new PrettyFormatter(useColors = true).formatContent(content, None)
    result should include("[")
  }

  it should "emit no ANSI codes when useColors = false" in {
    val result =
      new PrettyFormatter(useColors = false).formatContent(content, None)
    result should not include "["
  }

  it should "fall back to plain table when useColors = false" in {
    val pretty =
      new PrettyFormatter(useColors = false).formatContent(content, None)
    val table = new TableFormatter().formatContent(content, None)
    pretty shouldBe table
  }

  it should "render Double with full precision" in {
    val c = FileContent(
      List(Map("v" -> CellValue.F64(1.234567))),
      1L,
      isPartial = false
    )
    val result = new PrettyFormatter(useColors = false).formatContent(c, None)
    result should include("1.234567")
  }

  it should "not over-pad CJK values (uses displayWidth, not .length)" in {
    val c = FileContent(
      List(Map("lang" -> CellValue.Str("日本語"))),
      1L,
      isPartial = false
    )
    val result   = new PrettyFormatter(useColors = true).formatContent(c, None)
    val stripped = stripAnsi(result)
    stripped should include("│日本語│")
  }

  it should "return 'No data to display' for empty rows with colors" in {
    val empty  = FileContent(rows = Nil, totalRows = 0L, isPartial = false)
    val result = new PrettyFormatter(useColors = true).formatContent(empty, None)
    stripAnsi(result) should include("No data to display")
  }

  it should "delegate to TableFormatter for empty rows without colors" in {
    val empty  = FileContent(rows = Nil, totalRows = 0L, isPartial = false)
    val result = new PrettyFormatter(useColors = false).formatContent(empty, None)
    result shouldBe new TableFormatter().formatContent(empty, None)
  }

  "PrettyFormatter.formatSchema" should "include ANSI codes when useColors = true" in {
    val schema = ParquetSchema(
      columns = List(
        ColumnInfo("id", "INT64", isOptional = false, 1, 0, "SNAPPY"),
        ColumnInfo("name", "BINARY", isOptional = true, 1, 0, "SNAPPY"),
        ColumnInfo("score", "DOUBLE", isOptional = true, 1, 0, "ZSTD"),
        ColumnInfo("active", "BOOLEAN", isOptional = false, 1, 0, "SNAPPY"),
        ColumnInfo("ratio", "FLOAT", isOptional = true, 1, 0, "SNAPPY"),
        ColumnInfo("tag", "UTF8", isOptional = true, 1, 0, "SNAPPY")
      ),
      rowGroupCount = 2L,
      totalRowCount = 100L
    )
    val result = new PrettyFormatter(useColors = true).formatSchema(schema)
    result should include("[")
    result should include("id")
    result should include("name")
    result should include("INT64")
    result should include("BINARY")
    result should include("Yes")
    result should include("No")
  }

  it should "delegate to TableFormatter when useColors = false" in {
    val schema = ParquetSchema(
      columns = List(ColumnInfo("x", "INT32", isOptional = false, 1, 0, "SNAPPY")),
      rowGroupCount = 1L,
      totalRowCount = 5L
    )
    val pretty = new PrettyFormatter(useColors = false).formatSchema(schema)
    val table  = new TableFormatter().formatSchema(schema)
    pretty shouldBe table
  }

  "PrettyFormatter.formatMetadata" should "include ANSI codes when useColors = true" in {
    val meta = FileMetadata(
      fileSize = 1024L,
      createdAt = Some(Instant.parse("2024-01-01T00:00:00Z")),
      modifiedAt = Some(Instant.parse("2024-06-01T00:00:00Z")),
      compressionRatio = Some(3.14),
      version = "2.0",
      createdBy = Some("parquet-mr")
    )
    val result = new PrettyFormatter(useColors = true).formatMetadata(meta)
    result should include("[")
    result should include("1 KB")
    result should include("2.0")
    result should include("parquet-mr")
    result should include("3.14")
  }

  it should "delegate to TableFormatter when useColors = false" in {
    val meta = FileMetadata(
      fileSize = 512L,
      createdAt = None,
      modifiedAt = None,
      compressionRatio = None,
      version = "1.0",
      createdBy = None
    )
    val pretty = new PrettyFormatter(useColors = false).formatMetadata(meta)
    val table  = new TableFormatter().formatMetadata(meta)
    pretty shouldBe table
  }

  it should "omit optional fields when they are None" in {
    val meta = FileMetadata(
      fileSize = 256L,
      createdAt = None,
      modifiedAt = None,
      compressionRatio = None,
      version = "2.0",
      createdBy = None
    )
    val result = new PrettyFormatter(useColors = true).formatMetadata(meta)
    result should not include "Created:"
    result should not include "Modified:"
    result should not include "Created By:"
    result should not include "Compression Ratio:"
  }

  "PrettyFormatter colorizeFormatted" should "color Bool(false) differently from Bool(true)" in {
    val cTrue = FileContent(
      List(Map("ok" -> CellValue.Bool(true))),
      1L,
      isPartial = false
    )
    val cFalse = FileContent(
      List(Map("ok" -> CellValue.Bool(false))),
      1L,
      isPartial = false
    )
    val formatter = new PrettyFormatter(useColors = true)
    val resTrue   = formatter.formatContent(cTrue, None)
    val resFalse  = formatter.formatContent(cFalse, None)
    val greenCode = "[32m"
    val redCode   = "[31m"
    resTrue should include(greenCode)
    resFalse should include(redCode)
  }

  it should "color numeric types distinctly from strings" in {
    val c = FileContent(
      List(
        Map(
          "i"   -> CellValue.I64(1L),
          "f"   -> CellValue.F64(1.0),
          "s"   -> CellValue.Str("hello"),
          "nul" -> CellValue.Null
        )
      ),
      1L,
      isPartial = false
    )
    val result   = new PrettyFormatter(useColors = true).formatContent(c, None)
    val blueCode = "[34m"
    result should include(blueCode)
  }
}
