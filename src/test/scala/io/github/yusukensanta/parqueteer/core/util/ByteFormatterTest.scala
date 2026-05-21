package io.github.yusukensanta.parqueteer.core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ByteFormatterTest extends AnyFlatSpec with Matchers {

  "ByteFormatter.format" should "format bytes" in {
    ByteFormatter.format(512L) shouldBe "512.0 B"
  }

  it should "format exactly 1024 bytes as 1.0 KB" in {
    ByteFormatter.format(1024L) shouldBe "1.0 KB"
  }

  it should "format kilobytes" in {
    ByteFormatter.format(2048L) shouldBe "2.0 KB"
  }

  it should "format fractional kilobytes" in {
    ByteFormatter.format(1536L) shouldBe "1.5 KB"
  }

  it should "format megabytes" in {
    ByteFormatter.format(2 * 1024 * 1024L) shouldBe "2.0 MB"
  }

  it should "format gigabytes" in {
    ByteFormatter.format(3L * 1024 * 1024 * 1024) shouldBe "3.0 GB"
  }

  it should "format terabytes" in {
    ByteFormatter.format(4L * 1024 * 1024 * 1024 * 1024) shouldBe "4.0 TB"
  }

  it should "not exceed TB unit" in {
    ByteFormatter.format(Long.MaxValue) should include("TB")
  }

  it should "format zero bytes" in {
    ByteFormatter.format(0L) shouldBe "0.0 B"
  }
}
