package io.github.yusukensanta.parqueteer.core.filters

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FilterParserTest extends AnyFlatSpec with Matchers {

  "FilterParser" should "parse simple integer equality" in {
    FilterParser.parse("age = 25") shouldBe a[Right[?, ?]]
  }

  it should "parse greater than with integer" in {
    FilterParser.parse("salary > 50000") shouldBe a[Right[?, ?]]
  }

  it should "parse greater than or equal" in {
    FilterParser.parse("score >= 90") shouldBe a[Right[?, ?]]
  }

  it should "parse less than" in {
    FilterParser.parse("price < 100") shouldBe a[Right[?, ?]]
  }

  it should "parse less than or equal" in {
    FilterParser.parse("price <= 99") shouldBe a[Right[?, ?]]
  }

  it should "parse not equal" in {
    FilterParser.parse("status != 0") shouldBe a[Right[?, ?]]
  }

  it should "parse string equality with quoted value" in {
    FilterParser.parse("""name = "Alice"""") shouldBe a[Right[?, ?]]
  }

  it should "parse boolean equality" in {
    FilterParser.parse("active = true") shouldBe a[Right[?, ?]]
  }

  it should "parse AND expression" in {
    FilterParser.parse("age > 25 AND active = true") shouldBe a[Right[?, ?]]
  }

  it should "parse OR expression" in {
    FilterParser.parse("age < 18 OR age > 65") shouldBe a[Right[?, ?]]
  }

  it should "parse NOT expression" in {
    FilterParser.parse("NOT active = true") shouldBe a[Right[?, ?]]
  }

  it should "parse parenthesized expression" in {
    FilterParser
      .parse("(age > 25 OR age < 18) AND active = true") shouldBe a[Right[?, ?]]
  }

  it should "parse floating point comparison" in {
    FilterParser.parse("score >= 9.5") shouldBe a[Right[?, ?]]
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
    result.left.getOrElse("") should include("Filter parse error")
  }
}
