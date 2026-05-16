package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.{
  OutputFormat,
  CompressionType
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scopt.OParser

class ArgumentParserTest extends AnyFlatSpec with Matchers {

  "ArgumentParser" should "parse read command correctly" in {
    val args = Array(
      "read",
      "s3://bucket/file.parquet",
      "--max-rows",
      "100",
      "--format",
      "json"
    )
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command shouldBe defined
    result.get.command.get shouldBe a[ReadCommand]

    val readCmd = result.get.command.get.asInstanceOf[ReadCommand]
    readCmd.filePath shouldBe "s3://bucket/file.parquet"
    readCmd.maxRows shouldBe Some(100L)
    readCmd.format shouldBe OutputFormat.JSON
  }

  it should "parse info command with --schema flag" in {
    val args = Array("info", "/local/file.parquet", "--schema")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command shouldBe defined
    result.get.command.get shouldBe a[InfoCommand]

    val infoCmd = result.get.command.get.asInstanceOf[InfoCommand]
    infoCmd.filePath shouldBe "/local/file.parquet"
    infoCmd.showSchema shouldBe true
    infoCmd.showMetadata shouldBe false
  }

  it should "parse info command with --metadata flag" in {
    val args = Array("info", "/local/file.parquet", "--metadata")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    val infoCmd = result.get.command.get.asInstanceOf[InfoCommand]
    infoCmd.showSchema shouldBe false
    infoCmd.showMetadata shouldBe true
  }

  it should "parse info command with no flags (show all)" in {
    val args = Array("info", "/local/file.parquet")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    val infoCmd = result.get.command.get.asInstanceOf[InfoCommand]
    infoCmd.showSchema shouldBe false
    infoCmd.showMetadata shouldBe false
  }

  it should "parse write command correctly" in {
    val args = Array(
      "write",
      "data.json",
      "output.parquet",
      "--compression",
      "gzip"
    )
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command shouldBe defined
    result.get.command.get shouldBe a[WriteCommand]

    val writeCmd = result.get.command.get.asInstanceOf[WriteCommand]
    writeCmd.outputPath shouldBe "output.parquet"
    writeCmd.inputPath shouldBe "data.json"
    writeCmd.compression shouldBe CompressionType.Gzip
    writeCmd.dryRun shouldBe false
  }

  it should "parse write --dry-run flag" in {
    val args = Array("write", "data.json", "output.parquet", "--dry-run")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    val writeCmd = result.get.command.get.asInstanceOf[WriteCommand]
    writeCmd.dryRun shouldBe true
  }

  it should "parse convert --dry-run flag" in {
    val args = Array("convert", "input.parquet", "output.json", "--dry-run")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    val convertCmd = result.get.command.get.asInstanceOf[ConvertCommand]
    convertCmd.dryRun shouldBe true
  }

  it should "parse global options correctly" in {
    val args = Array("read", "file.parquet", "--verbose")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.globalOptions.verbose shouldBe true

    val args2 = Array("read", "file.parquet")
    val result2 =
      OParser.parse(ArgumentParser.parser, args2, ArgumentParser.Config())
    result2 shouldBe defined
  }

  it should "parse --quiet / -q flag" in {
    val args = Array("read", "file.parquet", "--quiet")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.globalOptions.quiet shouldBe true
  }

  it should "parse -q shorthand for quiet" in {
    val args = Array("read", "file.parquet", "-q")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.globalOptions.quiet shouldBe true
  }

  it should "parse --color=never" in {
    val args = Array("read", "file.parquet", "--color", "never")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.globalOptions.colorMode shouldBe ColorMode.Never
  }

  it should "parse --color=always" in {
    val args = Array("read", "file.parquet", "--color", "always")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.globalOptions.colorMode shouldBe ColorMode.Always
  }

  it should "default colorMode to Auto" in {
    val args = Array("read", "file.parquet")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.globalOptions.colorMode shouldBe ColorMode.Auto
  }

  it should "reject --color with invalid value" in {
    val args = Array("read", "file.parquet", "--color", "rainbow")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())
    result shouldBe None
  }

  it should "parse markdown output format" in {
    val args = Array("read", "file.parquet", "--format", "markdown")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command.get
      .asInstanceOf[ReadCommand]
      .format shouldBe OutputFormat.Markdown
  }

  it should "parse ndjson output format" in {
    val args = Array("read", "file.parquet", "--format", "ndjson")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command.get
      .asInstanceOf[ReadCommand]
      .format shouldBe OutputFormat.NDJSON
  }

  it should "reject unknown output format" in {
    val args = Array("read", "file.parquet", "--format", "xml")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())
    result shouldBe None
  }

  "ArgumentParser config show" should "parse config show subcommand" in {
    val args = Array("config", "show")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command shouldBe defined
    result.get.command.get shouldBe a[ConfigCommand]
    result.get.command.get
      .asInstanceOf[ConfigCommand]
      .sub shouldBe ConfigShowSubcommand
  }

  "ArgumentParser config validate" should "parse config validate subcommand" in {
    val args = Array("config", "validate")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command shouldBe defined
    result.get.command.get shouldBe a[ConfigCommand]
    result.get.command.get
      .asInstanceOf[ConfigCommand]
      .sub shouldBe ConfigValidateSubcommand
  }
}
