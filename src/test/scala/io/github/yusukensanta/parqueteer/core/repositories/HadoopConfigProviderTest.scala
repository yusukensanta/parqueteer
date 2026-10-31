package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.cloud.AzureTokenException
import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.CloudAuthException
import org.apache.hadoop.conf.Configuration
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.AccessDeniedException
import scala.util.{Failure, Success}

class HadoopConfigProviderTest extends AnyFlatSpec with Matchers {

  private def provider() = new HadoopConfigProvider(None, None, None)

  "HadoopConfigProvider.withConfig" should "run the block with a Configuration for a local path" in {
    val result = provider().withConfig(LocalPath("/tmp/x.parquet"))(cfg => Success(cfg))
    result.map(_.getClass) shouldBe Success(classOf[Configuration])
  }

  it should "cache per location key, counting one miss then hits" in {
    val p = provider()
    p.withConfig(LocalPath("/tmp/a.parquet"))(_ => Success(()))
    p.withConfig(LocalPath("/tmp/b.parquet"))(_ => Success(()))
    (p.hits, p.misses) shouldBe (1L, 1L)
  }

  it should "hand out an independent copy so a caller's mutation does not leak into the cache" in {
    val p = provider()
    p.withConfig(LocalPath("/tmp/a.parquet"))(cfg => Success(cfg.set("parqueteer.test", "dirty")))
    val seen =
      p.withConfig(LocalPath("/tmp/a.parquet"))(cfg => Success(Option(cfg.get("parqueteer.test"))))
    seen shouldBe Success(None)
  }

  it should "leave an AccessDeniedException on a local path untouched" in {
    val denied = new AccessDeniedException("/tmp/x")
    val result = provider().withConfig(LocalPath("/tmp/x"))(_ => Failure(denied))
    result shouldBe Failure(denied)
  }

  it should "reclassify an AccessDeniedException on a cloud path as CloudAuthException" in {
    val result = provider().withConfig(S3Location("bucket", "key.parquet"))(_ =>
      Failure(new AccessDeniedException("s3a://bucket/key.parquet"))
    )
    result.failed.get shouldBe a[CloudAuthException]
    result.failed.get.asInstanceOf[CloudAuthException].provider shouldBe "S3"
  }

  it should "report a nested Azure token failure as a CloudAuthException with its own message" in {
    val tokenFailure =
      new AzureTokenException("Could not get an Azure access token. Run `az login`", null)
    val wrapped = new java.io.IOException(
      "Invalid configuration value detected for fs.azure.account.hns.enabled",
      new RuntimeException("abfs wrapper", tokenFailure)
    )
    val result =
      provider().withConfig(AzureLocation("acct", "ctr", "x.parquet"))(_ => Failure(wrapped))

    val err = result.failed.get.asInstanceOf[CloudAuthException]
    err.provider shouldBe "Azure"
    err.getMessage should include("az login")
    err.getMessage should not include "hns.enabled"
  }

  it should "recover the token failure ABFS flattened into an HttpException message" in {
    val flattened = new java.io.IOException(
      "HTTP Error -1CustomTokenProvider getAccessToken threw " +
        "io.github.yusukensanta.parqueteer.cloud.AzureTokenException : " +
        "Could not get an Azure access token: no session. Run `az login`."
    )
    val wrapped = new java.io.IOException(
      "Invalid configuration value detected for fs.azure.account.hns.enabled",
      new RuntimeException("Status code: -1", flattened)
    )
    val result =
      provider().withConfig(AzureLocation("acct", "ctr", "x.parquet"))(_ => Failure(wrapped))

    val err = result.failed.get.asInstanceOf[CloudAuthException]
    err.getMessage shouldBe "Could not get an Azure access token: no session. Run `az login`."
  }

  it should "leave an unrelated I/O error on an Azure path untouched" in {
    val other  = new java.io.IOException("connection reset")
    val result =
      provider().withConfig(AzureLocation("acct", "ctr", "x.parquet"))(_ => Failure(other))
    result shouldBe Failure(other)
  }

  it should "not loop forever on a cyclic cause chain" in {
    val a = new RuntimeException("a")
    val b = new RuntimeException("b", a)
    a.initCause(b)
    val result = provider().withConfig(AzureLocation("acct", "ctr", "x.parquet"))(_ => Failure(a))
    result shouldBe Failure(a)
  }
}
