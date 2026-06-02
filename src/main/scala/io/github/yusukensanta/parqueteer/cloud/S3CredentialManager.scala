package io.github.yusukensanta.parqueteer.cloud

import io.github.yusukensanta.parqueteer.core.models.{
  StorageLocation,
  S3Location
}
import org.apache.hadoop.conf.Configuration
import software.amazon.awssdk.auth.credentials.{
  AwsSessionCredentials,
  DefaultCredentialsProvider,
  ProfileCredentialsProvider,
  InstanceProfileCredentialsProvider
}
import scala.util.{Try, Success, Failure}

private object S3Tuning {
  val MaxConnections = "100"
  val MaxAttempts = "3"
  val ThrottleRetryLimit = "20"
  val ThrottleRetryInterval = "50ms"
  val MultipartSize = "100m"
  val MultipartThreshold = "100m"
}

class S3CredentialManager(profile: Option[String] = None)
    extends CloudCredentialManager {
  override def configureHadoop(
      location: StorageLocation
  ): Try[Configuration] = {
    location match {
      case s3Location: S3Location =>
        Try {
          val conf = new Configuration()

          conf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
          conf.set(
            "fs.AbstractFileSystem.s3a.impl",
            "org.apache.hadoop.fs.s3a.S3A"
          )

          val credentials = resolveCredentials()

          credentials match {
            case Success((accessKey, secretKey, sessionToken)) =>
              conf.set("fs.s3a.access.key", accessKey)
              conf.set("fs.s3a.secret.key", secretKey)
              sessionToken.foreach(token =>
                conf.set("fs.s3a.session.token", token)
              )

              s3Location.region.foreach(region => {
                conf.set("fs.s3a.endpoint.region", region)
              })

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

              // Support custom S3-compatible endpoints (RustFS, LocalStack, etc.)
              // AWS_ENDPOINT_URL is the standard override used by AWS CLI v2 and SDKs
              sys.env.get("AWS_ENDPOINT_URL").foreach { rawEndpoint =>
                val endpoint = if (!rawEndpoint.contains("://")) {
                  Console.err.println(
                    s"[parqueteer] warning: AWS_ENDPOINT_URL='$rawEndpoint' has no scheme; assuming http:// — set explicitly (e.g. http://$rawEndpoint) to suppress this warning"
                  )
                  s"http://$rawEndpoint"
                } else rawEndpoint
                conf.set("fs.s3a.endpoint", endpoint)
                conf.set("fs.s3a.path.style.access", "true")
                if (endpointDisablesSsl(endpoint))
                  conf.set("fs.s3a.connection.ssl.enabled", "false")
              }

              conf
            case Failure(error) =>
              throw new RuntimeException(
                s"Failed to resolve S3 credentials: ${error.getMessage}",
                error
              )
          }
        }
      case _ =>
        Failure(new IllegalArgumentException("Expected S3Location"))
    }
  }

  private[cloud] def endpointDisablesSsl(endpoint: String): Boolean =
    endpoint.toLowerCase(java.util.Locale.ROOT).startsWith("http://")

  private def resolveCredentials(): Try[(String, String, Option[String])] = {
    val strategies: List[() => Try[(String, String, Option[String])]] =
      profile match {
        case Some(p) => List(() => tryProfile(Some(p)))
        case None =>
          List(
            () => tryEnvironmentVariables(),
            () => tryDefaultCredentialsProvider(),
            () => tryInstanceProfile(),
            () => tryProfile(None)
          )
      }
    CloudCredentialManager.firstSuccess(
      "No S3 credentials found. Attempted strategies:",
      strategies
    )
  }

  private def tryEnvironmentVariables()
      : Try[(String, String, Option[String])] = {
    Try {
      val accessKey = sys.env
        .get("AWS_ACCESS_KEY_ID")
        .orElse(sys.env.get("AWS_ACCESS_KEY"))
        .getOrElse(
          throw new RuntimeException(
            "AWS_ACCESS_KEY_ID not found in environment"
          )
        )

      val secretKey = sys.env
        .get("AWS_SECRET_ACCESS_KEY")
        .orElse(sys.env.get("AWS_SECRET_KEY"))
        .getOrElse(
          throw new RuntimeException(
            "AWS_SECRET_ACCESS_KEY not found in environment"
          )
        )

      val sessionToken = sys.env
        .get("AWS_SESSION_TOKEN")
        .orElse(sys.env.get("AWS_SECURITY_TOKEN"))

      (accessKey, secretKey, sessionToken)
    }
  }

  private def tryDefaultCredentialsProvider()
      : Try[(String, String, Option[String])] = {
    Try {
      val provider = DefaultCredentialsProvider.create()
      val credentials = provider.resolveCredentials()

      val sessionToken = credentials match {
        case sessionCreds: AwsSessionCredentials =>
          Some(sessionCreds.sessionToken())
        case _ => None
      }

      (credentials.accessKeyId(), credentials.secretAccessKey(), sessionToken)
    }
  }

  private[cloud] def tryInstanceProfile()
      : Try[(String, String, Option[String])] = {
    Try {
      val provider = InstanceProfileCredentialsProvider.create()
      val credentials = provider.resolveCredentials()
      val sessionToken = credentials match {
        case sessionCreds: AwsSessionCredentials =>
          Some(sessionCreds.sessionToken())
        case _ => None
      }
      (credentials.accessKeyId(), credentials.secretAccessKey(), sessionToken)
    }
  }

  private def tryProfile(
      explicitProfile: Option[String]
  ): Try[(String, String, Option[String])] = {
    Try {
      val profileName = explicitProfile
        .orElse(sys.env.get("AWS_PROFILE"))
        .getOrElse("default")
      val provider = ProfileCredentialsProvider.create(profileName)
      val credentials = provider.resolveCredentials()
      val sessionToken = credentials match {
        case s: AwsSessionCredentials => Some(s.sessionToken())
        case _                        => None
      }
      (credentials.accessKeyId(), credentials.secretAccessKey(), sessionToken)
    }
  }
}
