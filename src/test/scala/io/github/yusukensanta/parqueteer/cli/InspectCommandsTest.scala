package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import org.scalatest.flatspec.AnyFlatSpec
import java.io.{ByteArrayOutputStream, PrintStream}
import scala.util.Success

class InspectCommandsTest extends CliTestSupport {

  // ── executeInfo ────────────────────────────────────────────────────────

  "executeInfo" should "return 0 on success with quiet" in {
    InspectCommands.executeInfo(
      newService(),
      InfoCommand("/tmp/test.parquet", OutputFormat.Table, verbose = false),
      quietOpts
    ) shouldBe 0
  }

  it should "return error exit code on failure" in {
    val repo = new FakeParquetRepository(
      schemaResult = scala.util.Failure(new java.io.FileNotFoundException("/nope")),
      metadataResult = scala.util.Failure(new java.io.FileNotFoundException("/nope"))
    )
    val (code, _) = captureStderr {
      InspectCommands.executeInfo(
        newService(repo),
        InfoCommand("/nope", OutputFormat.Table, verbose = false),
        quietOpts
      )
    }
    code should not be 0
  }

  // ── executeValidate ────────────────────────────────────────────────────

  "executeValidate" should "return 0 for valid file" in {
    InspectCommands.executeValidate(
      newService(),
      ValidateCommand("/tmp/test.parquet", verbose = false, deep = false),
      quietOpts
    ) shouldBe 0
  }

  it should "return 1 for file with issues" in {
    val repo = new FakeParquetRepository(
      validateResult = Success(List("column mismatch"))
    )
    InspectCommands.executeValidate(
      newService(repo),
      ValidateCommand("/tmp/test.parquet", verbose = false, deep = false),
      quietOpts
    ) shouldBe 1
  }

  // ── executeCount ───────────────────────────────────────────────────────

  "executeCount" should "return 0 on success" in {
    InspectCommands.executeCount(
      newService(),
      CountCommand("/tmp/test.parquet", OutputFormat.Table),
      quietOpts
    ) shouldBe 0
  }

  // ── executeStats ───────────────────────────────────────────────────────

  "executeStats" should "return 0 on success" in {
    InspectCommands.executeStats(
      newService(),
      StatsCommand("/tmp/test.parquet", OutputFormat.Table),
      quietOpts
    ) shouldBe 0
  }

  it should "return error code on failure" in {
    val repo = new FakeParquetRepository(
      statsResult = scala.util.Failure(new java.io.IOException("disk error"))
    )
    val (code, _) = captureStderr {
      InspectCommands.executeStats(
        newService(repo),
        StatsCommand("/nope", OutputFormat.Table),
        quietOpts
      )
    }
    code should not be 0
  }

  // ── executeSchemaInfo ──────────────────────────────────────────────────

  "executeSchemaInfo" should "return 2 when filePath is empty" in {
    val (code, stderr) = captureStderr {
      InspectCommands.executeSchemaInfo(
        newService(),
        SchemaCommand(""),
        quietOpts
      )
    }
    code shouldBe 2
    stderr should include("schema requires a file path")
  }

  it should "return 0 when filePath is valid" in {
    InspectCommands.executeSchemaInfo(
      newService(),
      SchemaCommand("/tmp/test.parquet"),
      quietOpts
    ) shouldBe 0
  }

  // ── executeSchemaDiff ──────────────────────────────────────────────────

  "executeSchemaDiff" should "return 0 when schemas are identical" in {
    InspectCommands.executeSchemaDiff(
      newService(),
      SchemaDiffCommand("/tmp/a.parquet", "/tmp/b.parquet"),
      quietOpts
    ) shouldBe 0
  }

  it should "return error code when schema read fails" in {
    val repo = new FakeParquetRepository(
      schemaResult = scala.util.Failure(new java.io.FileNotFoundException("/nope")),
      metadataResult = scala.util.Failure(new java.io.FileNotFoundException("/nope"))
    )
    val (code, stderr) = captureStderr {
      InspectCommands.executeSchemaDiff(
        newService(repo),
        SchemaDiffCommand("/nope", "/nope"),
        quietOpts
      )
    }
    code should not be 0
    stderr should include("Failed to diff schemas")
  }

  it should "output JSON diff when format is JSON" in {
    val out = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      InspectCommands.executeSchemaDiff(
        newService(),
        SchemaDiffCommand("/tmp/a.parquet", "/tmp/b.parquet", OutputFormat.JSON),
        defaultOpts
      )
    }
    out.toString("UTF-8") should include("{")
  }

  it should "output table diff when format is Table" in {
    val out = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      InspectCommands.executeSchemaDiff(
        newService(),
        SchemaDiffCommand("/tmp/a.parquet", "/tmp/b.parquet", OutputFormat.Table),
        defaultOpts
      )
    }
    out.toString("UTF-8") should not be empty
  }
}
