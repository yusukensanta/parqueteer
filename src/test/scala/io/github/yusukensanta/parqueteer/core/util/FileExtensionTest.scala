package io.github.yusukensanta.parqueteer.core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FileExtensionTest extends AnyFlatSpec with Matchers {

  "FileExtension.of" should "extract extension from a local path" in {
    FileExtension.of("/data/file.parquet") shouldBe "parquet"
  }

  it should "extract extension from an S3 URI" in {
    FileExtension.of("s3://bucket/path/to/file.parquet") shouldBe "parquet"
  }

  it should "strip query string before extracting extension" in {
    FileExtension.of(
      "s3://bucket/file.parquet?versionId=abc123"
    ) shouldBe "parquet"
  }

  it should "return unknown when no extension is present" in {
    FileExtension.of("/data/noextension") shouldBe "unknown"
  }

  it should "lowercase the extension" in {
    FileExtension.of("/data/file.PARQUET") shouldBe "parquet"
  }

  it should "handle csv extension" in {
    FileExtension.of("data.csv") shouldBe "csv"
  }

  it should "handle json extension" in {
    FileExtension.of("data.json") shouldBe "json"
  }

  it should "return unknown for dot-prefixed filenames (hidden files)" in {
    FileExtension.of("/home/user/.gitignore") shouldBe "unknown"
    FileExtension.of(".env") shouldBe "unknown"
  }

  it should "return the real extension for files with multiple dots" in {
    FileExtension.of("archive.tar.gz") shouldBe "gz"
  }

  it should "return unknown for a trailing dot (empty extension)" in {
    FileExtension.of("file.") shouldBe "unknown"
    FileExtension.of("/path/to/file.") shouldBe "unknown"
  }
}
