package io.github.yusukensanta.parqueteer.core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen

class SizeParserTest
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks {

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

  it should "accept bare single-letter units (M, G, K)" in {
    SizeParser.parse("128M") shouldBe 134217728L
    SizeParser.parse("128m") shouldBe 134217728L
    SizeParser.parse("1G") shouldBe 1073741824L
    SizeParser.parse("1g") shouldBe 1073741824L
    SizeParser.parse("64K") shouldBe 65536L
    SizeParser.parse("64k") shouldBe 65536L
  }

  it should "throw IllegalArgumentException for invalid format" in {
    an[IllegalArgumentException] should be thrownBy SizeParser.parse("invalid")
  }

  it should "parse terabytes" in {
    SizeParser.parse("1TB") shouldBe 1099511627776L
  }

  it should "accept bare single-letter T unit" in {
    SizeParser.parse("1T") shouldBe 1099511627776L
    SizeParser.parse("1t") shouldBe 1099511627776L
  }

  it should "accept bare integers (no unit) as bytes" in {
    SizeParser.parse("128") shouldBe 128L
    SizeParser.parse("134217728") shouldBe 134217728L
  }

  it should "throw for number that overflows Long" in {
    an[IllegalArgumentException] should be thrownBy SizeParser.parse(
      "99999999999999999999GB"
    )
  }

  it should "throw for overflowing product even when the number alone fits in Long" in {
    // 9999999999GB = 9999999999 * 1073741824 which overflows Long
    an[IllegalArgumentException] should be thrownBy SizeParser.parse(
      "9999999999GB"
    )
  }

  it should "accept fractional megabytes" in {
    SizeParser.parse("128.5MB") shouldBe (128.5 * 1024 * 1024).toLong
  }

  it should "accept fractional gigabytes" in {
    SizeParser.parse("1.5GB") shouldBe (1.5 * 1024 * 1024 * 1024).toLong
  }

  // ── Property-based: ByteFormatter.format → SizeParser.parse never throws ─
  // For any positive Long, the formatted string must be re-parseable.

  "SizeParser.parse (property)" should "never throw for any ByteFormatter output" in {
    forAll(Gen.posNum[Long]) { n =>
      val formatted = ByteFormatter.format(n)
      noException should be thrownBy SizeParser.parse(formatted)
    }
  }

  it should "return a non-negative result for any ByteFormatter output" in {
    forAll(Gen.posNum[Long]) { n =>
      SizeParser.parse(ByteFormatter.format(n)) should be >= 0L
    }
  }

  it should "reject fractional bytes (no unit or B unit)" in {
    an[IllegalArgumentException] should be thrownBy SizeParser.parse("1.5")
    an[IllegalArgumentException] should be thrownBy SizeParser.parse("1.5B")
  }

  it should "accept fractional values with non-byte units that produce whole bytes" in {
    SizeParser.parse("0.5KB") shouldBe 512L
    SizeParser.parse("1.5KB") shouldBe 1536L
  }
}
