package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.cloud.{AzureTokenException, CloudCredentialManager}
import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.CloudAuthException
import io.github.yusukensanta.parqueteer.core.util.CredentialRedactor
import org.apache.hadoop.conf.Configuration
import java.nio.file.AccessDeniedException
import scala.util.{Failure, Success, Try}

/**
 * Builds and caches the Hadoop Configuration for a storage location.
 *
 * The cache is keyed on storage location type + bucket/region. For S3, the
 * cached Configuration holds a credentials-*provider* class name
 * (fs.s3a.aws.credentials.provider), not resolved key/secret/session-token
 * values — S3A instantiates and re-invokes that provider per request, so env
 * var rotation and short-lived session/IMDS token refresh both work correctly
 * without needing a new instance mid-process.
 */
private[repositories] class HadoopConfigProvider(
    profile: Option[String],
    region: Option[String],
    s3EndpointUrl: Option[String]
) {

  private val CacheMaxSize = 64

  private val cache = new LruCache[String, Configuration](CacheMaxSize)

  def hits: Long   = cache.hits
  def misses: Long = cache.misses

  /**
   * Runs `f` with the Configuration for `location`. Credential *resolution* is
   * not eager (S3A resolves/refreshes its own provider chain per request — see
   * S3CredentialManager), so an invalid or unauthorized identity only surfaces
   * once Hadoop actually attempts a cloud operation, as
   * java.nio.file.AccessDeniedException. It is reclassified as
   * CloudAuthException here so the CLI still reports "cloud authentication
   * failed" instead of a raw I/O error — scoped to cloud locations only, so a
   * real local-filesystem permission error isn't mislabeled.
   */
  def withConfig[A](location: StorageLocation)(f: Configuration => Try[A]): Try[A] =
    configFor(location).flatMap(f).recoverWith {
      case e: AccessDeniedException if isCloudLocation(location) =>
        Failure(
          new CloudAuthException(
            providerNameFor(location),
            CredentialRedactor.redact(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)),
            e
          )
        )
      case e if isCloudLocation(location) && azureTokenFailureMessage(e).isDefined =>
        val (summary, detail) = AzureTokenException.split(azureTokenFailureMessage(e).get)
        Failure(new CloudAuthException(providerNameFor(location), summary, e, detail))
    }

  private val MaxCauseDepth = 10

  // ABFS does not keep the token provider's exception: it flattens it into the message of an
  // HttpException ("... getAccessToken threw <class> : <message>") under an unrelated type.
  // Recover the real reason from either the typed cause or that flattened text.
  private val FlattenedTokenFailure = s"${classOf[AzureTokenException].getName} : "

  private def azureTokenFailureMessage(e: Throwable): Option[String] =
    Iterator
      .iterate(e)(_.getCause)
      .takeWhile(_ != null)
      .take(MaxCauseDepth)
      .flatMap {
        case t: AzureTokenException => Option(t.getMessage)
        case t                      =>
          Option(t.getMessage)
            .map(m => (m, m.indexOf(FlattenedTokenFailure)))
            .collect { case (m, i) if i >= 0 => m.substring(i + FlattenedTokenFailure.length) }
      }
      .nextOption()

  private def configFor(location: StorageLocation): Try[Configuration] = {
    val key = cacheKey(location)
    cache.get(key) match {
      case Some(cfg) =>
        Success(new Configuration(cfg))
      case None =>
        val effectiveLocation = (location, region) match {
          case (s3: S3Location, Some(r)) => s3.copy(region = Some(r))
          case _                         => location
        }
        val result = CloudCredentialManager
          .forLocation(effectiveLocation, profile, s3EndpointUrl) match {
          case Some(credManager) =>
            credManager.configureHadoop(effectiveLocation).recoverWith {
              case e if !e.isInstanceOf[CloudAuthException] =>
                Failure(
                  new CloudAuthException(
                    providerNameFor(effectiveLocation),
                    CredentialRedactor
                      .redact(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)),
                    e
                  )
                )
            }
          case None => Success(new Configuration())
        }
        // INVARIANT: cached Configuration must never be mutated after this put.
        // Callers always get a shallow copy (new Configuration(cfg)); mutating the
        // cached original while another thread copies it is not thread-safe.
        result.foreach(cfg => cache.put(key, cfg))
        result.map(new Configuration(_))
    }
  }

  private def cacheKey(location: StorageLocation): String =
    location match {
      case LocalPath(_)             => "local"
      case S3Location(bucket, _, r) =>
        s"s3:$bucket:${r.orElse(region).getOrElse("")}"
      case GCSLocation(bucket, _) =>
        s"gcs:$bucket"
      case AzureLocation(account, cont, _) =>
        s"azure:${profile.getOrElse("")}:$account/$cont"
    }

  private def providerNameFor(location: StorageLocation): String = location match {
    case _: S3Location    => "S3"
    case _: GCSLocation   => "GCS"
    case _: AzureLocation => "Azure"
    case _                => "cloud storage"
  }

  private def isCloudLocation(location: StorageLocation): Boolean = location match {
    case _: S3Location | _: GCSLocation | _: AzureLocation => true
    case _                                                 => false
  }
}
