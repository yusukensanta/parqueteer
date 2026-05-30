package io.github.yusukensanta.parqueteer.cloud

import io.github.yusukensanta.parqueteer.core.models.StorageLocation
import org.apache.hadoop.conf.Configuration
import scala.util.{Failure, Success, Try}

trait CloudCredentialManager {
  def configureHadoop(location: StorageLocation): Try[Configuration]
}

object CloudCredentialManager {

  def forLocation(
      location: StorageLocation,
      profile: Option[String] = None
  ): Option[CloudCredentialManager] = {
    location match {
      case _: io.github.yusukensanta.parqueteer.core.models.S3Location =>
        Some(new S3CredentialManager(profile))
      case _: io.github.yusukensanta.parqueteer.core.models.GCSLocation =>
        Some(new GCSCredentialManager)
      case _: io.github.yusukensanta.parqueteer.core.models.AzureLocation =>
        Some(new AzureCredentialManager)
      case _: io.github.yusukensanta.parqueteer.core.models.LocalPath => None
    }
  }

  /** Try each credential strategy in order; return the first success, or a
    * single `RuntimeException` aggregating every failure message under the
    * given header. Used by both S3 and GCS credential managers — keeps the
    * "attempted strategies" error text consistent across providers.
    */
  private[cloud] def firstSuccess[A](
      header: String,
      strategies: List[() => Try[A]]
  ): Try[A] = {
    val results = strategies.iterator.map(f => f()).toList
    results.collectFirst { case s @ Success(_) => s } match {
      case Some(s) => s
      case None =>
        val failures = results.collect { case Failure(err) => err.getMessage }
        Failure(new RuntimeException(s"$header\n${failures.mkString("\n")}"))
    }
  }
}
