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
