package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models._
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class JSONFormatterTest extends AnyFlatSpec with Matchers {

  private val formatter = new JSONFormatter()

  private val sampleContent = FileContent(
    rows = List(
      Map("id" -> 1L, "name" -> "Alice", "active" -> true),
      Map("id" -> 2L, "name" -> "Bob", "active" -> false)
    ),
    totalRows = 2L,
    isPartial = false
  )

  private val sampleSchema = ParquetSchema(
    columns = List(
      ColumnInfo("id", "INT64", isOptional = false, 1, 0, "SNAPPY"),
      ColumnInfo("name", "BINARY", isOptional = true, 1, 0, "SNAPPY")
    ),
    rowGroupCount = 1L,
    totalRowCount = 2L
  )

  private val sampleMetadata = FileMetadata(
    fileSize = 1024L,
    createdAt = Some(Instant.parse("2024-06-01T00:00:00Z")),
    modifiedAt = None,
    compressionRatio = Some(0.5),
    version = "2.0",
    createdBy = None
  )

  "JSONFormatter.formatContent" should "produce valid JSON" in {
    val result = formatter.formatContent(sampleContent, None)
    parse(result).isRight shouldBe true
  }

  it should "include rows array with correct length" in {
    val result = formatter.formatContent(sampleContent, None)
    val json = parse(result).getOrElse(fail("not JSON"))
    val rows = json.hcursor.downField("rows").as[List[io.circe.Json]]
    rows.map(_.length) shouldBe Right(2)
  }

  it should "report correct totalRows" in {
    val result = formatter.formatContent(sampleContent, None)
    val json = parse(result).getOrElse(fail("not JSON"))
    val total = json.hcursor.downField("totalRows").as[Long]
    total shouldBe Right(2L)
  }

  it should "report isPartial as false" in {
    val result = formatter.formatContent(sampleContent, None)
    val json = parse(result).getOrElse(fail("not JSON"))
    val isPartial = json.hcursor.downField("isPartial").as[Boolean]
    isPartial shouldBe Right(false)
  }

  it should "include displayedRows count" in {
    val result = formatter.formatContent(sampleContent, None)
    val json = parse(result).getOrElse(fail("not JSON"))
    val displayedRows = json.hcursor.downField("displayedRows").as[Int]
    displayedRows shouldBe Right(2)
  }

  it should "encode null values as JSON null" in {
    val withNull = FileContent(
      rows = List(Map("key" -> null)),
      totalRows = 1L,
      isPartial = false
    )
    val result = formatter.formatContent(withNull, None)
    result should include("null")
  }

  it should "encode boolean values correctly" in {
    val result = formatter.formatContent(sampleContent, None)
    result should include("true")
    result should include("false")
  }

  "JSONFormatter.formatSchema" should "produce valid JSON" in {
    val result = formatter.formatSchema(sampleSchema)
    parse(result).isRight shouldBe true
  }

  it should "include columns array with correct length" in {
    val result = formatter.formatSchema(sampleSchema)
    val json = parse(result).getOrElse(fail("not JSON"))
    val columns = json.hcursor.downField("columns").as[List[io.circe.Json]]
    columns.map(_.length) shouldBe Right(2)
  }

  it should "include rowGroupCount" in {
    val result = formatter.formatSchema(sampleSchema)
    val json = parse(result).getOrElse(fail("not JSON"))
    json.hcursor.downField("rowGroupCount").as[Long] shouldBe Right(1L)
  }

  "JSONFormatter.formatMetadata" should "produce valid JSON" in {
    val result = formatter.formatMetadata(sampleMetadata)
    parse(result).isRight shouldBe true
  }

  it should "include fileSize" in {
    val result = formatter.formatMetadata(sampleMetadata)
    val json = parse(result).getOrElse(fail("not JSON"))
    json.hcursor.downField("fileSize").as[Long] shouldBe Right(1024L)
  }
}
