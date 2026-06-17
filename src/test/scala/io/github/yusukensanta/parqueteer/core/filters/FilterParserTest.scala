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

  // ── String escape sequences ───────────────────────────────────────────────
  it should "correctly decode \\t as tab in quoted string" in {
    FilterParser.parse("""name = "a\tb"""") shouldBe a[Right[?, ?]]
  }

  it should "correctly decode \\n as newline in quoted string" in {
    FilterParser.parse("""name = "line1\nline2"""") shouldBe a[Right[?, ?]]
  }

  it should "correctly decode \\\\ as literal backslash" in {
    FilterParser.parse("""path = "C:\\\\Users"""") shouldBe a[Right[?, ?]]
  }

  it should "correctly decode \\\" as literal double-quote" in {
    FilterParser.parse("""name = "say \\\"hi\\\""""") shouldBe a[Right[?, ?]]
  }

  it should "return Left for unknown escape sequence" in {
    val result = FilterParser.parse("""name = "a\qb"""")
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.message should include("Unknown escape")
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

  // ── Negative literal tokenization (H6) ───────────────────────────────────────
  // '-' is only treated as unary minus when the previous token allows it.

  it should "parse negative literal after operator" in {
    val result = FilterParser.parse("price = -5")
    result shouldBe a[Right[?, ?]]
  }

  it should "parse negative literal after BETWEEN keyword" in {
    val result = FilterParser.parse("score BETWEEN -10 AND 10")
    result shouldBe a[Right[?, ?]]
  }

  it should "parse negative literal as first token in parenthesized group" in {
    val result = FilterParser.parse("(score = -1) AND active = true")
    result shouldBe a[Right[?, ?]]
  }

  it should "return Left when '-' appears after a literal (subtract operator not supported)" in {
    val result = FilterParser.parse("col >= 10-5")
    result shouldBe a[Left[?, ?]]
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

  it should "fail at parse time for IS NULL on column not present in schema" in {
    val s = schema("age" -> PrimitiveTypeName.INT32)
    val result = FilterParser.parseWithSchema("unknown_col IS NULL", s)
    result shouldBe a[Left[?, ?]]
    result.left.exists(_.message.contains("unknown_col")) shouldBe true
  }

  it should "fail at parse time for IS NULL on a group/nested column in schema" in {
    // GroupType IS NULL cannot be expressed as a typed Parquet filter predicate.
    // Fail fast at parse time rather than defaulting to BINARY and throwing a
    // cryptic type-mismatch error at read time.
    val groupField = Types
      .buildGroup(Repetition.OPTIONAL)
      .named("address")
      .asInstanceOf[org.apache.parquet.schema.Type]
    val s = new MessageType("test", List(groupField).asJava)
    val result = FilterParser.parseWithSchema("address IS NULL", s)
    result shouldBe a[Left[?, ?]]
    result.left.get.message should include("nested column")
  }

  it should "fail at parse time for IS NULL on an invalid dotted path" in {
    // Schema has root-level 'a' (INT32, a primitive) and 'c' (INT64).
    // 'a.b.c' is invalid because 'a' is not a group. The old code defaulted
    // to BINARY and potentially threw at read time; now we fail at parse time.
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
    val result = FilterParser.parseWithSchema("a.b.c IS NULL", s)
    result shouldBe a[Left[?, ?]]
    result.left.exists(_.message.contains("a.b.c")) shouldBe true
  }

  // ── BETWEEN reverse-bounds validation ─────────────────────────────────────
  "FilterParser BETWEEN" should "return Left when low > high (integer)" in {
    FilterParser.parse("age BETWEEN 100 AND 10") match {
      case Left(err) => err.message should include("BETWEEN range is empty")
      case Right(_)  => fail("Expected Left for reversed BETWEEN bounds")
    }
  }

  it should "return Left when low > high (double)" in {
    FilterParser.parse("score BETWEEN 9.9 AND 1.0") match {
      case Left(err) => err.message should include("BETWEEN range is empty")
      case Right(_)  => fail("Expected Left for reversed BETWEEN bounds")
    }
  }

  it should "return Left when low > high (mixed long/double)" in {
    FilterParser.parse("price BETWEEN 100 AND 9.5") match {
      case Left(err) => err.message should include("BETWEEN range is empty")
      case Right(_)  => fail("Expected Left for reversed BETWEEN bounds")
    }
  }

  // ── IN boolean values ──────────────────────────────────────────────────────
  "FilterParser IN" should "parse a single boolean in IN list" in {
    val result = FilterParser.parse("active IN (true)")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "parse both boolean values in IN list" in {
    val result = FilterParser.parse("active IN (true, false)")
    result shouldBe a[Right[?, ?]]
    result.exists(_ ne Filter.noopFilter) shouldBe true
  }

  it should "return Left for mixed boolean and numeric IN list" in {
    val result = FilterParser.parse("col IN (true, 1)")
    result shouldBe a[Left[?, ?]]
  }

  // ── DECIMAL warning for = and != (advisory, not an error) ─────────────────

  it should "parse Long equality against a DECIMAL-annotated column (warning only, no error)" in {
    val fields = List(
      org.apache.parquet.schema.Types
        .required(PrimitiveTypeName.INT64)
        .as(org.apache.parquet.schema.LogicalTypeAnnotation.decimalType(2, 10))
        .named("price")
    )
    val s = new org.apache.parquet.schema.MessageType("test", fields.asJava)
    FilterParser.parseWithSchema("price = 100", s) shouldBe a[Right[?, ?]]
  }

  it should "parse Long inequality against a DECIMAL-annotated column (warning only, no error)" in {
    val fields = List(
      org.apache.parquet.schema.Types
        .required(PrimitiveTypeName.INT64)
        .as(org.apache.parquet.schema.LogicalTypeAnnotation.decimalType(2, 10))
        .named("price")
    )
    val s = new org.apache.parquet.schema.MessageType("test", fields.asJava)
    FilterParser.parseWithSchema("price != 500", s) shouldBe a[Right[?, ?]]
  }

  // ── BETWEEN Long/Double precision guard ────────────────────────────────────

  it should "return Left when BETWEEN Long lower bound loses precision as Double" in {
    // 9007199254740993 < 1e17 so range is not reversed; but 9007199254740993.toDouble loses precision
    FilterParser.parse("id BETWEEN 9007199254740993 AND 1.0e17") match {
      case Left(err) =>
        err.message should include("cannot be represented exactly as a Double")
      case Right(_) =>
        fail("Expected Left for imprecise BETWEEN Long lower bound")
    }
  }

  it should "return Left when BETWEEN Long upper bound loses precision as Double" in {
    FilterParser.parse("id BETWEEN 1.5 AND 9007199254740993") match {
      case Left(err) =>
        err.message should include("cannot be represented exactly as a Double")
      case Right(_) =>
        fail("Expected Left for imprecise BETWEEN Long upper bound")
    }
  }

  it should "parse BETWEEN mixed Long/Double when Long is exactly representable as Double" in {
    FilterParser.parse("price BETWEEN 10 AND 99.9") shouldBe a[Right[?, ?]]
  }

  // ── IN list Long/Double precision guard ────────────────────────────────────

  it should "return Left for mixed IN list where Long cannot be represented exactly as Double" in {
    FilterParser.parse("id IN (9007199254740993, 1.5)") match {
      case Left(err) =>
        err.message should include("cannot be represented exactly as Double")
      case Right(_) => fail("Expected Left for imprecise IN list Long value")
    }
  }

  it should "accept mixed Long/Double IN list when all Long values are exactly representable" in {
    FilterParser.parse("score IN (100, 99.5)") shouldBe a[Right[?, ?]]
  }

  // ── BETWEEN Long.MaxValue precision guard (JVM d2l clamping false-negative) ─

  it should "return Left for BETWEEN with Long.MaxValue lower bound (not exactly representable as Double)" in {
    // Long.MaxValue.toDouble rounds UP to 2^63; JVM d2l clamps that back to Long.MaxValue,
    // so the old round-trip check (l.toDouble.toLong != l) had a false negative.
    // BigDecimal comparison correctly detects the imprecision.
    FilterParser.parse(s"id BETWEEN ${Long.MaxValue} AND 1.0e20") match {
      case Left(err) =>
        err.message should include("cannot be represented exactly as a Double")
      case Right(_) =>
        fail(
          "Expected Left: Long.MaxValue is not exactly representable as Double"
        )
    }
  }

  it should "return Left for IN list containing Long.MaxValue mixed with Double" in {
    FilterParser.parse(s"id IN (${Long.MaxValue}, 1.5)") match {
      case Left(err) =>
        err.message should include("cannot be represented exactly as Double")
      case Right(_) =>
        fail(
          "Expected Left: Long.MaxValue cannot be represented exactly as Double"
        )
    }
  }

  // ── Pure-Long IN list: DECIMAL column advisory ─────────────────────────────

  it should "parse pure-Long IN list against a DECIMAL column (warning only, no error)" in {
    val fields = List(
      org.apache.parquet.schema.Types
        .required(PrimitiveTypeName.INT64)
        .as(org.apache.parquet.schema.LogicalTypeAnnotation.decimalType(2, 10))
        .named("price")
    )
    val s = new org.apache.parquet.schema.MessageType("test", fields.asJava)
    FilterParser
      .parseWithSchema("price IN (100, 200, 300)", s) shouldBe a[Right[?, ?]]
  }

  // ── IS NOT NULL error message ──────────────────────────────────────────────

  "FilterParser IS NOT NULL" should "report 'after IS NOT' when NOT was consumed but NULL is missing" in {
    // "col IS NOT 42" — IS NOT consumed, next token is 42 not NULL
    val result = FilterParser.parse("col IS NOT 42")
    result shouldBe a[Left[?, ?]]
    result.left.exists(_.message.contains("after IS NOT")) shouldBe true
    result.left.exists(_.message.contains("after IS,")) shouldBe false
  }

  it should "report 'after IS' when IS alone consumed but NULL is missing" in {
    // "col IS 42" — IS consumed, next token is 42 not NULL or NOT
    val result = FilterParser.parse("col IS 42")
    result shouldBe a[Left[?, ?]]
    result.left.exists(_.message.contains("after IS,")) shouldBe true
  }

  // ── FilterParseError exit code and FilterParseException routing ───────────

  "FilterParseError" should "have exit code 7" in {
    val s = schema("age" -> PrimitiveTypeName.INT32)
    val result = FilterParser.parseWithSchema("unknown IS NULL", s)
    result shouldBe a[Left[?, ?]]
    result.left.map(_.exitCode) shouldBe Left(7)
  }

  "ParqueteerError.FilterParseException" should "route to FilterParseError via toParqueteerError" in {
    import io.github.yusukensanta.parqueteer.core.models.ParqueteerError
    import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.toParqueteerError
    import scala.util.Try
    val ex = new ParqueteerError.FilterParseException(
      "col IS NULL",
      "column 'col' not found"
    )
    val result = Try(throw ex).toParqueteerError
    result shouldBe Left(
      ParqueteerError.FilterParseError("col IS NULL", "column 'col' not found")
    )
    result.left.map(_.exitCode) shouldBe Left(7)
  }
}
