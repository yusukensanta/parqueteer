package io.github.yusukensanta.parqueteer.core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SizeParserTest extends AnyFlatSpec with Matchers {

  "SizeParser.parse" should "parse bytes" in {
    SizeParser.parse("512B") shouldBe 512L
  }

  it should "parse kilobytes" in {
    SizeParser.parse("128KB") shouldBe 131072L
  }

  it should "parse megabytes" in {
    SizeParser.parse("64MB") shouldBe 67108864L
  }

  it should "parse gigabytes" in {
    SizeParser.parse("1GB") shouldBe 1073741824L
  }

  it should "accept spaces between number and unit" in {
    SizeParser.parse("64 MB") shouldBe 67108864L
  }

  it should "be case-insensitive" in {
    SizeParser.parse("64mb") shouldBe 67108864L
    SizeParser.parse("1gb") shouldBe 1073741824L
  }

  it should "throw IllegalArgumentException for invalid format" in {
    an[IllegalArgumentException] should be thrownBy SizeParser.parse("invalid")
  }

  it should "throw for number with no unit" in {
    an[IllegalArgumentException] should be thrownBy SizeParser.parse("128")
  }

  it should "throw for number that overflows Long" in {
    an[IllegalArgumentException] should be thrownBy SizeParser.parse(
      "99999999999999999999GB"
    )
  }
}
