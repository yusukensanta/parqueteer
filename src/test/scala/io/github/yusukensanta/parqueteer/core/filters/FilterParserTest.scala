package io.github.yusukensanta.parqueteer.core.filters

import com.github.mjakubowski84.parquet4s.Filter
import org.apache.parquet.schema.{MessageType, PrimitiveType, Types}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.schema.Type.Repetition
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters._

class FilterParserTest extends AnyFlatSpec with Matchers {

  private def schema(fields: (String, PrimitiveTypeName)*): MessageType =
    new MessageType(
      "test",
      fields.map { case (name, t) =>
        Types
          .primitive(t, Repetition.OPTIONAL)
          .named(name)
          .asInstanceOf[org.apache.parquet.schema.Type]
      }*
    )

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

  it should "return Left for unterminated string literal" in {
    val result = FilterParser.parse("""name = "Alice""")
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.message should include("Unterminated")
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

  it should "return Left when '(' is missing after IN" in {
    val result = FilterParser.parse("id IN 1, 2")
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("expected '('")
  }

  it should "return Left when ')' is missing after IN list" in {
    val result = FilterParser.parse("id IN (1, 2")
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("expected ')'")
  }

  it should "return Left for invalid value in IN list" in {
    val result = FilterParser.parse("id IN (1, , 3)")
    result.isLeft shouldBe true
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

  // ── Error message quality ──────────────────────────────────────────────────
  "FilterParser error messages" should "include original expression in userMessage" in {
    val expr = "age >"
    val result = FilterParser.parse(expr)
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include(expr)
  }

  it should "return Left for completely nonsense input" in {
    FilterParser.parse("???!!!") shouldBe a[Left[?, ?]]
  }

  it should "return Left with position hint for unknown character '&'" in {
    val result = FilterParser.parse("""name = "Alice" & age > 10""")
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.message should include("unexpected character")
    result.left.toOption.get.message should include("&")
  }

  it should "return Left with position hint for pipe character '|'" in {
    val result = FilterParser.parse("age > 5 | age < 2")
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.message should include("unexpected character")
    result.left.toOption.get.message should include("|")
  }

  it should "return Left with hint for Long overflow" in {
    val result = FilterParser.parse("id = 99999999999999999999")
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.message should include("out of Long range")
  }

  it should "return Left for dangling AND" in {
    FilterParser.parse("age > 5 AND") shouldBe a[Left[?, ?]]
  }

  it should "return Left for value-only input" in {
    FilterParser.parse("42") shouldBe a[Left[?, ?]]
  }

  // ── parseWithSchema ────────────────────────────────────────────────────────
  "FilterParser.parseWithSchema" should "succeed for INT32 column with integer value" in {
    val s = schema("age" -> PrimitiveTypeName.INT32)
    val result = FilterParser.parseWithSchema("age > 18", s)
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "succeed for BINARY column with string value" in {
    val s = schema("name" -> PrimitiveTypeName.BINARY)
    val result = FilterParser.parseWithSchema("""name = "Alice"""", s)
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "succeed for INT64 column with BETWEEN" in {
    val s = schema("ts" -> PrimitiveTypeName.INT64)
    val result = FilterParser.parseWithSchema("ts BETWEEN 1000 AND 9999", s)
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "succeed for BOOLEAN column with equality" in {
    val s = schema("active" -> PrimitiveTypeName.BOOLEAN)
    val result = FilterParser.parseWithSchema("active = true", s)
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "succeed for multi-column AND expression" in {
    val s = schema(
      "age" -> PrimitiveTypeName.INT32,
      "active" -> PrimitiveTypeName.BOOLEAN
    )
    val result =
      FilterParser.parseWithSchema("age > 18 AND active = true", s)
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "succeed for IS NULL on column not present in schema (defaults to BINARY)" in {
    val s = schema("age" -> PrimitiveTypeName.INT32)
    val result = FilterParser.parseWithSchema("unknown_col IS NULL", s)
    result shouldBe a[Right[?, ?]]
  }

  it should "not resolve wrong column type when a dotted path segment is missing" in {
    // Schema has root-level 'c' (INT64) but no 'a.b' group — resolveColumnType
    // previously reset to the root schema when 'b' was not found under 'a',
    // then matched 'c' at the root, returning INT64 instead of defaulting to BINARY.
    val fields = List(
      org.apache.parquet.schema.Types
        .required(PrimitiveTypeName.INT32)
        .named("a"),
      org.apache.parquet.schema.Types
        .required(PrimitiveTypeName.INT64)
        .named("c")
    )
    val s = new org.apache.parquet.schema.MessageType(
      "test",
      fields.asJava
    )
    // 'a.b.c' — 'a' is a primitive (not a group), so the path is invalid.
    // Should succeed (defaults to BINARY) rather than crashing or returning INT64.
    val result = FilterParser.parseWithSchema("a.b.c IS NULL", s)
    result shouldBe a[Right[?, ?]]
  }
}
