package io.github.yusukensanta.parqueteer.config

import io.circe.{ACursor, Decoder, Encoder, JsonObject}
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
    defaultRegion: Option[String] = None,
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
  private def normalizeKeys(cursor: ACursor): ACursor =
    cursor.withFocus(_.mapObject { obj =>
      JsonObject.fromIterable(obj.toIterable.map { case (k, v) =>
        "_([a-z\\d])".r.replaceAllIn(k, m => m.group(1).toUpperCase) -> v
      })
    })

  given Decoder[S3Config] = deriveDecoder[S3Config].prepare(normalizeKeys)
  given Encoder[S3Config] = deriveEncoder[S3Config]
  given Decoder[GCSConfig] = deriveDecoder[GCSConfig].prepare(normalizeKeys)
  given Encoder[GCSConfig] = deriveEncoder[GCSConfig]
  given Decoder[AzureConfig] = deriveDecoder[AzureConfig].prepare(normalizeKeys)
  given Encoder[AzureConfig] = deriveEncoder[AzureConfig]
  given Decoder[CloudConfig] = deriveDecoder[CloudConfig].prepare(normalizeKeys)
  given Encoder[CloudConfig] = deriveEncoder[CloudConfig]
  given Decoder[OutputConfig] =
    deriveDecoder[OutputConfig].prepare(normalizeKeys)
  given Encoder[OutputConfig] = deriveEncoder[OutputConfig]
  given Decoder[PerformanceConfig] =
    deriveDecoder[PerformanceConfig].prepare(normalizeKeys)
  given Encoder[PerformanceConfig] = deriveEncoder[PerformanceConfig]
  given Decoder[LoggingConfig] =
    deriveDecoder[LoggingConfig].prepare(normalizeKeys)
  given Encoder[LoggingConfig] = deriveEncoder[LoggingConfig]
  given Decoder[AppConfig] = deriveDecoder[AppConfig].prepare(normalizeKeys)
  given Encoder[AppConfig] = deriveEncoder[AppConfig]
}

class ConfigurationManager {
  private val defaultConfigPath = File.home / ".parqueteer" / "config.yaml"

  def loadConfig(configPath: Option[String] = None): Try[AppConfig] = {
    val configFile = configPath.map(File(_)).getOrElse(defaultConfigPath)
    if (!configFile.exists) Success(AppConfig())
    else parseConfigFile(configFile)
  }

  def initDefaultConfig(configPath: Option[String] = None): Try[Unit] = {
    val configFile = configPath.map(File(_)).getOrElse(defaultConfigPath)
    if (configFile.exists) Success(())
    else createDefaultConfig(configFile)
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
          |    default_region: null
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

  def validate(configPath: Option[String] = None): Try[List[String]] = {
    val configFile = configPath.map(File(_)).getOrElse(defaultConfigPath)
    if (!configFile.exists) {
      Success(
        List(
          s"Config file not found: ${configFile.pathAsString} (using defaults)"
        )
      )
    } else {
      parseConfigFile(configFile).map(_ => List.empty).recover { case ex =>
        List(ex.getMessage)
      }
    }
  }

  def resolvedConfigPath(configPath: Option[String]): String =
    configPath
      .orElse(EnvConfig.configPath)
      .getOrElse(defaultConfigPath.pathAsString)

  def parseSizeString(sizeStr: String): Long =
    io.github.yusukensanta.parqueteer.core.util.SizeParser.parse(sizeStr)
}
