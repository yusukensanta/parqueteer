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

  // ── validate --expect-schema ─────────────────────────────────────────────

  private def contractFile(json: String): String = {
    val f = java.nio.file.Files.createTempFile("parqueteer_contract_", ".json")
    f.toFile.deleteOnExit()
    java.nio.file.Files.writeString(f, json)
    f.toString
  }

  private def captureStdout[A](block: => A): (A, String) = {
    val out    = new ByteArrayOutputStream()
    val result = Console.withOut(new PrintStream(out))(block)
    (result, out.toString("UTF-8"))
  }

  private val matchingContract =
    """{"columns": [{"name": "id", "dataType": "INT64", "optional": false}]}"""

  "executeValidate --expect-schema" should "accept the contract that `schema --format json` produces" in {
    val schemaJson = CliOutputFormatter.formatSchemaJson(
      ParquetFile(LocalPath("/tmp/test.parquet"), schema = Some(defaultSchema))
    )
    val (code, out) = captureStdout {
      InspectCommands.executeValidate(
        newService(),
        ValidateCommand("/tmp/test.parquet", expectSchema = Some(contractFile(schemaJson))),
        defaultOpts
      )
    }
    code shouldBe 0
    out should include("matches")
  }

  it should "exit 4 and print the diff when the schema differs" in {
    val contract = contractFile(
      """{"columns": [
        |  {"name": "id", "dataType": "INT32", "optional": false},
        |  {"name": "email", "dataType": "STRING", "optional": true}
        |]}""".stripMargin
    )
    val (code, out) = captureStdout {
      InspectCommands.executeValidate(
        newService(),
        ValidateCommand("/tmp/test.parquet", expectSchema = Some(contract)),
        defaultOpts
      )
    }
    code shouldBe 4
    out should include("does not match")
    out should include("email")
  }

  it should "exit 3 when the contract file does not exist" in {
    val (code, err) = captureStderr {
      InspectCommands.executeValidate(
        newService(),
        ValidateCommand("/tmp/test.parquet", expectSchema = Some("/nonexistent/contract.json")),
        quietOpts
      )
    }
    code shouldBe 3
    err should include("Failed to check schema contract")
  }

  it should "exit 2 when the contract is not a schema document" in {
    val (code, err) = captureStderr {
      InspectCommands.executeValidate(
        newService(),
        ValidateCommand(
          "/tmp/test.parquet",
          expectSchema = Some(contractFile("""{"columns": 1}"""))
        ),
        quietOpts
      )
    }
    code shouldBe 2
    err should include("schema contract")
  }

  it should "skip the contract check when integrity validation already failed" in {
    val repo = new FakeParquetRepository(validateResult = Success(List("corrupt page")))
    InspectCommands.executeValidate(
      newService(repo),
      ValidateCommand("/tmp/test.parquet", expectSchema = Some("/nonexistent/contract.json")),
      quietOpts
    ) shouldBe 1
  }

  "executeValidateMulti --expect-schema" should "fail (exit 1) when any matched file breaks the contract" in {
    val contract =
      contractFile("""{"columns": [{"name": "id", "dataType": "STRING", "optional": false}]}""")
    val (code, out) = captureStdout {
      InspectCommands.executeValidateMulti(
        newService(),
        List("/tmp/a.parquet", "/tmp/b.parquet"),
        ValidateCommand("/tmp/*.parquet", expectSchema = Some(contract)),
        defaultOpts
      )
    }
    code shouldBe 1
    out should include("does not match")
  }

  it should "pass when every matched file matches the contract" in {
    InspectCommands.executeValidateMulti(
      newService(),
      List("/tmp/a.parquet", "/tmp/b.parquet"),
      ValidateCommand("/tmp/*.parquet", expectSchema = Some(contractFile(matchingContract))),
      quietOpts
    ) shouldBe 0
  }
}
