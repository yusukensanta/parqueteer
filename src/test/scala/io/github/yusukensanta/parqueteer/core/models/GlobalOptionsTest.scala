package io.github.yusukensanta.parqueteer.core.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GlobalOptionsTest extends AnyFlatSpec with Matchers {

  "ColorMode.fromString" should "parse 'always'" in {
    ColorMode.fromString("always") shouldBe Some(ColorMode.Always)
  }

  it should "parse 'never'" in {
    ColorMode.fromString("never") shouldBe Some(ColorMode.Never)
  }

  it should "parse 'auto'" in {
    ColorMode.fromString("auto") shouldBe Some(ColorMode.Auto)
  }

  it should "be case-insensitive" in {
    ColorMode.fromString("ALWAYS") shouldBe Some(ColorMode.Always)
    ColorMode.fromString("Never") shouldBe Some(ColorMode.Never)
    ColorMode.fromString("AUTO") shouldBe Some(ColorMode.Auto)
  }

  it should "return None for unknown input" in {
    ColorMode.fromString("rainbow") shouldBe None
    ColorMode.fromString("") shouldBe None
    ColorMode.fromString("on") shouldBe None
  }

  "GlobalOptions" should "have correct defaults" in {
    val opts = GlobalOptions()
    opts.verbose shouldBe false
    opts.quiet shouldBe false
    opts.configPath shouldBe None
    opts.profile shouldBe None
    opts.region shouldBe None
    opts.colorMode shouldBe ColorMode.Auto
  }

  it should "allow customization" in {
    val opts = GlobalOptions(
      verbose = true,
      quiet = true,
      configPath = Some("/etc/config.yaml"),
      profile = Some("production"),
      region = Some("us-east-1"),
      colorMode = ColorMode.Never
    )
    opts.verbose shouldBe true
    opts.quiet shouldBe true
    opts.configPath shouldBe Some("/etc/config.yaml")
    opts.profile shouldBe Some("production")
    opts.region shouldBe Some("us-east-1")
    opts.colorMode shouldBe ColorMode.Never
  }
}
