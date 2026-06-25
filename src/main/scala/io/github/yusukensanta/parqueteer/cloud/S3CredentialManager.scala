package io.github.yusukensanta.parqueteer.cloud

import io.github.yusukensanta.parqueteer.core.models.{S3Location, StorageLocation}
import org.apache.hadoop.conf.Configuration
import software.amazon.awssdk.auth.credentials.{
  AwsSessionCredentials,
  DefaultCredentialsProvider,
  InstanceProfileCredentialsProvider,
  ProfileCredentialsProvider
}
import scala.util.{Failure, Try, Using}

private object S3Tuning {
  val MaxConnections        = "100"
  val MaxAttempts           = "3"
  val ThrottleRetryLimit    = "20"
  val ThrottleRetryInterval = "50ms"
  val MultipartSize         = "100m"
  val MultipartThreshold    = "100m"
}

// Process-wide singletons so background IMDS refresh threads are shared and
// closed exactly once on JVM exit (same pattern as Hadoop FileSystem.closeAll).
private object S3CredentialProviders {

  @volatile private var defaultInitialized: Boolean         = false
  @volatile private var instanceProfileInitialized: Boolean = false

  lazy val default: DefaultCredentialsProvider = {
    val p = DefaultCredentialsProvider.create()
    defaultInitialized = true
    p
  }

  lazy val instanceProfile: InstanceProfileCredentialsProvider = {
    val p = InstanceProfileCredentialsProvider.create()
    instanceProfileInitialized = true
    p
  }

  Runtime.getRuntime.addShutdownHook(new Thread(() => {
    if defaultInitialized then
      try default.close()
      catch { case _: Exception => () }
    if instanceProfileInitialized then
      try instanceProfile.close()
      catch { case _: Exception => () }
  }))
}

class S3CredentialManager(profile: Option[String] = None) extends CloudCredentialManager {

  override def configureHadoop(
      location: StorageLocation
  ): Try[Configuration] =
    location match {
      case s3Location: S3Location =>
        resolveCredentials().map { case (accessKey, secretKey, sessionToken) =>
          val conf = new Configuration()

          conf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
          conf.set(
            "fs.AbstractFileSystem.s3a.impl",
            "org.apache.hadoop.fs.s3a.S3A"
          )

          conf.set("fs.s3a.access.key", accessKey)
          conf.set("fs.s3a.secret.key", secretKey)
          sessionToken.foreach(token => conf.set("fs.s3a.session.token", token))

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

          sys.env.get("AWS_ENDPOINT_URL").foreach { rawEndpoint =>
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

  private def tryEnvironmentVariables(): Try[(String, String, Option[String])] =
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

  private def tryDefaultCredentialsProvider(): Try[(String, String, Option[String])] =
    Try {
      val credentials = S3CredentialProviders.default.resolveCredentials()
      val sessionToken = credentials match {
        case sessionCreds: AwsSessionCredentials =>
          Some(sessionCreds.sessionToken())
        case _ => None
      }
      (credentials.accessKeyId(), credentials.secretAccessKey(), sessionToken)
    }

  private[cloud] def tryInstanceProfile(): Try[(String, String, Option[String])] =
    Try {
      val credentials =
        S3CredentialProviders.instanceProfile.resolveCredentials()
      val sessionToken = credentials match {
        case sessionCreds: AwsSessionCredentials =>
          Some(sessionCreds.sessionToken())
        case _ => None
      }
      (credentials.accessKeyId(), credentials.secretAccessKey(), sessionToken)
    }

  private def tryProfile(
      explicitProfile: Option[String]
  ): Try[(String, String, Option[String])] = {
    val profileName = explicitProfile
      .orElse(sys.env.get("AWS_PROFILE"))
      .getOrElse("default")
    Try {
      Using.resource(ProfileCredentialsProvider.create(profileName)) { provider =>
        val credentials = provider.resolveCredentials()
        val sessionToken = credentials match {
          case s: AwsSessionCredentials => Some(s.sessionToken())
          case _                        => None
        }
        (
          credentials.accessKeyId(),
          credentials.secretAccessKey(),
          sessionToken
        )
      }
    }
  }
}
