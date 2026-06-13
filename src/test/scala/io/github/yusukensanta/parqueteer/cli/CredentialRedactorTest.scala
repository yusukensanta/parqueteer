package io.github.yusukensanta.parqueteer.cli

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen

class CredentialRedactorTest
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks {

  "CredentialRedactor.redact" should "redact Authorization header value" in {
    val input = "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.secret"
    CredentialRedactor.redact(input) should include("Authorization: [REDACTED]")
    CredentialRedactor.redact(input) should not include "eyJhbGciOiJSUzI1NiJ9"
  }

  it should "redact X-Amz-Security-Token header value" in {
    val input = "X-Amz-Security-Token: IQoJb3JpZ2luXsecrettoken"
    val result = CredentialRedactor.redact(input)
    result should include("X-Amz-Security-Token: [REDACTED]")
    result should not include "IQoJb3JpZ2luXsecrettoken"
  }

  it should "redact X-Amz-Credential header value" in {
    val input =
      "X-Amz-Credential: AKIAIOSFODNN7EXAMPLE/20230101/us-east-1/s3/aws4_request"
    val result = CredentialRedactor.redact(input)
    result should include("X-Amz-Credential: [REDACTED]")
    result should not include "AKIAIOSFODNN7EXAMPLE"
  }

  it should "NOT redact non-secret X-Amz-Date header (debug info)" in {
    val input = "X-Amz-Date: 20230101T120000Z"
    val result = CredentialRedactor.redact(input)
    // Date is non-secret: must NOT be redacted
    result shouldBe input
  }

  it should "NOT redact non-secret X-Amz-SignedHeaders header (debug info)" in {
    val input = "X-Amz-SignedHeaders: host;x-amz-content-sha256"
    val result = CredentialRedactor.redact(input)
    result shouldBe input
  }

  it should "NOT redact non-secret X-Amz-Algorithm header (debug info)" in {
    val input = "X-Amz-Algorithm: AWS4-HMAC-SHA256"
    val result = CredentialRedactor.redact(input)
    result shouldBe input
  }

  it should "redact AWSAccessKeyId query parameter" in {
    val input = "AWSAccessKeyId=AKIAIOSFODNN7EXAMPLE&other=val"
    val result = CredentialRedactor.redact(input)
    result should include("AWSAccessKeyId=[REDACTED]")
    result should not include "AKIAIOSFODNN7EXAMPLE"
  }

  it should "redact Signature query parameter" in {
    val input =
      "Signature=wJalrXUtnFEMI%2FK7MDENG%2FbPxRfiCYEXAMPLEKEY&Expires=1234"
    val result = CredentialRedactor.redact(input)
    result should include("Signature=[REDACTED]")
    result should not include "wJalrXUtnFEMI"
  }

  it should "redact aws_secret_access_key config value" in {
    val input =
      "aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    val result = CredentialRedactor.redact(input)
    result should include("aws_secret_access_key = [REDACTED]")
    result should not include "wJalrXUtnFEMI"
  }

  it should "be case-insensitive for header names" in {
    val input = "authorization: token secret123"
    val result = CredentialRedactor.redact(input)
    result should not include "secret123"
    result should include("[REDACTED]")
  }

  it should "redact raw AWS access key (AKIA* prefix) in unstructured message" in {
    val input = "Forbidden for AKIAIOSFODNN7EXAMPLE while accessing bucket"
    val result = CredentialRedactor.redact(input)
    result should not include "AKIAIOSFODNN7EXAMPLE"
    result should include("[REDACTED]")
  }

  it should "redact raw AWS SSO access key (ASIA* prefix) in unstructured message" in {
    val input = "Access denied: ASIAIOSFODNN7EXAMPLE"
    val result = CredentialRedactor.redact(input)
    result should not include "ASIAIOSFODNN7EXAMPLE"
    result should include("[REDACTED]")
  }

  it should "leave non-credential strings unchanged" in {
    val input = "Error connecting to s3://my-bucket/key"
    CredentialRedactor.redact(input) shouldBe input
  }

  // ── L-B: service principal IDs (AROA*, AIPA*, etc.) are public — not redacted ──
  it should "NOT redact AWS IAM role/instance/service principal IDs (AROA/AIPA/ANPA/AGPA/AIDA)" in {
    val roleId = "AROAIOSFODNN7EXAMPLE12"
    val instanceProfileId = "AIPAIOSFODNN7EXAMPLE"
    val input = s"role=$roleId, profile=$instanceProfileId"
    val result = CredentialRedactor.redact(input)
    result should include(roleId)
    result should include(instanceProfileId)
    result should not include "[REDACTED]"
  }

  it should "redact multiple credentials in one string" in {
    val input =
      "Authorization: Bearer token123, AWSAccessKeyId=AKIAIOSFODNN7EXAMPLE&Signature=abc"
    val result = CredentialRedactor.redact(input)
    result should not include "token123"
    result should not include "AKIAIOSFODNN7EXAMPLE"
    result should not include "abc"
    result should include("[REDACTED]")
  }

  it should "handle empty string" in {
    CredentialRedactor.redact("") shouldBe ""
  }

  // ── Property-based ────────────────────────────────────────────────────────

  // Safe strings: lowercase letters + digits only — guaranteed to match no pattern
  private val safeStr: Gen[String] = Gen
    .listOf(
      Gen.frequency(8 -> Gen.alphaLowerChar, 2 -> Gen.numChar)
    )
    .map(_.mkString)

  "CredentialRedactor.redact (property)" should "leave credential-free strings unchanged" in {
    forAll(safeStr) { s =>
      CredentialRedactor.redact(s) shouldBe s
    }
  }

  it should "always redact the secret portion of an Authorization header" in {
    val secretGen = Gen.listOfN(16, Gen.alphaNumChar).map(_.mkString)
    forAll(secretGen) { secret =>
      val input = s"Authorization: Bearer $secret"
      CredentialRedactor.redact(input) should not include secret
    }
  }

  it should "always redact the value of aws_secret_access_key" in {
    val secretGen = Gen.listOfN(20, Gen.alphaNumChar).map(_.mkString)
    forAll(secretGen) { secret =>
      val input = s"aws_secret_access_key = $secret"
      CredentialRedactor.redact(input) should not include secret
    }
  }

  it should "redact URL-form X-Amz-Signature query parameter" in {
    val input =
      "s3://bucket/file?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE&X-Amz-Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d"
    val result = CredentialRedactor.redact(input)
    result should not include "5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d"
    result should include("X-Amz-Signature=[REDACTED]")
  }

  it should "redact Azure SAS token sig= query parameter" in {
    val input =
      "abfss://container@account.dfs.core.windows.net/path?sv=2021-06-08&sr=b&sig=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx&se=2023-01-01&sp=r"
    val result = CredentialRedactor.redact(input)
    result should not include "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    result should include("sig=[REDACTED]")
  }

  it should "redact PEM private key block content including END marker" in {
    val input =
      "Error parsing service account: -----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQ\n-----END PRIVATE KEY-----"
    val result = CredentialRedactor.redact(input)
    result should not include "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQ"
    result should include("-----BEGIN PRIVATE KEY-----")
    result should not include "-----END PRIVATE KEY-----"
    result should include("[REDACTED]")
  }

  // ── CliApp call-site shapes ───────────────────────────────────────────────

  it should "redact credentials in Hadoop S3A exception message (catch-all handler shape)" in {
    val exceptionMessage =
      "com.amazonaws.services.s3.model.AmazonS3Exception: " +
        "Status Code: 403, AWS Service: Amazon S3, AWS Request ID: 1234, " +
        "AWSAccessKeyId=AKIAIOSFODNN7EXAMPLE&Signature=wJalrXUtnFEMI%2FK7MDENG"
    val printed = s"Error: ${CredentialRedactor.redact(exceptionMessage)}"
    printed should not include "AKIAIOSFODNN7EXAMPLE"
    printed should not include "wJalrXUtnFEMI"
    printed should include("[REDACTED]")
  }

  it should "redact credentials embedded in IOError userMessage (reportError shape)" in {
    val causeMsg =
      "I/O error: Software caused connection abort — " +
        "Authorization: Bearer eyJsZWFrZWRUb2tlbn0secret"
    val printed = s"Failed to read: ${CredentialRedactor.redact(causeMsg)}"
    printed should not include "eyJsZWFrZWRUb2tlbn0secret"
    printed should include("[REDACTED]")
  }

  // ── redactThrowable: cause-chain walking ──────────────────────────────────

  "CredentialRedactor.redactThrowable" should "redact credentials in nested cause chain" in {
    val secret = "AKIAIOSFODNN7EXAMPLE"
    val inner = new RuntimeException(s"AWSAccessKeyId=$secret")
    val outer = new RuntimeException("outer error", inner)
    val redacted = CredentialRedactor.redactThrowable(outer)
    redacted should not include secret
    redacted should include("[REDACTED]")
  }

  it should "redact credentials in second-level cause" in {
    val secret = "AKIAIOSFODNN7EXAMPLE"
    val root = new RuntimeException(s"AWSAccessKeyId=$secret")
    val mid = new RuntimeException("mid", root)
    val top = new RuntimeException("top", mid)
    val redacted = CredentialRedactor.redactThrowable(top)
    redacted should not include secret
  }

  it should "include the top-level message when no cause exists" in {
    val secret = "AKIAIOSFODNN7EXAMPLE"
    val ex = new RuntimeException(s"AWSAccessKeyId=$secret")
    val redacted = CredentialRedactor.redactThrowable(ex)
    redacted should not include secret
    redacted should include("[REDACTED]")
  }

  it should "use class name when getMessage returns null" in {
    val ex = new RuntimeException(null: String)
    val redacted = CredentialRedactor.redactThrowable(ex)
    redacted should include("RuntimeException")
  }

  it should "include Caused by prefix for each nested cause" in {
    val inner = new RuntimeException("inner msg")
    val outer = new RuntimeException("outer msg", inner)
    val redacted = CredentialRedactor.redactThrowable(outer)
    redacted should include("outer msg")
    redacted should include("Caused by: ")
    redacted should include("inner msg")
  }

  it should "terminate on circular cause chain without infinite loop" in {
    val base = new RuntimeException("base: AWSAccessKeyId=AKIAIOSFODNN7EXAMPLE")
    val chain = (1 to 100).foldLeft(base: Throwable)((acc, _) =>
      new RuntimeException("mid", acc)
    )
    val result = CredentialRedactor.redactThrowable(chain)
    result should not include "AKIAIOSFODNN7EXAMPLE"
  }

  // ── M3: additional Hadoop cloud config secrets ────────────────────────────

  it should "redact Azure OAuth2 client secret Hadoop property value" in {
    val input =
      "fs.azure.account.oauth2.client.secret.myaccount.dfs.core.windows.net=supersecretvalue"
    val result = CredentialRedactor.redact(input)
    result should not include "supersecretvalue"
    result should include("[REDACTED]")
  }

  it should "redact S3A secret key Hadoop property value" in {
    val input = "fs.s3a.secret.key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    val result = CredentialRedactor.redact(input)
    result should not include "wJalrXUtnFEMI"
    result should include("[REDACTED]")
  }

  it should "redact S3A session token Hadoop property value" in {
    val input =
      "fs.s3a.session.token=FwoGZXIvYXdzEJr//////////wEaDFkjsecrettoken"
    val result = CredentialRedactor.redact(input)
    result should not include "FwoGZXIvYXdzEJr"
    result should include("[REDACTED]")
  }

  it should "redact bare Bearer JWT token in log lines" in {
    val input =
      "Sending request with Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig"
    val result = CredentialRedactor.redact(input)
    result should not include "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9"
    result should include("[REDACTED]")
  }
}
