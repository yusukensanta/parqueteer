package io.github.yusukensanta.parqueteer.cloud

import io.github.yusukensanta.parqueteer.core.models.{
  StorageLocation,
  GCSLocation
}
import org.apache.hadoop.conf.Configuration
import org.slf4j.LoggerFactory
import com.google.auth.oauth2.ServiceAccountCredentials
import scala.util.{Try, Success, Failure, Using}
import java.io.FileInputStream
import java.nio.file.{Files, Paths}

class GCSCredentialManager extends CloudCredentialManager {
  private val logger = LoggerFactory.getLogger(getClass)
  override def configureHadoop(
      location: StorageLocation
  ): Try[Configuration] = {
    location match {
      case _: GCSLocation =>
        Try {
          val conf = new Configuration()

          // GCS connector configuration
          conf.set(
            "fs.gs.impl",
            "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem"
          )
          conf.set(
            "fs.AbstractFileSystem.gs.impl",
            "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFS"
          )

          // Authentication configuration
          resolveCredentials() match {
            case Success(credentialsPath) =>
              conf.set("google.cloud.auth.service.account.enable", "true")
              conf.set(
                "google.cloud.auth.service.account.json.keyfile",
                credentialsPath
              )

            case Failure(error) =>
              conf.set("google.cloud.auth.service.account.enable", "false")
              logger.warn(
                "Using application default credentials: {}",
                error.getMessage
              )
          }

          // Project ID configuration
          sys.env
            .get("GCP_PROJECT_ID")
            .orElse(sys.env.get("GOOGLE_CLOUD_PROJECT"))
            .foreach { projectId =>
              conf.set("fs.gs.project.id", projectId)
            }

          // Performance settings
          conf.set("fs.gs.block.size", "67108864") // 64MB
          conf.set("fs.gs.inputstream.buffer.size", "8388608") // 8MB
          conf.set("fs.gs.outputstream.buffer.size", "8388608") // 8MB
          conf.set("fs.gs.outputstream.upload.chunk.size", "67108864") // 64MB

          // Retry settings
          conf.set("fs.gs.max.requests.per.batch", "30")
          conf.set("fs.gs.batch.threads", "15")

          conf
        }
      case _ =>
        Failure(new IllegalArgumentException("Expected GCSLocation"))
    }
  }

  private def resolveCredentials(): Try[String] = {
    val strategies = List(
      () => tryServiceAccountFile(),
      () => tryEnvironmentVariable(),
      () => tryWellKnownLocation()
    )

    strategies.foldLeft(
      Failure(new RuntimeException("No GCS credentials found")): Try[String]
    ) {
      case (Success(path), _)     => Success(path)
      case (Failure(_), strategy) => strategy()
    }
  }

  private def tryServiceAccountFile(): Try[String] = {
    Try {
      val credPath = sys.env
        .get("GOOGLE_APPLICATION_CREDENTIALS")
        .getOrElse(
          throw new RuntimeException(
            "GOOGLE_APPLICATION_CREDENTIALS not set"
          )
        )

      if (!Files.exists(Paths.get(credPath))) {
        throw new RuntimeException(
          s"Credentials file not found: $credPath"
        )
      }

      // Validate JSON format
      Using.resource(new FileInputStream(credPath)) { stream =>
        ServiceAccountCredentials.fromStream(stream)
      }

      credPath
    }
  }

  private def tryEnvironmentVariable(): Try[String] = {
    sys.env
      .get("GCP_SERVICE_ACCOUNT_KEY_FILE")
      .filter(path => Files.exists(Paths.get(path)))
      .map(Success(_))
      .getOrElse(
        Failure(
          new RuntimeException(
            "GCP_SERVICE_ACCOUNT_KEY_FILE not set or file doesn't exist"
          )
        )
      )
  }

  private def tryWellKnownLocation(): Try[String] = {
    val homeDir = sys.props
      .get("user.home")
      .getOrElse(throw new RuntimeException("Cannot determine home directory"))

    val wellKnownPath = Paths.get(
      homeDir,
      ".config",
      "gcloud",
      "application_default_credentials.json"
    )

    if (Files.exists(wellKnownPath)) {
      Success(wellKnownPath.toString)
    } else {
      Failure(
        new RuntimeException(
          s"No credentials found at well-known location: $wellKnownPath"
        )
      )
    }
  }

}
