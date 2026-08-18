package io.github.yusukensanta.parqueteer.cloud

import io.github.yusukensanta.parqueteer.core.models.{S3Location, StorageLocation}
import org.apache.hadoop.conf.Configuration
import scala.util.{Failure, Try}

private[cloud] object S3Tuning {
  val MaxConnections        = "100"
  val MaxAttempts           = "3"
  val ThrottleRetryLimit    = "20"
  val ThrottleRetryInterval = "50ms"
  val MultipartSize         = "100m"
  val MultipartThreshold    = "100m"
}

// AWS SDK v2 credential provider class names, tried by S3A in this order until
// one resolves. S3A instantiates and calls each *itself*, per S3 request, so
// short-lived session/IMDS tokens refresh automatically instead of being
// resolved once here and baked into a Configuration for the process lifetime.
private[cloud] object S3ProviderChain {
  val Simple      = "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider"
  val EnvVar      = "software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider"
  val Profile     = "software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider"
  val IamInstance = "org.apache.hadoop.fs.s3a.auth.IAMInstanceCredentialsProvider"

  // Default chain when no explicit --profile is given: static config (never
  // populated by this app, but honors any Configuration a caller pre-seeds),
  // then env vars, then a named profile via AWS_PROFILE/aws.profile, then
  // EC2/ECS instance credentials.
  val default: String = List(Simple, EnvVar, Profile, IamInstance).mkString(",")
}

class S3CredentialManager(
    profile: Option[String] = None,
    endpointOverride: Option[String] = None
) extends CloudCredentialManager {

  protected[cloud] def env(key: String): Option[String] = key match {
    case "AWS_ENDPOINT_URL" => endpointOverride.orElse(sys.env.get(key))
    case _                  => sys.env.get(key)
  }

  override def configureHadoop(
      location: StorageLocation
  ): Try[Configuration] =
    location match {
      case s3Location: S3Location =>
        Try {
          val conf = new Configuration()

          conf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
          conf.set(
            "fs.AbstractFileSystem.s3a.impl",
            "org.apache.hadoop.fs.s3a.S3A"
          )

          // --profile pins S3A to just the profile provider. AWS SDK v2's
          // ProfileCredentialsProvider takes no config of its own — it reads
          // the "aws.profile" system property (falling back to AWS_PROFILE,
          // then "default"), so an explicit --profile is passed through that
          // way. Safe here because parqueteer is a single-command-per-process
          // CLI, not a long-running multi-tenant service.
          profile match {
            case Some(p) =>
              System.setProperty("aws.profile", p)
              conf.set("fs.s3a.aws.credentials.provider", S3ProviderChain.Profile)
            case None =>
              conf.set("fs.s3a.aws.credentials.provider", S3ProviderChain.default)
          }

          s3Location.region.foreach(region => conf.set("fs.s3a.endpoint.region", region))

          conf.set("fs.s3a.connection.maximum", S3Tuning.MaxConnections)
          conf.set("fs.s3a.attempts.maximum", S3Tuning.MaxAttempts)
          conf.set(
            "fs.s3a.retry.throttle.limit",
            S3Tuning.ThrottleRetryLimit
          )
          conf.set(
            "fs.s3a.retry.throttle.interval",
            S3Tuning.ThrottleRetryInterval
          )

          conf.set("fs.s3a.buffer.dir", sys.props("java.io.tmpdir"))
          conf.set("fs.s3a.fast.upload", "true")
          conf.set("fs.s3a.fast.upload.buffer", "disk")
          conf.set("fs.s3a.multipart.size", S3Tuning.MultipartSize)
          conf.set(
            "fs.s3a.multipart.threshold",
            S3Tuning.MultipartThreshold
          )

          env("AWS_ENDPOINT_URL").foreach { rawEndpoint =>
            val endpoint = if !rawEndpoint.contains("://") then {
              io.github.yusukensanta.parqueteer.core.util.Warnings.emit(
                "AWS_ENDPOINT_URL has no scheme; assuming https:// — prepend http:// or https:// to suppress this warning"
              )
              s"https://$rawEndpoint"
            } else rawEndpoint
            conf.set("fs.s3a.endpoint", endpoint)
            conf.set("fs.s3a.path.style.access", "true")
            if endpointDisablesSsl(endpoint) then conf.set("fs.s3a.connection.ssl.enabled", "false")
          }

          conf
        }
      case _ =>
        Failure(new IllegalArgumentException("Expected S3Location"))
    }

  private[cloud] def endpointDisablesSsl(endpoint: String): Boolean =
    endpoint.toLowerCase(java.util.Locale.ROOT).startsWith("http://")
}
