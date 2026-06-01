package io.github.yusukensanta.parqueteer.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import better.files.File
import scala.util.Success

class ConfigurationManagerTest extends AnyFlatSpec with Matchers {

  "ConfigurationManager" should "create default config if not exists" in {
    val manager = new ConfigurationManager()
    val tempDir = File.newTemporaryDirectory()
    val configPath = (tempDir / "config.yaml").pathAsString

    val result = manager.loadConfig(Some(configPath))
    result shouldBe a[Success[?]]

    // Cleanup
    tempDir.delete()
  }

  it should "parse size strings correctly" in {
    val manager = new ConfigurationManager()

    manager.parseSizeString("64MB") shouldBe 67108864L
    manager.parseSizeString("1GB") shouldBe 1073741824L
    manager.parseSizeString("128KB") shouldBe 131072L
  }

  it should "throw exception for invalid size format" in {
    val manager = new ConfigurationManager()

    an[IllegalArgumentException] should be thrownBy {
      manager.parseSizeString("invalid")
    }
  }

  "ConfigurationManager.validate" should "report config-not-found for nonexistent path" in {
    val manager = new ConfigurationManager()
    val result = manager.validate(Some("/nonexistent/path/config.yaml"))
    result.isSuccess shouldBe true
    result.get should not be empty
    result.get.head should include("not found")
  }

  it should "return empty issues for valid config" in {
    val manager = new ConfigurationManager()
    val tempDir = File.newTemporaryDirectory()
    val configFile = tempDir / "config.yaml"
    configFile.write(
      """|cloud:
         |  s3:
         |    defaultRegion: "us-east-1"
         |    useSsl: true
         |    bufferSize: "64MB"
         |    multipartThreshold: "100MB"
         |  gcs:
         |    bufferSize: "64MB"
         |    chunkSize: "32MB"
         |  azure:
         |    authMethod: "managed_identity"
         |    bufferSize: "64MB"
         |output:
         |  defaultFormat: "table"
         |  maxRows: 1000
         |  precision: 2
         |  showNulls: true
         |  colorOutput: true
         |performance:
         |  readBufferSize: "64MB"
         |  writeBufferSize: "64MB"
         |  maxConcurrency: 4
         |  enableCaching: true
         |  cacheSize: "256MB"
         |logging:
         |  level: "INFO"
         |  enableConsole: true
         |  enableStructured: false
         |""".stripMargin
    )

    val result = manager.validate(Some(configFile.pathAsString))
    result.isSuccess shouldBe true
    result.get shouldBe empty

    tempDir.delete()
  }

  it should "parse auto-generated snake_case config (roundtrip)" in {
    val manager = new ConfigurationManager()
    val tempDir = File.newTemporaryDirectory()
    val configPath = (tempDir / "config.yaml").pathAsString

    // First call: file absent → createDefaultConfig writes snake_case YAML
    manager.loadConfig(Some(configPath)) shouldBe a[Success[?]]

    // Second call: file exists → parseConfigFile must decode snake_case keys
    val result = manager.loadConfig(Some(configPath))
    result shouldBe a[Success[?]]

    val config = result.get
    config.cloud.s3.defaultRegion shouldBe None
    config.cloud.s3.useSsl shouldBe true
    config.cloud.s3.bufferSize shouldBe "64MB"
    config.cloud.s3.multipartThreshold shouldBe "100MB"
    config.output.defaultFormat shouldBe "table"
    config.output.maxRows shouldBe None
    config.performance.readBufferSize shouldBe "64MB"
    config.performance.enableCaching shouldBe true
    config.logging.level shouldBe "INFO"
    config.logging.enableConsole shouldBe true

    tempDir.delete()
  }

  it should "parse explicit snake_case YAML keys" in {
    val manager = new ConfigurationManager()
    val tempDir = File.newTemporaryDirectory()
    val configFile = tempDir / "config.yaml"
    configFile.write(
      """|cloud:
         |  s3:
         |    default_region: "eu-west-1"
         |    use_ssl: false
         |    buffer_size: "128MB"
         |    multipart_threshold: "200MB"
         |  gcs:
         |    project_id: "my-project"
         |    buffer_size: "32MB"
         |    chunk_size: "16MB"
         |  azure:
         |    account_name: "myaccount"
         |    auth_method: "service_principal"
         |    buffer_size: "32MB"
         |output:
         |  default_format: "json"
         |  max_rows: 500
         |  precision: 4
         |  show_nulls: false
         |  color_output: false
         |performance:
         |  read_buffer_size: "32MB"
         |  write_buffer_size: "32MB"
         |  max_concurrency: 8
         |  enable_caching: false
         |  cache_size: "128MB"
         |logging:
         |  level: "DEBUG"
         |  enable_console: false
         |  enable_structured: true
         |""".stripMargin
    )

    val result = manager.loadConfig(Some(configFile.pathAsString))
    result shouldBe a[Success[?]]

    val config = result.get
    config.cloud.s3.defaultRegion shouldBe Some("eu-west-1")
    config.cloud.s3.useSsl shouldBe false
    config.cloud.s3.bufferSize shouldBe "128MB"
    config.cloud.s3.multipartThreshold shouldBe "200MB"
    config.cloud.gcs.projectId shouldBe Some("my-project")
    config.cloud.gcs.bufferSize shouldBe "32MB"
    config.cloud.gcs.chunkSize shouldBe "16MB"
    config.cloud.azure.accountName shouldBe Some("myaccount")
    config.cloud.azure.authMethod shouldBe "service_principal"
    config.output.defaultFormat shouldBe "json"
    config.output.maxRows shouldBe Some(500L)
    config.output.precision shouldBe 4
    config.output.showNulls shouldBe false
    config.output.colorOutput shouldBe false
    config.performance.readBufferSize shouldBe "32MB"
    config.performance.maxConcurrency shouldBe 8
    config.performance.enableCaching shouldBe false
    config.logging.level shouldBe "DEBUG"
    config.logging.enableConsole shouldBe false
    config.logging.enableStructured shouldBe true

    tempDir.delete()
  }

  it should "return issues for malformed YAML" in {
    val manager = new ConfigurationManager()
    val tempDir = File.newTemporaryDirectory()
    val configFile = tempDir / "bad.yaml"
    configFile.write("output:\n  default_format: [unclosed")

    val result = manager.validate(Some(configFile.pathAsString))
    result.isSuccess shouldBe true
    result.get should not be empty

    tempDir.delete()
  }

  "ConfigurationManager.resolvedConfigPath" should "prefer explicit path over env default" in {
    val manager = new ConfigurationManager()
    val explicit = "/explicit/config.yaml"
    manager.resolvedConfigPath(Some(explicit)) shouldBe explicit
  }
}
