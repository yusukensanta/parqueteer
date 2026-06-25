package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.GlobalOptions
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigCommandRendererTest extends AnyFlatSpec with Matchers {

  "ConfigCommandRenderer.render" should "return 0 for config display in quiet mode" in {
    val cmd  = ConfigCommand(validate = false)
    val opts = GlobalOptions(quiet = true)
    ConfigCommandRenderer.render(cmd, opts) shouldBe 0
  }

  it should "return 0 for config validate with no config file (quiet mode)" in {
    val cmd  = ConfigCommand(validate = true)
    val opts = GlobalOptions(quiet = true, configPath = Some("/nonexistent/path.yaml"))
    ConfigCommandRenderer.render(cmd, opts) shouldBe 0
  }

  it should "return 0 for config display (non-quiet)" in {
    val cmd  = ConfigCommand(validate = false)
    val opts = GlobalOptions(quiet = false)
    ConfigCommandRenderer.render(cmd, opts) shouldBe 0
  }

  it should "return 0 for config validate when no file exists (success with issues)" in {
    val cmd  = ConfigCommand(validate = true)
    val opts = GlobalOptions(configPath = Some("/nonexistent/path/config.yaml"))
    ConfigCommandRenderer.render(cmd, opts) shouldBe 0
  }
}
