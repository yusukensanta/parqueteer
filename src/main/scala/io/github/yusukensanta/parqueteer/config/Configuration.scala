package io.github.yusukensanta.parqueteer.config

import io.circe.{ACursor, Decoder, Encoder, JsonObject}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import better.files.File
import scala.util.{Success, Try}

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
    maxRows: Option[Long] = None,
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
  private val snakeToCamelRe = "_([a-z\\d])".r

  private def normalizeKeys(cursor: ACursor): ACursor =
    cursor.withFocus(_.mapObject { obj =>
      JsonObject.fromIterable(obj.toIterable.map { case (k, v) =>
        snakeToCamelRe.replaceAllIn(k, m => m.group(1).toUpperCase) -> v
      })
    })

  given Decoder[S3Config]    = deriveDecoder[S3Config].prepare(normalizeKeys)
  given Encoder[S3Config]    = deriveEncoder[S3Config]
  given Decoder[GCSConfig]   = deriveDecoder[GCSConfig].prepare(normalizeKeys)
  given Encoder[GCSConfig]   = deriveEncoder[GCSConfig]
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
  given Decoder[AppConfig]     = deriveDecoder[AppConfig].prepare(normalizeKeys)
  given Encoder[AppConfig]     = deriveEncoder[AppConfig]
}

class ConfigurationManager {
  private val defaultConfigPath = File.home / ".parqueteer" / "config.yaml"

  def loadConfig(configPath: Option[String] = None): Try[AppConfig] = {
    val configFile = File(resolvedConfigPath(configPath))
    if !configFile.exists then Success(AppConfig())
    else parseConfigFile(configFile)
  }

  private def parseConfigFile(configFile: File): Try[AppConfig] = {
    import io.circe.yaml.v12.parser

    Try(
      configFile.contentAsString(using java.nio.charset.StandardCharsets.UTF_8)
    ).flatMap { yamlContent =>
      if yamlContent.trim.isEmpty then Success(AppConfig())
      else
        Try {
          parser.parse(yamlContent) match {
            case Right(json) =>
              json.as[AppConfig] match {
                case Right(config) => config
                case Left(error) =>
                  throw new RuntimeException(
                    s"Failed to parse configuration: ${io.github.yusukensanta.parqueteer.cli.CredentialRedactor
                        .redact(error.getMessage)}",
                    error
                  )
              }
            case Left(error) =>
              throw new RuntimeException(
                s"Invalid YAML syntax: ${io.github.yusukensanta.parqueteer.cli.CredentialRedactor
                    .redact(error.getMessage)}",
                error
              )
          }
        }
    }
  }

  def validate(configPath: Option[String] = None): Try[List[String]] = {
    val configFile = File(resolvedConfigPath(configPath))
    if !configFile.exists then {
      Success(
        List(
          s"Config file not found: ${configFile.pathAsString} (using defaults)"
        )
      )
    } else {
      parseConfigFile(configFile).map(_ => List.empty).recover { case ex =>
        List(
          io.github.yusukensanta.parqueteer.cli.CredentialRedactor
            .redact(Option(ex.getMessage).getOrElse("parse error"))
        )
      }
    }
  }

  def resolvedConfigPath(configPath: Option[String]): String =
    configPath
      .orElse(EnvConfig.configPath)
      .getOrElse(defaultConfigPath.pathAsString)
}
