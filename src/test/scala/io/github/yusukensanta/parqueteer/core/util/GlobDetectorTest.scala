package io.github.yusukensanta.parqueteer.core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GlobDetectorTest extends AnyFlatSpec with Matchers {

  "GlobDetector.hasGlobChars" should "return false for a plain local path" in {
    GlobDetector.hasGlobChars("/data/file.parquet") shouldBe false
  }

  it should "return false for a plain cloud path" in {
    GlobDetector.hasGlobChars("s3://bucket/data/file.parquet") shouldBe false
  }

  it should "return true for a star wildcard" in {
    GlobDetector.hasGlobChars("s3://bucket/2026-*.parquet") shouldBe true
  }

  it should "return true for a question-mark wildcard" in {
    GlobDetector.hasGlobChars("/data/file?.parquet") shouldBe true
  }

  it should "return true for a bracket character class" in {
    GlobDetector.hasGlobChars("/data/file[0-9].parquet") shouldBe true
  }

  it should "return true for a brace alternation" in {
    GlobDetector.hasGlobChars("/data/{jan,feb}.parquet") shouldBe true
  }

  it should "return false for an empty string" in {
    GlobDetector.hasGlobChars("") shouldBe false
  }
}
