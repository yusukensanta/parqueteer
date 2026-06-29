package io.github.yusukensanta.parqueteer.cli

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HelpFormatterTest extends AnyFlatSpec with Matchers {

  "HelpFormatter.subcommandHelp" should "return Some for read" in {
    val h = HelpFormatter.subcommandHelp("read")
    h shouldBe defined
    h.get should include("parqueteer read")
    h.get should include("--limit")
  }

  it should "return Some for info" in {
    val h = HelpFormatter.subcommandHelp("info")
    h shouldBe defined
    h.get should include("parqueteer info")
    h.get should include("--verbose")
  }

  it should "return Some for write" in {
    val h = HelpFormatter.subcommandHelp("write")
    h shouldBe defined
    h.get should include("parqueteer write")
    h.get should include("--input-format")
  }

  it should "return Some for validate" in {
    val h = HelpFormatter.subcommandHelp("validate")
    h shouldBe defined
    h.get should include("parqueteer validate")
    h.get should include("--deep")
  }

  it should "return Some for convert" in {
    val h = HelpFormatter.subcommandHelp("convert")
    h shouldBe defined
    h.get should include("parqueteer convert")
    h.get should include("--compression")
  }

  it should "return Some for schema" in {
    val h = HelpFormatter.subcommandHelp("schema")
    h shouldBe defined
    h.get should include("parqueteer schema")
  }

  it should "return Some for schema diff" in {
    val h = HelpFormatter.subcommandHelp("schema diff")
    h shouldBe defined
    h.get should include("schema diff")
    h.get should include("<file1>")
    h.get should include("<file2>")
  }

  it should "return Some for merge" in {
    val h = HelpFormatter.subcommandHelp("merge")
    h shouldBe defined
    h.get should include("parqueteer merge")
    h.get should include("--output")
    h.get should include("--schema-mode")
  }

  it should "return Some for stats" in {
    val h = HelpFormatter.subcommandHelp("stats")
    h shouldBe defined
    h.get should include("parqueteer stats")
  }

  it should "return Some for count" in {
    val h = HelpFormatter.subcommandHelp("count")
    h shouldBe defined
    h.get should include("parqueteer count")
  }

  it should "return Some for completions" in {
    val h = HelpFormatter.subcommandHelp("completions")
    h shouldBe defined
    h.get should include("bash")
    h.get should include("zsh")
    h.get should include("fish")
  }

  it should "return Some for config" in {
    val h = HelpFormatter.subcommandHelp("config")
    h shouldBe defined
    h.get should include("parqueteer config")
    h.get should include("--validate")
  }

  it should "return None for unknown command" in {
    HelpFormatter.subcommandHelp("bogus") shouldBe None
  }

  it should "return None for empty string" in {
    HelpFormatter.subcommandHelp("") shouldBe None
  }

  "HelpFormatter.topLevelHelp" should "list all major commands" in {
    val h = HelpFormatter.topLevelHelp()
    h should include("read")
    h should include("info")
    h should include("schema")
    h should include("write")
    h should include("convert")
    h should include("merge")
    h should include("validate")
    h should include("count")
    h should include("stats")
    h should include("completions")
    h should include("config")
  }

  it should "include global options" in {
    val h = HelpFormatter.topLevelHelp()
    h should include("--verbose")
    h should include("--quiet")
    h should include("--color")
    h should include("--profile")
    h should include("--region")
  }

  it should "include the version from BuildInfo" in {
    val h = HelpFormatter.topLevelHelp()
    h should include(io.github.yusukensanta.parqueteer.BuildInfo.version)
  }
}
