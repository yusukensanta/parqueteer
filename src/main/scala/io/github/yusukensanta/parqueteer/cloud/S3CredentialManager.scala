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

              conf.set("fs.s3a.connection.maximum", "100")
              conf.set("fs.s3a.attempts.maximum", "3")
              conf.set("fs.s3a.retry.throttle.limit", "20")
              conf.set("fs.s3a.retry.throttle.interval", "50ms")

              conf.set("fs.s3a.buffer.dir", "/tmp")
              conf.set("fs.s3a.fast.upload", "true")
              conf.set("fs.s3a.fast.upload.buffer", "disk")
              conf.set("fs.s3a.multipart.size", "100m")
              conf.set("fs.s3a.multipart.threshold", "100m")

              // Support custom S3-compatible endpoints (RustFS, LocalStack, etc.)
              // AWS_ENDPOINT_URL is the standard override used by AWS CLI v2 and SDKs
              sys.env.get("AWS_ENDPOINT_URL").foreach { endpoint =>
                conf.set("fs.s3a.endpoint", endpoint)
                conf.set("fs.s3a.path.style.access", "true")
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

  private def resolveCredentials(): Try[(String, String, Option[String])] = {
    val strategies = profile match {
      case Some(p) =>
        List(() => tryProfile(Some(p)))
      case None =>
        List(
          () => tryEnvironmentVariables(),
          () => tryDefaultCredentialsProvider(),
          () => tryInstanceProfile(),
          () => tryProfile(None)
        )
    }

    val (result, failures) = strategies.foldLeft(
      (
        Failure(new RuntimeException("No credentials found")): Try[
          (String, String, Option[String])
        ],
        List.empty[String]
      )
    ) {
      case ((Success(creds), msgs), _) => (Success(creds), msgs)
      case ((Failure(_), msgs), strategy) =>
        strategy() match {
          case s @ Success(_) => (s, msgs)
          case Failure(err)   => (Failure(err), msgs :+ err.getMessage)
        }
    }
    result match {
      case s @ Success(_) => s
      case Failure(_) =>
        Failure(
          new RuntimeException(
            "No S3 credentials found. Attempted strategies:\n" + failures
              .mkString("\n")
          )
        )
    }
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

  private def tryInstanceProfile(): Try[(String, String, Option[String])] = {
    Try {
      val provider = InstanceProfileCredentialsProvider.create()
      val credentials = provider.resolveCredentials()
      (credentials.accessKeyId(), credentials.secretAccessKey(), None)
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
      (credentials.accessKeyId(), credentials.secretAccessKey(), None)
    }
  }
}
