package io.github.yusukensanta.parqueteer.cloud

import io.github.yusukensanta.parqueteer.core.models.{
  AzureLocation,
  GCSLocation,
  LocalPath,
  S3Location,
  StorageLocation
}
import org.apache.hadoop.conf.Configuration
import scala.util.{Failure, Success, Try}

trait CloudCredentialManager {
  def configureHadoop(location: StorageLocation): Try[Configuration]
}

object CloudCredentialManager {

  def forLocation(
      location: StorageLocation,
      profile: Option[String] = None
  ): Option[CloudCredentialManager] =
    location match {
      case _: S3Location    => Some(new S3CredentialManager(profile))
      case _: GCSLocation   => Some(new GCSCredentialManager)
      case _: AzureLocation => Some(new AzureCredentialManager)
      case _: LocalPath     => None
    }

  /**
   * Try each credential strategy in order; return the first success, or a
   * single `RuntimeException` aggregating every failure message under the
   * given header. Used by both S3 and GCS credential managers — keeps the
   * "attempted strategies" error text consistent across providers.
   */
  private[cloud] def firstSuccess[A](
      header: String,
      strategies: List[() => Try[A]]
  ): Try[A] = {
    val failures             = scala.collection.mutable.ListBuffer.empty[String]
    var lastCause: Throwable = null
    val it                   = strategies.iterator
    while it.hasNext do
      it.next()() match {
        case s @ Success(_) => return s
        case Failure(err) =>
          failures += Option(err.getMessage).getOrElse(err.getClass.getName)
          lastCause = err
      }
    Failure(
      new RuntimeException(
        s"$header\n${failures.mkString("\n")}",
        lastCause
      )
    )
  }
}
