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

  it should "resolve credentials with explicit profile" in {
    val mgr    = new S3CredentialManager(profile = Some("nonexistent-profile-xyz"))
    val result = mgr.configureHadoop(S3Location("bucket", "key"))
    result.isFailure shouldBe true
  }

  "S3CredentialManager.tryEnvironmentVariables" should "succeed when AWS_ACCESS_KEY_ID is set" in {
    assume(
      sys.env.contains("AWS_ACCESS_KEY_ID") && sys.env.contains("AWS_SECRET_ACCESS_KEY"),
      "Skipped: AWS env vars not set"
    )
    val mgr    = new S3CredentialManager()
    val result = mgr.configureHadoop(S3Location("bucket", "key"))
    result.isSuccess shouldBe true
    result.get.get("fs.s3a.access.key") shouldBe sys.env("AWS_ACCESS_KEY_ID")
    result.get.get("fs.s3a.secret.key") shouldBe sys.env("AWS_SECRET_ACCESS_KEY")
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

  // ── S3CredentialManager env-injectable tests ───────────────────────────

  "S3CredentialManager.tryEnvironmentVariables" should "succeed with AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY injected" in {
    val mgr = new S3CredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AWS_ACCESS_KEY_ID"     => Some("TESTKEY")
        case "AWS_SECRET_ACCESS_KEY" => Some("TESTSECRET")
        case _                       => None
      }
    }
    val result = mgr.tryEnvironmentVariables()
    result.isSuccess shouldBe true
    val (accessKey, secretKey, sessionToken) = result.get
    accessKey shouldBe "TESTKEY"
    secretKey shouldBe "TESTSECRET"
    sessionToken shouldBe None
  }

  it should "succeed with legacy AWS_ACCESS_KEY and AWS_SECRET_KEY env vars" in {
    val mgr = new S3CredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AWS_ACCESS_KEY" => Some("LEGACYKEY")
        case "AWS_SECRET_KEY" => Some("LEGACYSEC")
        case _                => None
      }
    }
    val result = mgr.tryEnvironmentVariables()
    result.isSuccess shouldBe true
    result.get._1 shouldBe "LEGACYKEY"
    result.get._2 shouldBe "LEGACYSEC"
  }

  it should "include session token when AWS_SESSION_TOKEN is present" in {
    val mgr = new S3CredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AWS_ACCESS_KEY_ID"     => Some("KEY")
        case "AWS_SECRET_ACCESS_KEY" => Some("SEC")
        case "AWS_SESSION_TOKEN"     => Some("TOK")
        case _                       => None
      }
    }
    val result = mgr.tryEnvironmentVariables()
    result.isSuccess shouldBe true
    result.get._3 shouldBe Some("TOK")
  }

  it should "include session token from AWS_SECURITY_TOKEN when AWS_SESSION_TOKEN absent" in {
    val mgr = new S3CredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AWS_ACCESS_KEY_ID"     => Some("KEY")
        case "AWS_SECRET_ACCESS_KEY" => Some("SEC")
        case "AWS_SECURITY_TOKEN"    => Some("SECTOK")
        case _                       => None
      }
    }
    val result = mgr.tryEnvironmentVariables()
    result.isSuccess shouldBe true
    result.get._3 shouldBe Some("SECTOK")
  }

  it should "return Failure when AWS_ACCESS_KEY_ID is absent" in {
    val mgr = new S3CredentialManager {
      override def env(key: String): Option[String] = None
    }
    val result = mgr.tryEnvironmentVariables()
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("AWS_ACCESS_KEY_ID")
  }

  it should "return Failure when AWS_SECRET_ACCESS_KEY is absent but access key is present" in {
    val mgr = new S3CredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AWS_ACCESS_KEY_ID" => Some("KEY")
        case _                   => None
      }
    }
    val result = mgr.tryEnvironmentVariables()
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("AWS_SECRET_ACCESS_KEY")
  }

  "S3CredentialManager.configureHadoop" should "set all tuning constants in Hadoop config when credentials available" in {
    val mgr = new S3CredentialManager {
      override def env(key: String): Option[String] = key match {
        case "AWS_ACCESS_KEY_ID"     => Some("KEY")
        case "AWS_SECRET_ACCESS_KEY" => Some("SEC")
        case _                       => None
      }
    }
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
