package io.github.yusukensanta.parqueteer.core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ByteFormatterTest extends AnyFlatSpec with Matchers {

  "ByteFormatter.format" should "format whole-number bytes without decimal" in {
    ByteFormatter.format(512L) shouldBe "512 B"
  }

  it should "format exactly 1024 bytes as 1 KB (no decimal)" in {
    ByteFormatter.format(1024L) shouldBe "1 KB"
  }

  it should "format whole-number kilobytes without decimal" in {
    ByteFormatter.format(2048L) shouldBe "2 KB"
  }

  it should "format fractional kilobytes with one decimal" in {
    ByteFormatter.format(1536L) shouldBe "1.5 KB"
  }

  it should "format whole-number megabytes without decimal" in {
    ByteFormatter.format(2 * 1024 * 1024L) shouldBe "2 MB"
  }

  it should "format whole-number gigabytes without decimal" in {
    ByteFormatter.format(3L * 1024 * 1024 * 1024) shouldBe "3 GB"
  }

  it should "format whole-number terabytes without decimal" in {
    ByteFormatter.format(4L * 1024 * 1024 * 1024 * 1024) shouldBe "4 TB"
  }

  it should "not exceed TB unit" in {
    ByteFormatter.format(Long.MaxValue) should include("TB")
  }

  it should "format zero bytes without decimal" in {
    ByteFormatter.format(0L) shouldBe "0 B"
  }

  it should "format 1.5 GB with decimal" in {
    ByteFormatter.format((1.5 * 1024 * 1024 * 1024).toLong) shouldBe "1.5 GB"
  }
}
