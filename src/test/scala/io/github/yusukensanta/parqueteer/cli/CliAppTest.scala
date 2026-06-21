package io.github.yusukensanta.parqueteer.cli

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
}
