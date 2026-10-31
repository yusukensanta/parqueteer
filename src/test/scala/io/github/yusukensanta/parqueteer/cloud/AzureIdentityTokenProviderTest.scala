package io.github.yusukensanta.parqueteer.cloud

import com.azure.core.credential.{AccessToken, TokenCredential, TokenRequestContext}
import com.azure.identity.CredentialUnavailableException
import org.apache.hadoop.conf.Configuration
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import reactor.core.publisher.Mono

import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference

class AzureIdentityTokenProviderTest extends AnyFlatSpec with Matchers {

  private val Account = "acct.dfs.core.windows.net"

  private class StubCredential(result: Mono[AccessToken]) extends TokenCredential {
    val lastRequest = new AtomicReference[TokenRequestContext]()

    override def getToken(request: TokenRequestContext): Mono[AccessToken] = {
      lastRequest.set(request)
      result
    }
  }

  private def confFor(kind: String): Configuration = {
    val conf = new Configuration(false)
    conf.set(AzureIdentityTokenProvider.CredentialKey, kind)
    conf
  }

  "AzureIdentityTokenProvider" should "return the token and expose its expiry" in {
    val expires = OffsetDateTime.now().plusHours(1)
    val stub    = new StubCredential(Mono.just(new AccessToken("tok-123", expires)))
    val p       = new AzureIdentityTokenProvider
    p.useCredential(stub)

    p.getAccessToken() shouldBe "tok-123"
    p.getExpiryTime().getTime shouldBe expires.toInstant.toEpochMilli
  }

  it should "request the Azure Storage scope" in {
    val stub =
      new StubCredential(Mono.just(new AccessToken("t", OffsetDateTime.now().plusHours(1))))
    val p = new AzureIdentityTokenProvider
    p.useCredential(stub)
    p.getAccessToken()

    stub.lastRequest.get().getScopes should contain only "https://storage.azure.com/.default"
  }

  it should "report an expired time before any token was fetched" in {
    val p = new AzureIdentityTokenProvider
    p.getExpiryTime().getTime shouldBe 0L
  }

  it should "fail with a hint to run az login when no credential is available" in {
    val stub = new StubCredential(
      Mono.error(new CredentialUnavailableException("Azure CLI not installed"))
    )
    val p = new AzureIdentityTokenProvider
    p.useCredential(stub)

    val ex = intercept[IOException](p.getAccessToken())
    ex.getMessage should include("az login")
    ex.getMessage should include("Azure CLI not installed")
    ex.getCause shouldBe a[RuntimeException]
  }

  it should "put the hint first and the per-credential detail after a separator" in {
    val stub = new StubCredential(
      Mono.error(new CredentialUnavailableException("Azure CLI not installed"))
    )
    val p = new AzureIdentityTokenProvider
    p.useCredential(stub)

    val ex                = intercept[AzureTokenException](p.getAccessToken())
    val (summary, detail) = AzureTokenException.split(ex.getMessage)
    summary should include("az login")
    summary should not include "Azure CLI not installed"
    detail shouldBe Some("Azure CLI not installed")
  }

  it should "redact secrets from the credential error message" in {
    val stub = new StubCredential(
      Mono.error(
        new CredentialUnavailableException(
          "failed https://x.blob.core.windows.net/c?sig=SECRETSIG123&sv=2023"
        )
      )
    )
    val p = new AzureIdentityTokenProvider
    p.useCredential(stub)

    val ex = intercept[IOException](p.getAccessToken())
    ex.getMessage should not include "SECRETSIG123"
  }

  "AzureTokenException.split" should "return the whole text and no detail when there is no separator" in {
    AzureTokenException.split("plain message") shouldBe ("plain message", None)
  }

  it should "split at the first separator only" in {
    val msg =
      s"head${AzureTokenException.DetailSeparator}one${AzureTokenException.DetailSeparator}two"
    AzureTokenException.split(msg) shouldBe (
      "head",
      Some(s"one${AzureTokenException.DetailSeparator}two")
    )
  }

  "AzureIdentityTokenProvider" should "fail clearly when used before initialize" in {
    val ex = intercept[IOException](new AzureIdentityTokenProvider().getAccessToken())
    ex.getMessage should include("not initialized")
  }

  it should "build the default credential chain on initialize" in {
    val p = new AzureIdentityTokenProvider
    noException should be thrownBy p.initialize(
      confFor(AzureIdentityTokenProvider.KindDefault),
      Account
    )
  }

  it should "build the Azure CLI credential on initialize" in {
    val p = new AzureIdentityTokenProvider
    noException should be thrownBy p.initialize(
      confFor(AzureIdentityTokenProvider.KindAzureCli),
      Account
    )
  }

  it should "default to the credential chain when the kind is not configured" in {
    val p = new AzureIdentityTokenProvider
    noException should be thrownBy p.initialize(new Configuration(false), Account)
  }

  it should "reject an unknown credential kind" in {
    val p  = new AzureIdentityTokenProvider
    val ex = intercept[IOException](p.initialize(confFor("kerberos"), Account))
    ex.getMessage should include("kerberos")
  }

  it should "have a public no-arg constructor so the ABFS driver can instantiate it" in {
    val ctor = classOf[AzureIdentityTokenProvider].getConstructor()
    ctor.newInstance() shouldBe a[AzureIdentityTokenProvider]
  }
}
