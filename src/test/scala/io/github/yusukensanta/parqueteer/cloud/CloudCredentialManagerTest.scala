package io.github.yusukensanta.parqueteer.cloud

import io.github.yusukensanta.parqueteer.core.models.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CloudCredentialManagerTest extends AnyFlatSpec with Matchers {

  "CloudCredentialManager" should "return S3CredentialManager for S3Location" in {
    val location = S3Location("bucket", "key")
    val manager  = CloudCredentialManager.forLocation(location)

    manager shouldBe defined
    manager.get shouldBe a[S3CredentialManager]
  }

  it should "return GCSCredentialManager for GCSLocation" in {
    val location = GCSLocation("bucket", "path")
    val manager  = CloudCredentialManager.forLocation(location)

    manager shouldBe defined
    manager.get shouldBe a[GCSCredentialManager]
  }

  it should "return AzureCredentialManager for AzureLocation" in {
    val location = AzureLocation("account", "container", "path")
    val manager  = CloudCredentialManager.forLocation(location)

    manager shouldBe defined
    manager.get shouldBe a[AzureCredentialManager]
  }

  "CloudCredentialManager.firstSuccess" should "aggregate all failure messages and preserve the last cause" in {
    val err1 = new RuntimeException("strategy-1-failed")
    val err2 = new RuntimeException("strategy-2-failed")
    val result = CloudCredentialManager.firstSuccess[Int](
      "No strategies worked:",
      List(() => scala.util.Failure(err1), () => scala.util.Failure(err2))
    )
    result.isFailure shouldBe true
    val ex = result.failed.get
    ex.getMessage should include("strategy-1-failed")
    ex.getMessage should include("strategy-2-failed")
    ex.getCause shouldBe err2
  }

  it should "return None for LocalPath" in {
    val location = LocalPath("/local/path")
    val manager  = CloudCredentialManager.forLocation(location)

    manager shouldBe empty
  }

  it should "pass profile to S3CredentialManager when provided" in {
    val location = S3Location("bucket", "key")
    val manager =
      CloudCredentialManager.forLocation(location, profile = Some("my-profile"))

    manager shouldBe defined
    manager.get shouldBe a[S3CredentialManager]
  }

  it should "return S3CredentialManager without profile when profile is None" in {
    val location = S3Location("bucket", "key")
    val manager  = CloudCredentialManager.forLocation(location, profile = None)

    manager shouldBe defined
    manager.get shouldBe a[S3CredentialManager]
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

  it should "set fs.s3a.connection.ssl.enabled=false when AWS_ENDPOINT_URL uses http://" in {
    assume(
      sys.env.contains("AWS_ACCESS_KEY_ID") &&
        sys.env
          .get("AWS_ENDPOINT_URL")
          .exists(_.toLowerCase(java.util.Locale.ROOT).startsWith("http://")),
      "Skipped: AWS_ACCESS_KEY_ID not set or AWS_ENDPOINT_URL is not an http:// endpoint"
    )
    val conf =
      new S3CredentialManager().configureHadoop(S3Location("bucket", "key"))
    conf.isSuccess shouldBe true
    conf.get.get("fs.s3a.connection.ssl.enabled") shouldBe "false"
  }

  it should "not set fs.s3a.connection.ssl.enabled when AWS_ENDPOINT_URL uses https://" in {
    assume(
      sys.env.contains("AWS_ACCESS_KEY_ID") &&
        sys.env
          .get("AWS_ENDPOINT_URL")
          .exists(_.toLowerCase(java.util.Locale.ROOT).startsWith("https://")),
      "Skipped: AWS_ACCESS_KEY_ID not set or AWS_ENDPOINT_URL is not an https:// endpoint"
    )
    val conf =
      new S3CredentialManager().configureHadoop(S3Location("bucket", "key"))
    conf.isSuccess shouldBe true
    conf.get.get("fs.s3a.connection.ssl.enabled", null) shouldBe null
  }

  "S3CredentialManager.tryInstanceProfile" should "return Failure gracefully when not on EC2 (no IMDS)" in {
    val mgr = new S3CredentialManager()
    // In non-EC2 environment InstanceProfileCredentialsProvider cannot reach IMDS.
    // Verify the method wraps the failure in Try (does not throw).
    val result = mgr.tryInstanceProfile()
    result.isFailure shouldBe true
  }

  "S3CredentialManager.endpointDisablesSsl" should "only disable SSL for http:// endpoints" in {
    val mgr = new S3CredentialManager()
    mgr.endpointDisablesSsl("http://localhost:9000") shouldBe true
    mgr.endpointDisablesSsl("http://127.0.0.1:8080") shouldBe true
    mgr.endpointDisablesSsl("https://minio.example.com") shouldBe false
    mgr.endpointDisablesSsl("https://s3.amazonaws.com") shouldBe false
    mgr.endpointDisablesSsl("https://localhost:9000") shouldBe false
    mgr.endpointDisablesSsl("HTTP://localhost:9000") shouldBe true
    mgr.endpointDisablesSsl("Http://minio.example.com") shouldBe true
  }

  it should "return false (not disable SSL) when endpoint has no scheme" in {
    // scheme-less endpoints don't start with http:// so SSL stays enabled;
    // the warning path is exercised separately
    val mgr = new S3CredentialManager()
    mgr.endpointDisablesSsl("localhost:9000") shouldBe false
    mgr.endpointDisablesSsl("minio.example.com") shouldBe false
  }

  // ── GCSCredentialManager ────────────────────────────────────────────────

  "GCSCredentialManager.configureHadoop" should "succeed for GCSLocation (falls back to application default when no service account found)" in {
    val conf =
      new GCSCredentialManager().configureHadoop(GCSLocation("bucket", "path"))
    conf.isSuccess shouldBe true
    conf.get.get("fs.gs.impl") shouldBe
      "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem"
  }

  it should "return Failure for non-GCS location" in {
    val conf =
      new GCSCredentialManager().configureHadoop(S3Location("bucket", "key"))
    conf.isFailure shouldBe true
    conf.failed.get shouldBe a[IllegalArgumentException]
  }

  it should "set fs.gs.project.id when GCP_PROJECT_ID env var is present" in {
    assume(
      sys.env.contains("GCP_PROJECT_ID") || sys.env.contains(
        "GOOGLE_CLOUD_PROJECT"
      ),
      "Skipped: GCP_PROJECT_ID / GOOGLE_CLOUD_PROJECT not set"
    )
    val conf =
      new GCSCredentialManager().configureHadoop(GCSLocation("bucket", "path"))
    conf.isSuccess shouldBe true
    conf.get.get("fs.gs.project.id") should not be null
  }

  it should "use class name when getMessage returns null" in {
    val nullMsgErr = new NullPointerException()
    val result = CloudCredentialManager.firstSuccess[Int](
      "All failed:",
      List(() => scala.util.Failure(nullMsgErr))
    )
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("NullPointerException")
  }

  // ── AzureCredentialManager per-account auth.type (H4) ────────────────────

  "AzureCredentialManager" should "set per-account auth.type=OAuth for managed_identity (default)" in {
    assume(
      !sys.env.contains("AZURE_AUTH_METHOD") ||
        sys.env("AZURE_AUTH_METHOD") == "managed_identity",
      "Skipped: AZURE_AUTH_METHOD is not managed_identity"
    )
    val loc    = AzureLocation("myaccount", "mycontainer", "path/to/file")
    val result = new AzureCredentialManager().configureHadoop(loc)
    result.isSuccess shouldBe true
    val conf = result.get
    conf.get(
      "fs.azure.account.auth.type.myaccount.dfs.core.windows.net"
    ) shouldBe "OAuth"
  }

  it should "set per-account auth.type=SharedKey for shared_key (not inherit global OAuth)" in {
    assume(
      sys.env.contains("AZURE_AUTH_METHOD") &&
        sys.env("AZURE_AUTH_METHOD") == "shared_key" &&
        sys.env.contains("AZURE_STORAGE_KEY"),
      "Skipped: AZURE_AUTH_METHOD=shared_key and AZURE_STORAGE_KEY not set"
    )
    val loc    = AzureLocation("myaccount", "mycontainer", "path/to/file")
    val result = new AzureCredentialManager().configureHadoop(loc)
    result.isSuccess shouldBe true
    val conf = result.get
    conf.get(
      "fs.azure.account.auth.type.myaccount.dfs.core.windows.net"
    ) shouldBe "SharedKey"
  }

  it should "set per-account auth.type=SAS and ABFS fixed-token key for sas_token" in {
    assume(
      sys.env.contains("AZURE_AUTH_METHOD") &&
        sys.env("AZURE_AUTH_METHOD") == "sas_token" &&
        sys.env.contains("AZURE_STORAGE_SAS_TOKEN"),
      "Skipped: AZURE_AUTH_METHOD=sas_token and AZURE_STORAGE_SAS_TOKEN not set"
    )
    val loc    = AzureLocation("myaccount", "mycontainer", "path/to/file")
    val result = new AzureCredentialManager().configureHadoop(loc)
    result.isSuccess shouldBe true
    val conf = result.get
    conf.get(
      "fs.azure.account.auth.type.myaccount.dfs.core.windows.net"
    ) shouldBe "SAS"
    // ABFS FixedSASTokenProvider reads from fs.azure.sas.fixed.token.* — not the legacy WASB key.
    conf.get(
      "fs.azure.sas.fixed.token.mycontainer.myaccount.dfs.core.windows.net"
    ) should not be null
    conf.get(
      "fs.azure.sas.mycontainer.myaccount.dfs.core.windows.net"
    ) shouldBe null
  }
}
