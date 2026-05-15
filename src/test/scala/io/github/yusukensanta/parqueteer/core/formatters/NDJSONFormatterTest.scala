package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{
  FileContent,
  ParquetSchema,
  ColumnInfo
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import io.circe.parser._

class NDJSONFormatterTest extends AnyFlatSpec with Matchers {

  private val formatter = new NDJSONFormatter()

  private val rows = List(
    Map("id" -> 1L, "name" -> "Alice"),
    Map("id" -> 2L, "name" -> "Bob")
  )
  private val content =
    FileContent(rows = rows, totalRows = 2L, isPartial = false)

  "NDJSONFormatter.formatContent" should "produce one JSON object per line" in {
    val result = formatter.formatContent(content, None)
    val lines = result.split("\n").filter(_.nonEmpty)
    lines should have length 2
    lines.foreach { line =>
      parse(line) match {
        case Right(_) => succeed
        case Left(e)  => fail(s"Line is not valid JSON: $line — $e")
      }
    }
  }

  it should "include field values in each line" in {
    val result = formatter.formatContent(content, None)
    result should include("Alice")
    result should include("Bob")
  }

  it should "produce compact (no-spaces) JSON per line" in {
    val result = formatter.formatContent(content, None)
    result.split("\n").foreach { line =>
      line should not include "\n"
      line.trim should startWith("{")
      line.trim should endWith("}")
    }
  }

  it should "return empty string for empty content" in {
    val empty = FileContent(rows = List.empty, totalRows = 0L)
    formatter.formatContent(empty, None) shouldBe ""
  }

  "NDJSONFormatter.formatSchema" should "produce one JSON object per column" in {
    val schema = ParquetSchema(
      columns = List(
        ColumnInfo("id", "INT64", isOptional = false, 1, 0, "SNAPPY"),
        ColumnInfo("name", "BINARY", isOptional = true, 1, 0, "SNAPPY")
      ),
      rowGroupCount = 1L,
      totalRowCount = 100L
    )
    val result = formatter.formatSchema(schema)
    val lines = result.split("\n").filter(_.nonEmpty)
    lines should have length 2
    result should include("INT64")
    result should include("BINARY")
  }
}
