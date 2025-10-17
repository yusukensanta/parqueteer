package io.parqueteer.cloud

import io.parqueteer.core.models.{StorageLocation, S3Location}
import org.apache.hadoop.conf.Configuration
import software.amazon.awssdk.auth.credentials.{
  DefaultCredentialsProvider,
  ProfileCredentialsProvider,
  InstanceProfileCredentialsProvider
}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sts.StsClient
import scala.util.{Try, Success, Failure}

class S3CredentialManager extends CloudCredentialManager {
  override def supportsLocation(location: StorageLocation): Boolean = {
    location.isInstanceOf[S3Location]
  }

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

          val credentials = resolveCredentials(s3Location)

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
              conf.set("fs.s3a.multipart.size", "100MB")
              conf.set("fs.s3a.multipart.threshold", "100MB")

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

  override def validateCredentials(location: StorageLocation): Try[Unit] = {
    location match {
      case s3Location: S3Location =>
        Try {
          val region = Region.of(s3Location.region.getOrElse("us-east-1"))
          val stsClient = StsClient
            .builder()
            .region(region)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build()
          val identity = stsClient.getCallerIdentity()
          println(
            s"S3 credentials validated for account: ${identity.account()}"
          )
        }
      case _ =>
        Failure(new IllegalArgumentException("Expected S3Location"))
    }
  }

  private def resolveCredentials(
      s3Location: S3Location
  ): Try[(String, String, Option[String])] = {
    val _ = s3Location // suppress unused warning
    val strategies = List(
      () => tryEnvironmentVariables(),
      () => tryDefaultCredentialsProvider(),
      () => tryInstanceProfile(),
      () => tryProfile()
    )

    strategies.foldLeft(
      Failure(new RuntimeException("No credentials found")): Try[
        (String, String, Option[String])
      ]
    ) {
      case (Success(creds), _)    => Success(creds)
      case (Failure(_), strategy) => strategy()
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
        case sessionCreds
            if sessionCreds.getClass.getSimpleName.contains("Session") =>
          try {
            val sessionTokenMethod =
              sessionCreds.getClass.getMethod("sessionToken")
            Some(sessionTokenMethod.invoke(sessionCreds).asInstanceOf[String])
          } catch {
            case _: Exception => None
          }
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

  private def tryProfile(): Try[(String, String, Option[String])] = {
    Try {
      val profileName = sys.env.get("AWS_PROFILE").getOrElse("default")
      val provider = ProfileCredentialsProvider.create(profileName)
      val credentials = provider.resolveCredentials()
      (credentials.accessKeyId(), credentials.secretAccessKey(), None)
    }
  }
}
