package io.github.yusukensanta.parqueteer.cli

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CredentialRedactorTest extends AnyFlatSpec with Matchers {

  "CredentialRedactor.redact" should "redact Authorization header value" in {
    val input = "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.secret"
    CredentialRedactor.redact(input) should include("Authorization: [REDACTED]")
    CredentialRedactor.redact(input) should not include "eyJhbGciOiJSUzI1NiJ9"
  }

  it should "redact X-Amz-* header values" in {
    val input = "X-Amz-Security-Token: IQoJb3JpZ2luXsecrettoken"
    val result = CredentialRedactor.redact(input)
    result should include("X-Amz-Security-Token: [REDACTED]")
    result should not include "IQoJb3JpZ2luXsecrettoken"
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

  it should "leave non-credential strings unchanged" in {
    val input = "Error connecting to s3://my-bucket/key"
    CredentialRedactor.redact(input) shouldBe input
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

  it should "redact PEM private key block content" in {
    val input =
      "Error parsing service account: -----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQ\n-----END PRIVATE KEY-----"
    val result = CredentialRedactor.redact(input)
    result should not include "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQ"
    result should include("-----BEGIN PRIVATE KEY-----")
    result should include("[REDACTED]")
  }
}
