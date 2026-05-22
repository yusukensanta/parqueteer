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
}
