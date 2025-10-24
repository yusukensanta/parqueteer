package io.github.yusukensanta.parqueteer.config

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import better.files.File
import scala.util.{Try, Success}

case class AppConfig(
    cloud: CloudConfig = CloudConfig(),
    output: OutputConfig = OutputConfig(),
    performance: PerformanceConfig = PerformanceConfig(),
    logging: LoggingConfig = LoggingConfig()
)

case class CloudConfig(
    s3: S3Config = S3Config(),
    gcs: GCSConfig = GCSConfig(),
    azure: AzureConfig = AzureConfig()
)

case class S3Config(
    defaultRegion: String = "us-east-1",
    profile: Option[String] = None,
    endpointUrl: Option[String] = None,
    useSsl: Boolean = true,
    bufferSize: String = "64MB",
    multipartThreshold: String = "100MB"
)

case class GCSConfig(
    projectId: Option[String] = None,
    credentialsFile: Option[String] = None,
    bufferSize: String = "64MB",
    chunkSize: String = "32MB"
)

case class AzureConfig(
    accountName: Option[String] = None,
    authMethod: String = "managed_identity",
    bufferSize: String = "64MB"
)

case class OutputConfig(
    defaultFormat: String = "table",
    maxRows: Long = 1000,
    precision: Int = 2,
    showNulls: Boolean = true,
    colorOutput: Boolean = true
)

case class PerformanceConfig(
    readBufferSize: String = "64MB",
    writeBufferSize: String = "64MB",
    maxConcurrency: Int = 4,
    enableCaching: Boolean = true,
    cacheSize: String = "256MB"
)

case class LoggingConfig(
    level: String = "INFO",
    file: Option[String] = None,
    enableConsole: Boolean = true,
    enableStructured: Boolean = false
)

object AppConfig {
  implicit val s3ConfigDecoder: Decoder[S3Config] = deriveDecoder[S3Config]
  implicit val s3ConfigEncoder: Encoder[S3Config] = deriveEncoder[S3Config]

  implicit val gcsConfigDecoder: Decoder[GCSConfig] = deriveDecoder[GCSConfig]
  implicit val gcsConfigEncoder: Encoder[GCSConfig] = deriveEncoder[GCSConfig]

  implicit val azureConfigDecoder: Decoder[AzureConfig] =
    deriveDecoder[AzureConfig]
  implicit val azureConfigEncoder: Encoder[AzureConfig] =
    deriveEncoder[AzureConfig]

  implicit val cloudConfigDecoder: Decoder[CloudConfig] =
    deriveDecoder[CloudConfig]
  implicit val cloudConfigEncoder: Encoder[CloudConfig] =
    deriveEncoder[CloudConfig]

  implicit val outputConfigDecoder: Decoder[OutputConfig] =
    deriveDecoder[OutputConfig]
  implicit val outputConfigEncoder: Encoder[OutputConfig] =
    deriveEncoder[OutputConfig]

  implicit val performanceConfigDecoder: Decoder[PerformanceConfig] =
    deriveDecoder[PerformanceConfig]
  implicit val performanceConfigEncoder: Encoder[PerformanceConfig] =
    deriveEncoder[PerformanceConfig]

  implicit val loggingConfigDecoder: Decoder[LoggingConfig] =
    deriveDecoder[LoggingConfig]
  implicit val loggingConfigEncoder: Encoder[LoggingConfig] =
    deriveEncoder[LoggingConfig]

  implicit val appConfigDecoder: Decoder[AppConfig] = deriveDecoder[AppConfig]
  implicit val appConfigEncoder: Encoder[AppConfig] = deriveEncoder[AppConfig]
}

class ConfigurationManager {
  private val defaultConfigPath = File.home / ".parqueteer" / "config.yaml"

  def loadConfig(configPath: Option[String] = None): Try[AppConfig] = {
    val configFile = configPath.map(File(_)).getOrElse(defaultConfigPath)

    if (!configFile.exists) {
      createDefaultConfig(configFile)
      Success(AppConfig())
    } else {
      parseConfigFile(configFile)
    }
  }

  private def parseConfigFile(configFile: File): Try[AppConfig] = {
    import io.circe.yaml.parser

    Try {
      val yamlContent = configFile.contentAsString
      parser.parse(yamlContent) match {
        case Right(json) =>
          json.as[AppConfig] match {
            case Right(config) => config
            case Left(error) =>
              throw new RuntimeException(
                s"Failed to parse configuration: ${error.getMessage}"
              )
          }
        case Left(error) =>
          throw new RuntimeException(
            s"Invalid YAML syntax: ${error.getMessage}"
          )
      }
    }
  }

  private def createDefaultConfig(configFile: File): Try[Unit] = {
    Try {
      configFile.parent.createDirectoryIfNotExists()

      val defaultYaml =
        """cloud:
          |  s3:
          |    default_region: "us-east-1"
          |    profile: null
          |    endpoint_url: null
          |    use_ssl: true
          |    buffer_size: "64MB"
          |    multipart_threshold: "100MB"
          |  gcs:
          |    project_id: null
          |    credentials_file: null
          |    buffer_size: "64MB"
          |    chunk_size: "32MB"
          |  azure:
          |    account_name: null
          |    auth_method: "managed_identity"
          |    buffer_size: "64MB"
          |
          |output:
          |  default_format: "table"
          |  max_rows: 1000
          |  precision: 2
          |  show_nulls: true
          |  color_output: true
          |
          |performance:
          |  read_buffer_size: "64MB"
          |  write_buffer_size: "64MB"
          |  max_concurrency: 4
          |  enable_caching: true
          |  cache_size: "256MB"
          |
          |logging:
          |  level: "INFO"
          |  file: null
          |  enable_console: true
          |  enable_structured: false
          |""".stripMargin

      configFile.write(defaultYaml)
    }
  }

  def parseSizeString(sizeStr: String): Long = {
    val units = Map(
      "B" -> 1L,
      "KB" -> 1024L,
      "MB" -> 1024L * 1024L,
      "GB" -> 1024L * 1024L * 1024L
    )

    val pattern = """(\d+)\s*(B|KB|MB|GB)""".r
    sizeStr.toUpperCase match {
      case pattern(size, unit) => size.toLong * units(unit)
      case _ =>
        throw new IllegalArgumentException(s"Invalid size format: $sizeStr")
    }
  }
}
