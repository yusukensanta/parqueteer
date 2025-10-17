package io.parqueteer.cloud

import io.parqueteer.core.models._
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
}
