package io.github.yusukensanta.parqueteer.core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CsvParserTest extends AnyFlatSpec with Matchers {

  "CsvParser.parse" should "parse basic CSV into typed rows" in {
    val rows = CsvParser.parse("a,b\n1,2\n3,4\n")
    rows should have size 2
    rows(0)("a") shouldBe 1L
    rows(0)("b") shouldBe 2L
  }

  it should "return empty list for empty input" in {
    CsvParser.parse("") shouldBe empty
  }

  it should "infer date strings" in {
    val rows = CsvParser.parse("name,dob\nAlice,1990-06-15\nBob,2001-12-01\n")
    rows(0)("dob").toString should include("1990")
  }

  it should "preserve leading zeros as strings" in {
    val rows = CsvParser.parse("code\n007\n042\n")
    rows(0)("code") shouldBe "007"
    rows(1)("code") shouldBe "042"
  }

  it should "reject rows with wrong field count" in {
    an[IllegalArgumentException] should be thrownBy {
      CsvParser.parse("a,b\n1,2,3\n")
    }
  }

  "CsvParser.parseRfc4180" should "handle quoted fields with commas" in {
    val records = CsvParser.parseRfc4180("\"a,b\",c\n\"x,y\",z\n")
    records should have size 2
    records(0)(0) shouldBe "a,b"
    records(1)(1) shouldBe "z"
  }

  it should "handle escaped quotes inside quoted fields" in {
    val records = CsvParser.parseRfc4180("\"say \"\"hi\"\"\",b\n")
    records(0)(0) shouldBe "say \"hi\""
  }

  it should "handle CRLF line endings" in {
    val records = CsvParser.parseRfc4180("a,b\r\n1,2\r\n")
    records should have size 2
    records(1)(0) shouldBe "1"
  }
}
