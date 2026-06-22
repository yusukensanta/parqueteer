package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.config.{AppConfig, CloudConfig, OutputConfig, S3Config}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CliAppTest extends AnyFlatSpec with Matchers {

  "shouldShowVersion" should "detect --version flag" in {
    CliApp.shouldShowVersion(Array("--version")) shouldBe true
  }

  it should "detect -V flag" in {
    CliApp.shouldShowVersion(Array("-V")) shouldBe true
  }

  it should "return false when no version flag" in {
    CliApp.shouldShowVersion(Array("read", "file.parquet")) shouldBe false
  }

  it should "return false for empty args" in {
    CliApp.shouldShowVersion(Array.empty) shouldBe false
  }

  "shouldShowTopLevelHelp" should "return true for --help with no command" in {
    CliApp.shouldShowTopLevelHelp(Array("--help")) shouldBe true
  }

  it should "return true for -h with no command" in {
    CliApp.shouldShowTopLevelHelp(Array("-h")) shouldBe true
  }

  it should "return false for --help with known command" in {
    CliApp.shouldShowTopLevelHelp(Array("read", "--help")) shouldBe false
  }

  it should "return false when no help flag" in {
    CliApp.shouldShowTopLevelHelp(Array("read", "file.parquet")) shouldBe false
  }

  "detectSubcommandHelp" should "detect read --help" in {
    CliApp.detectSubcommandHelp(Array("read", "--help")) shouldBe Some("read")
  }

  it should "prefer longer match (schema diff over schema)" in {
    CliApp.detectSubcommandHelp(
      Array("schema", "diff", "--help")
    ) shouldBe Some("schema diff")
  }

  it should "return None when no help flag" in {
    CliApp.detectSubcommandHelp(Array("read", "file.parquet")) shouldBe None
  }

  it should "detect convert -h" in {
    CliApp.detectSubcommandHelp(Array("convert", "-h")) shouldBe Some("convert")
  }

  it should "return None for --help without known command" in {
    CliApp.detectSubcommandHelp(Array("--help")) shouldBe None
  }

  "knownCommands" should "include all expected subcommands" in {
    val expected =
      Set(
        "read",
        "info",
        "write",
        "validate",
        "convert",
        "merge",
        "schema",
        "schema diff",
        "stats",
        "count",
        "config",
        "completions"
      )
    CliApp.knownCommands shouldBe expected
  }

  "applyAppConfig" should "prefer CLI opts over app config" in {
    val opts = GlobalOptions(profile = Some("cli-profile"), region = Some("us-west-2"))
    val appCfg = AppConfig(cloud =
      CloudConfig(s3 = S3Config(profile = Some("file-profile"), defaultRegion = Some("eu-west-1")))
    )
    val result = CliApp.applyAppConfig(opts, appCfg)
    result.profile shouldBe Some("cli-profile")
    result.region shouldBe Some("us-west-2")
  }

  it should "fill missing opts from app config" in {
    val opts = GlobalOptions()
    val appCfg = AppConfig(cloud =
      CloudConfig(s3 = S3Config(profile = Some("file-profile"), defaultRegion = Some("eu-west-1")))
    )
    val result = CliApp.applyAppConfig(opts, appCfg)
    result.profile shouldBe Some("file-profile")
    result.region shouldBe Some("eu-west-1")
  }

  it should "leave None when neither CLI nor config provides values" in {
    val result = CliApp.applyAppConfig(GlobalOptions(), AppConfig())
    result.profile shouldBe None
    result.region shouldBe None
  }

  "applyAppConfigToCommand" should "fill ReadCommand maxRows from config" in {
    val cmd    = ReadCommand(filePath = "test.parquet")
    val appCfg = AppConfig(output = OutputConfig(maxRows = Some(100)))
    val result = CliApp.applyAppConfigToCommand(cmd, appCfg).asInstanceOf[ReadCommand]
    result.maxRows shouldBe Some(100)
  }

  it should "prefer CLI maxRows over config" in {
    val cmd    = ReadCommand(filePath = "test.parquet", maxRows = Some(50))
    val appCfg = AppConfig(output = OutputConfig(maxRows = Some(100)))
    val result = CliApp.applyAppConfigToCommand(cmd, appCfg).asInstanceOf[ReadCommand]
    result.maxRows shouldBe Some(50)
  }

  it should "fill ConvertCommand maxRows from config" in {
    val cmd    = ConvertCommand(inputPath = "in.csv", outputPath = "out.parquet")
    val appCfg = AppConfig(output = OutputConfig(maxRows = Some(200)))
    val result = CliApp.applyAppConfigToCommand(cmd, appCfg).asInstanceOf[ConvertCommand]
    result.maxRows shouldBe Some(200)
  }

  it should "pass through unrelated commands unchanged" in {
    val cmd    = InfoCommand(filePath = "test.parquet")
    val appCfg = AppConfig(output = OutputConfig(maxRows = Some(100)))
    CliApp.applyAppConfigToCommand(cmd, appCfg) shouldBe cmd
  }
}
