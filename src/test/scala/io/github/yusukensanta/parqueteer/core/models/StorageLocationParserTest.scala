package io.github.yusukensanta.parqueteer.core.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StorageLocationParserTest extends AnyFlatSpec with Matchers {
  "StorageLocationParser" should "parse S3 URLs correctly" in {
    val result =
      StorageLocationParser.parse("s3://my-bucket/path/to/file.parquet")
    result shouldBe Right(S3Location("my-bucket", "path/to/file.parquet"))
  }

  it should "parse GCS URLs correctly" in {
    val result =
      StorageLocationParser.parse("gs://my-bucket/path/to/file.parquet")
    result shouldBe Right(GCSLocation("my-bucket", "path/to/file.parquet"))
  }

  it should "parse Azure URLs correctly" in {
    val result = StorageLocationParser.parse(
      "abfss://container@account.dfs.core.windows.net/path/to/file.parquet"
    )
    result shouldBe Right(
      AzureLocation("account", "container", "path/to/file.parquet")
    )
  }

  it should "parse local paths correctly" in {
    val result = StorageLocationParser.parse("/local/path/to/file.parquet")
    result shouldBe Right(LocalPath("/local/path/to/file.parquet"))
  }

  it should "return error for unsupported URLs" in {
    val result = StorageLocationParser.parse("ftp://example.com/file.parquet")
    result.isLeft shouldBe true
  }

}
