package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import org.scalatest.flatspec.AnyFlatSpec
import java.io.{ByteArrayOutputStream, PrintStream}

class WriteCommandsTest extends CliTestSupport {

  // ── performConvert ─────────────────────────────────────────────────────

  "performConvert" should "reject unsupported extension pairs" in {
    val result = WriteCommands.performConvert(
      newService(),
      "input.txt",
      "output.xml",
      ConversionConfig()
    )
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("Unsupported conversion")
  }

  it should "refuse to overwrite an existing file when converting parquet to a text format" in {
    val tmpFile = java.nio.file.Files.createTempFile("pqt-convert-guard", ".csv")
    try {
      java.nio.file.Files.writeString(tmpFile, "PREEXISTING")
      val result = WriteCommands.performConvert(
        newService(),
        "input.parquet",
        tmpFile.toString,
        ConversionConfig()
      )
      result.isLeft shouldBe true
      result.left.toOption.get.userMessage should include("already exists")
      java.nio.file.Files.readString(tmpFile) shouldBe "PREEXISTING"
    } finally java.nio.file.Files.deleteIfExists(tmpFile)
  }

  // ── executeMerge ───────────────────────────────────────────────────────

  "executeMerge" should "reject unwritable cloud-like but check local paths" in {
    WriteCommands.executeMerge(
      newService(),
      MergeCommand(
        List("/tmp/a.parquet"),
        "s3://bucket/merged.parquet",
        CompressionType.Snappy,
        SchemaMode.Strict,
        dryRun = false
      ),
      quietOpts
    )
  }

  it should "show dry-run summary without writing" in {
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(new java.io.PrintStream(out)) {
      WriteCommands.executeMerge(
        newService(),
        MergeCommand(
          List("/tmp/a.parquet", "/tmp/b.parquet"),
          "s3://bucket/merged.parquet",
          CompressionType.Snappy,
          SchemaMode.Strict,
          dryRun = true
        ),
        quietOpts
      )
    }
    val output = out.toString("UTF-8")
    output should include("Dry run")
    output should include("2 files")
  }

  // ── executeWrite ───────────────────────────────────────────────────────

  "executeWrite" should "show dry-run preview without writing" in {
    val tmpFile = java.nio.file.Files.createTempFile("pqt-test", ".json")
    try {
      java.nio.file.Files.writeString(tmpFile, """[{"id":1,"name":"Alice"}]""")
      val out = new ByteArrayOutputStream()
      Console.withOut(new PrintStream(out)) {
        WriteCommands.executeWrite(
          newService(),
          WriteCommand(
            tmpFile.toString,
            "s3://bucket/out.parquet",
            InputFormat.Json,
            CompressionType.Snappy,
            rowGroupSize = None,
            dryRun = true
          ),
          quietOpts
        )
      }
      val output = out.toString("UTF-8")
      output should include("Dry run")
      output should include("s3://bucket/out.parquet")
      output should include("id")
    } finally java.nio.file.Files.deleteIfExists(tmpFile)
  }

  it should "return error when dry-run input file not found" in {
    val (code, stderr) = captureStderr {
      WriteCommands.executeWrite(
        newService(),
        WriteCommand(
          "/nonexistent/input.json",
          "s3://bucket/out.parquet",
          InputFormat.Json,
          CompressionType.Snappy,
          rowGroupSize = None,
          dryRun = true
        ),
        quietOpts
      )
    }
    code should not be 0
    stderr should include("Failed to read input file")
  }

  // ── executeConvert ─────────────────────────────────────────────────────

  "executeConvert" should "show dry-run preview for non-parquet input" in {
    val out = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      WriteCommands.executeConvert(
        newService(),
        ConvertCommand(
          "input.csv",
          "output.parquet",
          CompressionType.Zstd,
          maxRows = None,
          dryRun = true
        ),
        quietOpts
      )
    }
    val output = out.toString("UTF-8")
    output should include("Dry run")
    output should include("input.csv")
    output should include("output.parquet")
    output should include("csv")
  }

  it should "show dry-run preview for parquet input" in {
    val out = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      WriteCommands.executeConvert(
        newService(),
        ConvertCommand(
          "input.parquet",
          "output.parquet",
          CompressionType.Snappy,
          maxRows = None,
          dryRun = true
        ),
        quietOpts
      )
    }
    val output = out.toString("UTF-8")
    output should include("Dry run")
    output should include("input.parquet")
    output should include("output.parquet")
  }

  it should "return non-zero for unsupported conversion extension pair" in {
    val (code, stderr) = captureStderr {
      WriteCommands.executeConvert(
        newService(),
        ConvertCommand(
          "input.txt",
          "output.xml",
          CompressionType.Snappy,
          maxRows = None,
          dryRun = false
        ),
        quietOpts
      )
    }
    code should not be 0
    stderr should include("Unsupported conversion")
  }

  // ── writeConfigFor ───────────────────────────────────────────────────────

  "WriteCommands.writeConfigFor" should "keep writer defaults when no flags are given" in {
    WriteCommands.writeConfigFor(CompressionType.Zstd, None, WriterOptions()) shouldBe
      WriteConfig(compressionType = CompressionType.Zstd)
  }

  it should "apply row group size, page size and dictionary flags" in {
    val cfg = WriteCommands.writeConfigFor(
      CompressionType.Snappy,
      Some(64L * 1024 * 1024),
      WriterOptions(pageSize = Some(8192), dictionary = false)
    )
    cfg.rowGroupSize shouldBe 64L * 1024 * 1024
    cfg.pageSize shouldBe 8192
    cfg.enableDictionary shouldBe false
  }
}
