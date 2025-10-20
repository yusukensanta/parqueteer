package com.github.yusukensanta.parqueteer.config

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
}
