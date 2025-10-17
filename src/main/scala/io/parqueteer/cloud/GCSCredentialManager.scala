package io.parqueteer.cloud

import io.parqueteer.core.models.{StorageLocation, GCSLocation}
import org.apache.hadoop.conf.Configuration
import com.google.auth.oauth2.{GoogleCredentials, ServiceAccountCredentials}
import com.google.cloud.storage.StorageOptions
import scala.util.{Try, Success, Failure}
import java.io.FileInputStream
import java.nio.file.{Files, Paths}

class GCSCredentialManager extends CloudCredentialManager {
  override def supportsLocation(location: StorageLocation): Boolean = {
    location.isInstanceOf[GCSLocation]
  }

  override def configureHadoop(
      location: StorageLocation
  ): Try[Configuration] = {
    location match {
      case gcsLocation: GCSLocation =>
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
          resolveCredentials(gcsLocation) match {
            case Success(credentialsPath) =>
              conf.set("google.cloud.auth.service.account.enable", "true")
              conf.set(
                "google.cloud.auth.service.account.json.keyfile",
                credentialsPath
              )

            case Failure(error) =>
              // Try application default credentials
              conf.set("google.cloud.auth.service.account.enable", "false")
              println(
                s"Warning: Using application default credentials: ${error.getMessage}"
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

  override def validateCredentials(location: StorageLocation): Try[Unit] = {
    location match {
      case gcsLocation: GCSLocation =>
        Try {
          val credentials = GoogleCredentials.getApplicationDefault()
          val storage = StorageOptions
            .newBuilder()
            .setCredentials(credentials)
            .build()
            .getService

          try {
            // Try to get bucket metadata to validate credentials
            val bucket = storage.get(gcsLocation.bucket)
            if (bucket != null) {
              println(
                s"GCS credentials validated for bucket: ${gcsLocation.bucket}"
              )
            } else {
              throw new RuntimeException(
                s"Bucket ${gcsLocation.bucket} not found or not accessible"
              )
            }
          } finally {
            // Storage client doesn't need explicit closing in newer versions
            // but we keep the try-finally for consistency
          }
        }
      case _ =>
        Failure(new IllegalArgumentException("Expected GCSLocation"))
    }
  }

  private def resolveCredentials(
      gcsLocation: GCSLocation
  ): Try[String] = {
    val _ = gcsLocation // suppress unused warning
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
      using(new FileInputStream(credPath)) { stream =>
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

  // Helper method for resource management
  private def using[A <: AutoCloseable, B](resource: A)(f: A => B): B = {
    try {
      f(resource)
    } finally {
      if (resource != null) resource.close()
    }
  }
}
