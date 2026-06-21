package io.github.yusukensanta.parqueteer.cloud

import io.github.yusukensanta.parqueteer.core.models.{GCSLocation, StorageLocation}
import org.apache.hadoop.conf.Configuration
import org.slf4j.LoggerFactory
import scala.util.{Failure, Success, Try}
import java.nio.file.{Files, Paths}

private object GCSTuning {
  // All values as plain bytes to avoid NumberFormatException: Hadoop 3.5
  // core-default.xml ships GCS defaults with suffix notation ("8m", "64m")
  // but conf.getLong/getInt call Long.parseLong directly.
  val BlockSize           = "67108864"  // 64MB
  val InputBufferSize     = "8388608"   // 8MB
  val InplaceSeekLimit    = "8388608"   // 8MB
  val MinRangeRequestSize = "2097152"   // 2MB
  val OutputBufferSize    = "8388608"   // 8MB
  val UploadChunkSize     = "67108864"  // 64MB
  val RewriteMaxChunkSize = "536870912" // 512MB
  val MaxRequestsPerBatch = "30"
  val BatchThreads        = "15"
}

class GCSCredentialManager extends CloudCredentialManager {
  private val logger = LoggerFactory.getLogger(getClass)

  override def configureHadoop(
      location: StorageLocation
  ): Try[Configuration] =
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
                io.github.yusukensanta.parqueteer.cli.CredentialRedactor
                  .redact(Option(error.getMessage).getOrElse(""))
              )
          }

          // Project ID configuration
          sys.env
            .get("GCP_PROJECT_ID")
            .orElse(sys.env.get("GOOGLE_CLOUD_PROJECT"))
            .foreach { projectId =>
              conf.set("fs.gs.project.id", projectId)
            }

          conf.set("fs.gs.block.size", GCSTuning.BlockSize)
          conf.set("fs.gs.inputstream.buffer.size", GCSTuning.InputBufferSize)
          conf.set(
            "fs.gs.inputstream.inplace.seek.limit",
            GCSTuning.InplaceSeekLimit
          )
          conf.set(
            "fs.gs.inputstream.min.range.request.size",
            GCSTuning.MinRangeRequestSize
          )
          conf.set("fs.gs.outputstream.buffer.size", GCSTuning.OutputBufferSize)
          conf.set(
            "fs.gs.outputstream.upload.chunk.size",
            GCSTuning.UploadChunkSize
          )
          conf.set(
            "fs.gs.rewrite.max.chunk.size",
            GCSTuning.RewriteMaxChunkSize
          )
          conf.set(
            "fs.gs.max.requests.per.batch",
            GCSTuning.MaxRequestsPerBatch
          )
          conf.set("fs.gs.batch.threads", GCSTuning.BatchThreads)

          conf
        }
      case _ =>
        Failure(new IllegalArgumentException("Expected GCSLocation"))
    }

  private def resolveCredentials(): Try[String] =
    CloudCredentialManager.firstSuccess(
      "No GCS credentials found. Attempted strategies:",
      List(
        () => tryServiceAccountFile(),
        () => tryEnvironmentVariable(),
        () => tryWellKnownLocation()
      )
    )

  // Checks existence only; the GCS connector validates the file content on first
  // I/O operation. Parsing and discarding credentials here would introduce a
  // TOCTOU window and double-parse with no benefit.
  private def tryServiceAccountFile(): Try[String] = Try {
    val credPath = CloudCredentialManager.requiredEnv("GOOGLE_APPLICATION_CREDENTIALS")
    if !Files.exists(Paths.get(credPath)) then
      throw new RuntimeException(
        s"GOOGLE_APPLICATION_CREDENTIALS file not found: $credPath"
      )
    credPath
  }

  private def tryEnvironmentVariable(): Try[String] =
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

  private def tryWellKnownLocation(): Try[String] = Try {
    val homeDir = sys.props
      .get("user.home")
      .getOrElse(throw new RuntimeException("Cannot determine home directory"))

    val wellKnownPath = Paths.get(
      homeDir,
      ".config",
      "gcloud",
      "application_default_credentials.json"
    )

    if Files.exists(wellKnownPath) then wellKnownPath.toString
    else
      throw new RuntimeException(
        s"No credentials found at well-known location: $wellKnownPath"
      )
  }

}
