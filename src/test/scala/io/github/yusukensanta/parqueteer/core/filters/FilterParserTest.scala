package io.github.yusukensanta.parqueteer.core.filters

import com.github.mjakubowski84.parquet4s.Filter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FilterParserTest extends AnyFlatSpec with Matchers {

  "FilterParser" should "parse simple integer equality" in {
    val result = FilterParser.parse("age = 25")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse greater than with integer" in {
    val result = FilterParser.parse("salary > 50000")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse greater than or equal" in {
    val result = FilterParser.parse("score >= 90")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse less than" in {
    val result = FilterParser.parse("price < 100")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse less than or equal" in {
    val result = FilterParser.parse("price <= 99")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse not equal" in {
    val result = FilterParser.parse("status != 0")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse string equality with quoted value" in {
    val result = FilterParser.parse("""name = "Alice"""")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse boolean equality" in {
    val result = FilterParser.parse("active = true")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse AND expression" in {
    val result = FilterParser.parse("age > 25 AND active = true")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse OR expression" in {
    val result = FilterParser.parse("age < 18 OR age > 65")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse NOT expression" in {
    val result = FilterParser.parse("NOT active = true")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse parenthesized expression" in {
    val result = FilterParser.parse("(age > 25 OR age < 18) AND active = true")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse floating point comparison" in {
    val result = FilterParser.parse("score >= 9.5")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse large Long value without truncation (> Int.MaxValue)" in {
    val result = FilterParser.parse("id > 3000000000")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse integer literal as Long not Double (prevents INT64 filter mismatch)" in {
    // 2^53+1 cannot be exactly represented as Double; a Double filter would silently
    // corrupt the value, causing the INT64 predicate to never match.
    val result = FilterParser.parse("id = 9007199254740993")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "return Left for empty string" in {
    FilterParser.parse("") shouldBe a[Left[?, ?]]
  }

  it should "return Left for incomplete expression" in {
    FilterParser.parse("age >") shouldBe a[Left[?, ?]]
  }

  it should "return Left for missing operator" in {
    FilterParser.parse("age 25") shouldBe a[Left[?, ?]]
  }

  it should "include error message in Left" in {
    val result = FilterParser.parse("age >")
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Filter parse error")
  }

  it should "return Left when numeric operator is applied to string value" in {
    val result = FilterParser.parse("""name > "Alice"""")
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("requires a numeric value")
  }

  // ── BETWEEN ───────────────────────────────────────────────────────────────
  "FilterParser BETWEEN" should "parse integer range" in {
    val result = FilterParser.parse("age BETWEEN 25 AND 35")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse floating-point range" in {
    val result = FilterParser.parse("score BETWEEN 7.5 AND 9.9")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse mixed Long and Double bounds" in {
    val result = FilterParser.parse("price BETWEEN 10 AND 99.9")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "combine with AND" in {
    val result = FilterParser.parse("age BETWEEN 18 AND 65 AND active = true")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  // ── IN ───────────────────────────────────────────────────────────────────
  "FilterParser IN" should "parse list of strings" in {
    val result =
      FilterParser.parse("""status IN ("active", "pending", "review")""")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse list of integers" in {
    val result = FilterParser.parse("priority IN (1, 2, 3)")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse single-element list" in {
    val result = FilterParser.parse("""type IN ("admin")""")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "return Left for mixed string and numeric values" in {
    val result = FilterParser.parse("""id IN (1, "two", 3)""")
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("same type")
  }

  // ── IS NULL / IS NOT NULL ─────────────────────────────────────────────────
  "FilterParser IS NULL" should "parse IS NULL" in {
    val result = FilterParser.parse("email IS NULL")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse IS NOT NULL" in {
    val result = FilterParser.parse("email IS NOT NULL")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "combine IS NULL with AND" in {
    val result = FilterParser.parse("active = true AND email IS NOT NULL")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  // ── Nested column access ──────────────────────────────────────────────────
  "FilterParser nested columns" should "parse dotted path" in {
    val result = FilterParser.parse("""user.address.city = "NYC"""")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse dotted path in BETWEEN" in {
    val result = FilterParser.parse("metrics.score BETWEEN 80 AND 100")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }
}
