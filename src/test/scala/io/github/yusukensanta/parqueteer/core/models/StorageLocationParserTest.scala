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

  // ── .path output tests (these caught the s3:// vs s3a:// regression) ──

  "S3Location.path" should "emit s3a scheme for Hadoop filesystem routing" in {
    S3Location(
      "my-bucket",
      "path/to/file.parquet"
    ).path shouldBe "s3a://my-bucket/path/to/file.parquet"
  }

  "GCSLocation.path" should "emit gs scheme" in {
    GCSLocation(
      "my-bucket",
      "path/to/file.parquet"
    ).path shouldBe "gs://my-bucket/path/to/file.parquet"
  }

  "AzureLocation.path" should "emit valid abfss URI" in {
    AzureLocation(
      "myaccount",
      "mycontainer",
      "path/to/file.parquet"
    ).path shouldBe
      "abfss://mycontainer@myaccount.dfs.core.windows.net/path/to/file.parquet"
  }

  // ── Scheme-contract: location.path scheme must have registered fs impl ──

  "S3Location path scheme" should "be s3a to match the fs.s3a.impl Hadoop key" in {
    val scheme = new java.net.URI(S3Location("b", "k").path).getScheme
    scheme shouldBe "s3a"
  }

  "GCSLocation path scheme" should "be gs to match the fs.gs.impl Hadoop key" in {
    val scheme = new java.net.URI(GCSLocation("b", "k").path).getScheme
    scheme shouldBe "gs"
  }

  "AzureLocation path scheme" should "be abfss to match the fs.azure abfss Hadoop key" in {
    val scheme =
      new java.net.URI(AzureLocation("acct", "cont", "k").path).getScheme
    scheme shouldBe "abfss"
  }

  // ── Single-slash typo detection (#180) ──────────────────────────────────────

  "StorageLocationParser.parse" should "reject s3:/bucket/key with a helpful suggestion" in {
    val result = StorageLocationParser.parse("s3:/bucket/key")
    result.isLeft shouldBe true
    result.left.get should include("s3://bucket/key")
  }

  it should "reject gs:/bucket/path with a helpful suggestion" in {
    val result = StorageLocationParser.parse("gs:/bucket/path")
    result.isLeft shouldBe true
    result.left.get should include("gs://bucket/path")
  }

  it should "still parse s3://bucket/key correctly after the new guard" in {
    StorageLocationParser.parse("s3://bucket/key") shouldBe Right(
      S3Location("bucket", "key")
    )
  }

  it should "still treat /local/path as LocalPath" in {
    StorageLocationParser.parse("/local/path") shouldBe Right(
      LocalPath("/local/path")
    )
  }

  // ── Round-trip: parse → .path → parse must yield the same location ────────

  "GCSLocation" should "round-trip through .path" in {
    val original = GCSLocation("my-bucket", "data/file.parquet")
    StorageLocationParser.parse(original.path) shouldBe Right(original)
  }

  it should "round-trip with nested path" in {
    val original = GCSLocation("bucket", "a/b/c/d.parquet")
    StorageLocationParser.parse(original.path) shouldBe Right(original)
  }

  it should "round-trip with root-level key" in {
    val original = GCSLocation("bucket", "file.parquet")
    StorageLocationParser.parse(original.path) shouldBe Right(original)
  }

  "AzureLocation" should "round-trip through .path" in {
    val original =
      AzureLocation("myaccount", "mycontainer", "data/file.parquet")
    StorageLocationParser.parse(original.path) shouldBe Right(original)
  }

  it should "round-trip with nested path" in {
    val original = AzureLocation("account", "container", "a/b/c/d.parquet")
    StorageLocationParser.parse(original.path) shouldBe Right(original)
  }

  it should "round-trip with root-level key" in {
    val original = AzureLocation("acct", "cont", "file.parquet")
    StorageLocationParser.parse(original.path) shouldBe Right(original)
  }

  "S3Location" should "round-trip through .path (s3a output parses back to S3Location)" in {
    val original = S3Location("my-bucket", "data/file.parquet")
    StorageLocationParser.parse(original.path) shouldBe Right(original)
  }

  // ── M4: extended Azure URI vocabulary ────────────────────────────────────
  "StorageLocationParser" should "parse abfs:// (non-secure) as AzureLocation" in {
    val result = StorageLocationParser.parse(
      "abfs://mycontainer@myaccount.dfs.core.windows.net/data/file.parquet"
    )
    result shouldBe Right(
      AzureLocation("myaccount", "mycontainer", "data/file.parquet")
    )
  }

  it should "return a helpful error for wasb:// URIs" in {
    val result = StorageLocationParser.parse(
      "wasb://container@account.blob.core.windows.net/key"
    )
    result.isLeft shouldBe true
    result.left.toOption.get should include("wasb")
    result.left.toOption.get should include("abfss://")
  }

  it should "return a helpful error for wasbs:// URIs" in {
    val result = StorageLocationParser.parse(
      "wasbs://container@account.blob.core.windows.net/key"
    )
    result.isLeft shouldBe true
    result.left.toOption.get should include("wasb")
  }
}
