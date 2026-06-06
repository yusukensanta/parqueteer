package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.{
  OutputFormat,
  CompressionType,
  SchemaMode
}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scopt.OParser

class ArgumentParserTest extends AnyFlatSpec with Matchers {

  "ArgumentParser" should "parse read command correctly" in {
    val args = Array(
      "read",
      "s3://bucket/file.parquet",
      "--limit",
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
    val args = Array("info", "/local/file.parquet")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command shouldBe defined
    result.get.command.get shouldBe a[InfoCommand]

    val infoCmd = result.get.command.get.asInstanceOf[InfoCommand]
    infoCmd.filePath shouldBe "/local/file.parquet"
    infoCmd.format shouldBe OutputFormat.Table
  }

  it should "parse info command with --format json" in {
    val args = Array("info", "/local/file.parquet", "--format", "json")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command.get
      .asInstanceOf[InfoCommand]
      .format shouldBe OutputFormat.JSON
  }

  it should "reject --schema flag on info (removed)" in {
    val args = Array("info", "/local/file.parquet", "--schema")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())
    result shouldBe None
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

  it should "parse convert --limit flag" in {
    val args = Array("convert", "input.parquet", "output.json", "--limit", "50")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    val convertCmd = result.get.command.get.asInstanceOf[ConvertCommand]
    convertCmd.maxRows shouldBe Some(50L)
  }

  it should "parse convert -n shorthand for limit" in {
    val args = Array("convert", "input.parquet", "output.json", "-n", "10")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    val convertCmd = result.get.command.get.asInstanceOf[ConvertCommand]
    convertCmd.maxRows shouldBe Some(10L)
  }

  it should "parse validate --verbose flag" in {
    val args = Array("validate", "file.parquet", "--verbose")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    val validateCmd = result.get.command.get.asInstanceOf[ValidateCommand]
    validateCmd.verbose shouldBe true
  }

  it should "default validate verbose to false" in {
    val args = Array("validate", "file.parquet")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    val validateCmd = result.get.command.get.asInstanceOf[ValidateCommand]
    validateCmd.verbose shouldBe false
  }

  it should "parse validate --deep flag" in {
    val args = Array("validate", "file.parquet", "--deep")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    val validateCmd = result.get.command.get.asInstanceOf[ValidateCommand]
    validateCmd.deep shouldBe true
  }

  it should "default validate deep to false" in {
    val args = Array("validate", "file.parquet")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    val validateCmd = result.get.command.get.asInstanceOf[ValidateCommand]
    validateCmd.deep shouldBe false
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

  "ArgumentParser config" should "parse config command (default: show)" in {
    val args = Array("config")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command shouldBe defined
    val cmd = result.get.command.get.asInstanceOf[ConfigCommand]
    cmd.validate shouldBe false
  }

  it should "parse config --validate flag" in {
    val args = Array("config", "--validate")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command shouldBe defined
    val cmd = result.get.command.get.asInstanceOf[ConfigCommand]
    cmd.validate shouldBe true
  }

  "ArgumentParser merge" should "parse merge command with required output" in {
    val args =
      Array("merge", "a.parquet", "b.parquet", "--output", "out.parquet")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command shouldBe defined
    result.get.command.get shouldBe a[MergeCommand]

    val cmd = result.get.command.get.asInstanceOf[MergeCommand]
    cmd.inputPaths shouldBe List("a.parquet", "b.parquet")
    cmd.outputPath shouldBe "out.parquet"
    cmd.compression shouldBe CompressionType.Snappy
    cmd.schemaMode shouldBe SchemaMode.Strict
  }

  it should "parse schema-mode union" in {
    val args = Array(
      "merge",
      "a.parquet",
      "b.parquet",
      "--output",
      "out.parquet",
      "--schema-mode",
      "union"
    )
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command.get
      .asInstanceOf[MergeCommand]
      .schemaMode shouldBe SchemaMode.Union
  }

  it should "reject invalid schema-mode" in {
    val args = Array(
      "merge",
      "a.parquet",
      "b.parquet",
      "--output",
      "out.parquet",
      "--schema-mode",
      "lax"
    )
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())
    result shouldBe None
  }

  it should "fail when output is not specified" in {
    val args = Array("merge", "a.parquet", "b.parquet")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())
    result shouldBe None
  }

  "ArgumentParser schema" should "parse schema command with default format" in {
    val args = Array("schema", "/tmp/test.parquet")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command shouldBe defined
    result.get.command.get shouldBe a[SchemaCommand]

    val cmd = result.get.command.get.asInstanceOf[SchemaCommand]
    cmd.filePath shouldBe "/tmp/test.parquet"
    cmd.format shouldBe OutputFormat.Table
  }

  it should "parse schema --format json" in {
    val args = Array("schema", "/tmp/test.parquet", "--format", "json")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command.get
      .asInstanceOf[SchemaCommand]
      .format shouldBe OutputFormat.JSON
  }

  it should "parse schema diff subcommand" in {
    val args = Array("schema", "diff", "a.parquet", "b.parquet")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command.get shouldBe a[SchemaDiffCommand]

    val cmd = result.get.command.get.asInstanceOf[SchemaDiffCommand]
    cmd.file1 shouldBe "a.parquet"
    cmd.file2 shouldBe "b.parquet"
    cmd.format shouldBe OutputFormat.Table
  }

  it should "parse schema diff with --format json" in {
    val args =
      Array("schema", "diff", "a.parquet", "b.parquet", "--format", "json")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())

    result shouldBe defined
    result.get.command.get
      .asInstanceOf[SchemaDiffCommand]
      .format shouldBe OutputFormat.JSON
  }

  "ArgumentParser error handling" should "reject unknown --format on convert" in {
    val args = Array("convert", "in.parquet", "out.json", "--format", "xml")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())
    result shouldBe None
  }

  it should "reject unknown --compression on convert" in {
    val args =
      Array("convert", "in.parquet", "out.parquet", "--compression", "bz2")
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())
    result shouldBe None
  }

  it should "reject unknown --compression on write" in {
    val args =
      Array(
        "write",
        "out.parquet",
        "--input",
        "in.json",
        "--compression",
        "bz2"
      )
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())
    result shouldBe None
  }

  it should "reject unknown --schema-mode on merge" in {
    val args = Array(
      "merge",
      "a.parquet",
      "b.parquet",
      "--output",
      "out.parquet",
      "--schema-mode",
      "lenient"
    )
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())
    result shouldBe None
  }

  it should "accept valid --schema-mode strict" in {
    val args = Array(
      "merge",
      "a.parquet",
      "b.parquet",
      "--output",
      "out.parquet",
      "--schema-mode",
      "strict"
    )
    val result =
      OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config())
    result shouldBe defined
    result.get.command.get
      .asInstanceOf[MergeCommand]
      .schemaMode shouldBe SchemaMode.Strict
  }

  it should "reject --limit 0 for read command" in {
    val result = OParser.parse(
      ArgumentParser.parser,
      Array("read", "a.parquet", "--limit", "0"),
      ArgumentParser.Config()
    )
    result shouldBe None
  }

  it should "reject --limit -1 for read command" in {
    val result = OParser.parse(
      ArgumentParser.parser,
      Array("read", "a.parquet", "--limit", "-1"),
      ArgumentParser.Config()
    )
    result shouldBe None
  }

  it should "reject --limit 0 for convert command" in {
    val result = OParser.parse(
      ArgumentParser.parser,
      Array("convert", "a.parquet", "out.json", "--limit", "0"),
      ArgumentParser.Config()
    )
    result shouldBe None
  }

  it should "parse count command correctly" in {
    val result = OParser.parse(
      ArgumentParser.parser,
      Array("count", "/local/file.parquet"),
      ArgumentParser.Config()
    )
    result shouldBe defined
    result.get.command.get shouldBe a[CountCommand]
    result.get.command.get
      .asInstanceOf[CountCommand]
      .filePath shouldBe "/local/file.parquet"
    result.get.command.get
      .asInstanceOf[CountCommand]
      .format shouldBe OutputFormat.Table
  }

  it should "parse count command with --format json" in {
    val result = OParser.parse(
      ArgumentParser.parser,
      Array("count", "/local/file.parquet", "--format", "json"),
      ArgumentParser.Config()
    )
    result shouldBe defined
    result.get.command.get
      .asInstanceOf[CountCommand]
      .format shouldBe OutputFormat.JSON
  }

  it should "reject invalid format for count command" in {
    val result = OParser.parse(
      ArgumentParser.parser,
      Array("count", "a.parquet", "--format", "csv"),
      ArgumentParser.Config()
    )
    result shouldBe None
  }
}
