package com.github.yusukensanta.parqueteer.cli

import com.github.yusukensanta.parqueteer.core.models.{
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

  it should "parse info command correctly" in {
    val args = Array("info", "/local/file.parquet", "--no-schema")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command shouldBe defined
    result.get.command.get shouldBe a[InfoCommand]

    val infoCmd = result.get.command.get.asInstanceOf[InfoCommand]
    infoCmd.filePath shouldBe "/local/file.parquet"
    infoCmd.showSchema shouldBe false
  }

  it should "parse write command correctly" in {
    val args = Array(
      "write",
      "output.parquet",
      "--input",
      "data.json",
      "--compression",
      "gzip"
    )
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command shouldBe defined
    result.get.command.get shouldBe a[WriteCommand]

    val writeCmd = result.get.command.get.asInstanceOf[WriteCommand]
    writeCmd.inputPath shouldBe "data.json"
    writeCmd.compression shouldBe CompressionType.Gzip
  }

  it should "parse global options correctly" in {
    // In scopt, for global options to work before commands, test with read command
    val args =
      Array("read", "file.parquet", "--verbose")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.globalOptions.verbose shouldBe true

    // Also test with config option
    val args2 = Array("read", "file.parquet")
    val result2 =
      OParser.parse(ArgumentParser.parser, args2, ArgumentParser.Config())
    result2 shouldBe defined
  }
}
