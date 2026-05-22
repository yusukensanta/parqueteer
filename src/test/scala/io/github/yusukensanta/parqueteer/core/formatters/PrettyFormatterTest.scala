package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PrettyFormatterTest extends AnyFlatSpec with Matchers {

  private val content = FileContent(
    rows = List(Map("name" -> "Alice", "score" -> 9.5)),
    totalRows = 1L,
    isPartial = false
  )

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
    val c = FileContent(List(Map("v" -> 1.234567)), 1L, isPartial = false)
    val result = new PrettyFormatter(useColors = false).formatContent(c, None)
    result should include("1.234567")
  }

  it should "not over-pad CJK values (uses displayWidth, not .length)" in {
    val c = FileContent(List(Map("lang" -> "日本語")), 1L, isPartial = false)
    val result = new PrettyFormatter(useColors = true).formatContent(c, None)
    val stripped = result.replaceAll("\\[[^m]*m", "")
    stripped should include("│日本語│")
  }
}
