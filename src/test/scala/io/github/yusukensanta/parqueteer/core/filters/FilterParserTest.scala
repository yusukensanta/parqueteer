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

  it should "return noopFilter (not an error) when numeric operator is applied to string value" in {
    val result = FilterParser.parse("""name > "Alice"""")
    result.isRight shouldBe true
    result.exists(_ eq Filter.noopFilter) shouldBe true
  }
}
