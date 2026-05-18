package io.github.yusukensanta.parqueteer.cloud

import io.github.yusukensanta.parqueteer.core.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CloudCredentialManagerTest extends AnyFlatSpec with Matchers {

  "CloudCredentialManager" should "return S3CredentialManager for S3Location" in {
    val location = S3Location("bucket", "key")
    val manager = CloudCredentialManager.forLocation(location)

    manager shouldBe defined
    manager.get shouldBe a[S3CredentialManager]
  }

  it should "return GCSCredentialManager for GCSLocation" in {
    val location = GCSLocation("bucket", "path")
    val manager = CloudCredentialManager.forLocation(location)

    manager shouldBe defined
    manager.get shouldBe a[GCSCredentialManager]
  }

  it should "return AzureCredentialManager for AzureLocation" in {
    val location = AzureLocation("account", "container", "path")
    val manager = CloudCredentialManager.forLocation(location)

    manager shouldBe defined
    manager.get shouldBe a[AzureCredentialManager]
  }

  it should "return None for LocalPath" in {
    val location = LocalPath("/local/path")
    val manager = CloudCredentialManager.forLocation(location)

    manager shouldBe empty
  }

  // ── Hadoop config output (requires AWS_ACCESS_KEY_ID in env) ────────────

  "S3CredentialManager.configureHadoop" should "set fs.s3a.impl when credentials are available" in {
    assume(
      sys.env.contains("AWS_ACCESS_KEY_ID"),
      "Skipped: AWS_ACCESS_KEY_ID not set (runs in S3 integration CI job)"
    )
    val conf =
      new S3CredentialManager().configureHadoop(S3Location("bucket", "key"))
    conf.isSuccess shouldBe true
    conf.get.get(
      "fs.s3a.impl"
    ) shouldBe "org.apache.hadoop.fs.s3a.S3AFileSystem"
    conf.get.get(
      "fs.AbstractFileSystem.s3a.impl"
    ) shouldBe "org.apache.hadoop.fs.s3a.S3A"
  }

  it should "set fs.s3a.access.key from AWS_ACCESS_KEY_ID env var" in {
    assume(
      sys.env.contains("AWS_ACCESS_KEY_ID"),
      "Skipped: AWS_ACCESS_KEY_ID not set (runs in S3 integration CI job)"
    )
    val conf =
      new S3CredentialManager().configureHadoop(S3Location("bucket", "key"))
    conf.isSuccess shouldBe true
    conf.get.get("fs.s3a.access.key") shouldBe sys.env("AWS_ACCESS_KEY_ID")
  }

  it should "set fs.s3a.endpoint when AWS_ENDPOINT_URL is set" in {
    assume(
      sys.env.contains("AWS_ACCESS_KEY_ID") && sys.env.contains(
        "AWS_ENDPOINT_URL"
      ),
      "Skipped: AWS_ACCESS_KEY_ID or AWS_ENDPOINT_URL not set"
    )
    val conf =
      new S3CredentialManager().configureHadoop(S3Location("bucket", "key"))
    conf.isSuccess shouldBe true
    conf.get.get("fs.s3a.endpoint") shouldBe sys.env("AWS_ENDPOINT_URL")
    conf.get.get("fs.s3a.path.style.access") shouldBe "true"
  }
}
