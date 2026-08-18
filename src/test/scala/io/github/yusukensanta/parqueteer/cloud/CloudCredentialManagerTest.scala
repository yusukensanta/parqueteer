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

  it should "wire s3EndpointUrl override into S3CredentialManager" in {
    val location = S3Location("bucket", "key")
    val manager = CloudCredentialManager.forLocation(
      location,
      s3EndpointUrl = Some("http://localhost:9000")
    )
    manager shouldBe defined
    val mgr = manager.get.asInstanceOf[S3CredentialManager]
    mgr.env("AWS_ENDPOINT_URL") shouldBe Some("http://localhost:9000")
  }

  "CloudCredentialManager.requiredEnv" should "return the env var value when set" in {
    assume(sys.env.contains("HOME"), "Skipped: HOME not set")
    CloudCredentialManager.requiredEnv("HOME") shouldBe sys.env("HOME")
  }

  it should "throw RuntimeException when env var is not set" in {
    val ex = intercept[RuntimeException] {
      CloudCredentialManager.requiredEnv("PARQUETEER_TEST_NONEXISTENT_VAR_XYZ")
    }
    ex.getMessage should include("PARQUETEER_TEST_NONEXISTENT_VAR_XYZ")
    ex.getMessage should include("is not set")
  }

  // ── Hadoop config output ─────────────────────────────────────────────────
  // configureHadoop no longer resolves credentials eagerly — it only wires
  // fs.s3a.aws.credentials.provider and lets S3A resolve (and refresh) them
  // per request, so none of these need real AWS credentials in the env.

  "S3CredentialManager.configureHadoop" should "set fs.s3a.impl" in {
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

  it should "set fs.s3a.aws.credentials.provider to the default chain, and no static keys, when no profile given" in {
    val conf =
      new S3CredentialManager().configureHadoop(S3Location("bucket", "key"))
    conf.isSuccess shouldBe true
    val provider = conf.get.get("fs.s3a.aws.credentials.provider")
    provider should include("SimpleAWSCredentialsProvider")
    provider should include("EnvironmentVariableCredentialsProvider")
    provider should include("ProfileCredentialsProvider")
    provider should include("IAMInstanceCredentialsProvider")
    conf.get.get("fs.s3a.access.key") shouldBe null
    conf.get.get("fs.s3a.secret.key") shouldBe null
  }

  it should "pin fs.s3a.aws.credentials.provider to ProfileCredentialsProvider and set aws.profile system property when profile given" in {
    val conf = new S3CredentialManager(profile = Some("my-test-profile"))
      .configureHadoop(S3Location("bucket", "key"))
    conf.isSuccess shouldBe true
    conf.get.get(
      "fs.s3a.aws.credentials.provider"
    ) shouldBe "software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider"
    System.getProperty("aws.profile") shouldBe "my-test-profile"
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
    val mgr = new S3CredentialManager()
    mgr.endpointDisablesSsl("localhost:9000") shouldBe false
    mgr.endpointDisablesSsl("minio.example.com") shouldBe false
  }

  it should "return false for empty string" in {
    val mgr = new S3CredentialManager()
    mgr.endpointDisablesSsl("") shouldBe false
  }

  "S3CredentialManager" should "return Failure for non-S3 location" in {
    val mgr    = new S3CredentialManager()
    val result = mgr.configureHadoop(GCSLocation("bucket", "path"))
    result.isFailure shouldBe true
    result.failed.get shouldBe a[IllegalArgumentException]
  }

  it should "wire an explicit profile through without resolving it eagerly (deferred to S3A)" in {
    // configureHadoop only builds config now; a nonexistent profile is only
    // discovered when S3A actually tries to authenticate against it.
    val mgr    = new S3CredentialManager(profile = Some("nonexistent-profile-xyz"))
    val result = mgr.configureHadoop(S3Location("bucket", "key"))
    result.isSuccess shouldBe true
    System.getProperty("aws.profile") shouldBe "nonexistent-profile-xyz"
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

  // ── AzureCredentialManager env-injectable tests ────────────────────────

  "AzureCredentialManager" should "cover managed_identity with optional AZURE_TENANT_ID and AZURE_CLIENT_ID" in {
    val loc = AzureLocation("acct", "ctr", "blob")
    val mgr = new AzureCredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AZURE_AUTH_METHOD" => None
        case "AZURE_TENANT_ID"   => Some("tenant-123")
        case "AZURE_CLIENT_ID"   => Some("client-abc")
        case _                   => None
      }
    }
    val result = mgr.configureHadoop(loc)
    result.isSuccess shouldBe true
    val conf = result.get
    conf.get("fs.azure.account.auth.type.acct.dfs.core.windows.net") shouldBe "OAuth"
    conf.get("fs.azure.account.oauth2.msi.tenant.acct.dfs.core.windows.net") shouldBe "tenant-123"
    conf.get("fs.azure.account.oauth2.client.id.acct.dfs.core.windows.net") shouldBe "client-abc"
  }

  it should "cover managed_identity without optional env vars" in {
    val loc = AzureLocation("acct", "ctr", "blob")
    val mgr = new AzureCredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AZURE_AUTH_METHOD" => None
        case _                   => None
      }
    }
    val result = mgr.configureHadoop(loc)
    result.isSuccess shouldBe true
    result.get.get("fs.azure.account.auth.type.acct.dfs.core.windows.net") shouldBe "OAuth"
  }

  it should "cover service_principal when all required env vars are set" in {
    val loc = AzureLocation("acct", "ctr", "blob")
    val mgr = new AzureCredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AZURE_AUTH_METHOD"   => Some("service_principal")
        case "AZURE_CLIENT_ID"     => Some("cid")
        case "AZURE_CLIENT_SECRET" => Some("csec")
        case "AZURE_TENANT_ID"     => Some("tid")
        case _                     => None
      }
    }
    val result = mgr.configureHadoop(loc)
    result.isSuccess shouldBe true
    val conf = result.get
    conf.get("fs.azure.account.auth.type.acct.dfs.core.windows.net") shouldBe "OAuth"
    conf.get("fs.azure.account.oauth2.client.id.acct.dfs.core.windows.net") shouldBe "cid"
    conf.get("fs.azure.account.oauth2.client.secret.acct.dfs.core.windows.net") shouldBe "csec"
    conf.get("fs.azure.account.oauth2.client.endpoint.acct.dfs.core.windows.net") should include(
      "tid"
    )
  }

  it should "return Failure for service_principal when AZURE_CLIENT_ID is missing" in {
    val loc = AzureLocation("acct", "ctr", "blob")
    val mgr = new AzureCredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AZURE_AUTH_METHOD" => Some("service_principal")
        case _                   => None
      }
    }
    val result = mgr.configureHadoop(loc)
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("AZURE_CLIENT_ID")
  }

  it should "cover shared_key when AZURE_STORAGE_KEY is set" in {
    val loc = AzureLocation("acct", "ctr", "blob")
    val mgr = new AzureCredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AZURE_AUTH_METHOD" => Some("shared_key")
        case "AZURE_STORAGE_KEY" => Some("the-key")
        case _                   => None
      }
    }
    val result = mgr.configureHadoop(loc)
    result.isSuccess shouldBe true
    val conf = result.get
    conf.get("fs.azure.account.auth.type.acct.dfs.core.windows.net") shouldBe "SharedKey"
    conf.get("fs.azure.account.key.acct.dfs.core.windows.net") shouldBe "the-key"
  }

  it should "return Failure for shared_key when AZURE_STORAGE_KEY is missing" in {
    val loc = AzureLocation("acct", "ctr", "blob")
    val mgr = new AzureCredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AZURE_AUTH_METHOD" => Some("shared_key")
        case _                   => None
      }
    }
    mgr.configureHadoop(loc).isFailure shouldBe true
  }

  it should "cover sas_token when AZURE_STORAGE_SAS_TOKEN is set" in {
    val loc = AzureLocation("acct", "ctr", "blob")
    val mgr = new AzureCredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AZURE_AUTH_METHOD"       => Some("sas_token")
        case "AZURE_STORAGE_SAS_TOKEN" => Some("sv=2023&...")
        case _                         => None
      }
    }
    val result = mgr.configureHadoop(loc)
    result.isSuccess shouldBe true
    val conf = result.get
    conf.get("fs.azure.account.auth.type.acct.dfs.core.windows.net") shouldBe "SAS"
    conf.get("fs.azure.sas.fixed.token.ctr.acct.dfs.core.windows.net") shouldBe "sv=2023&..."
  }

  it should "return Failure for unknown AZURE_AUTH_METHOD" in {
    val loc = AzureLocation("acct", "ctr", "blob")
    val mgr = new AzureCredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AZURE_AUTH_METHOD" => Some("kerberos")
        case _                   => None
      }
    }
    val result = mgr.configureHadoop(loc)
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("Unknown Azure auth method")
    result.failed.get.getMessage should include("kerberos")
  }

  // ── S3CredentialManager Hadoop config tests ─────────────────────────────

  "S3CredentialManager.configureHadoop" should "set all tuning constants in Hadoop config" in {
    val mgr    = new S3CredentialManager()
    val result = mgr.configureHadoop(S3Location("bucket", "key"))
    result.isSuccess shouldBe true
    val conf = result.get
    conf.get("fs.s3a.connection.maximum") shouldBe S3Tuning.MaxConnections
    conf.get("fs.s3a.attempts.maximum") shouldBe S3Tuning.MaxAttempts
    conf.get("fs.s3a.retry.throttle.limit") shouldBe S3Tuning.ThrottleRetryLimit
    conf.get("fs.s3a.retry.throttle.interval") shouldBe S3Tuning.ThrottleRetryInterval
    conf.get("fs.s3a.fast.upload") shouldBe "true"
  }

  it should "set region when S3Location has a region" in {
    val mgr = new S3CredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AWS_ACCESS_KEY_ID"     => Some("KEY")
        case "AWS_SECRET_ACCESS_KEY" => Some("SEC")
        case _                       => None
      }
    }
    val result = mgr.configureHadoop(S3Location("bucket", "key", region = Some("ap-northeast-1")))
    result.isSuccess shouldBe true
    result.get.get("fs.s3a.endpoint.region") shouldBe "ap-northeast-1"
  }

  it should "add https:// prefix and emit warning when AWS_ENDPOINT_URL has no scheme" in {
    val mgr = new S3CredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AWS_ACCESS_KEY_ID"     => Some("KEY")
        case "AWS_SECRET_ACCESS_KEY" => Some("SEC")
        case "AWS_ENDPOINT_URL"      => Some("localhost:9000")
        case _                       => None
      }
    }
    val result = mgr.configureHadoop(S3Location("bucket", "key"))
    result.isSuccess shouldBe true
    result.get.get("fs.s3a.endpoint") shouldBe "https://localhost:9000"
  }

  it should "set ssl.enabled=false when AWS_ENDPOINT_URL is http://" in {
    val mgr = new S3CredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AWS_ACCESS_KEY_ID"     => Some("KEY")
        case "AWS_SECRET_ACCESS_KEY" => Some("SEC")
        case "AWS_ENDPOINT_URL"      => Some("http://localhost:9000")
        case _                       => None
      }
    }
    val result = mgr.configureHadoop(S3Location("bucket", "key"))
    result.isSuccess shouldBe true
    result.get.get("fs.s3a.connection.ssl.enabled") shouldBe "false"
  }

  it should "NOT set ssl.enabled when AWS_ENDPOINT_URL is https://" in {
    val mgr = new S3CredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AWS_ACCESS_KEY_ID"     => Some("KEY")
        case "AWS_SECRET_ACCESS_KEY" => Some("SEC")
        case "AWS_ENDPOINT_URL"      => Some("https://minio.example.com")
        case _                       => None
      }
    }
    val result = mgr.configureHadoop(S3Location("bucket", "key"))
    result.isSuccess shouldBe true
    result.get.get("fs.s3a.connection.ssl.enabled") should not be "false"
  }

  it should "use endpointOverride from constructor when set, regardless of env" in {
    // endpointOverride takes priority: env() returns override, not sys.env fallback
    val mgr = new S3CredentialManager(endpointOverride = Some("http://config-host:9000"))
    mgr.env("AWS_ENDPOINT_URL") shouldBe Some("http://config-host:9000")
  }

  it should "fall back to sys.env when endpointOverride is None" in {
    // Without endpointOverride, non-endpoint keys are unaffected
    val mgr = new S3CredentialManager(endpointOverride = None)
    mgr.env("AWS_ACCESS_KEY_ID") shouldBe sys.env.get("AWS_ACCESS_KEY_ID")
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
