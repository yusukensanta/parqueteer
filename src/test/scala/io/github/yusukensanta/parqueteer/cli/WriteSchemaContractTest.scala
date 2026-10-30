package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.repositories.HadoopParquetRepository
import io.github.yusukensanta.parqueteer.core.services.ParquetService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.{Files, Path}

/** End-to-end `write --schema` / `convert --schema` against the real writer. */
class WriteSchemaContractTest extends AnyFlatSpec with Matchers {

  private val service = new ParquetService(new HadoopParquetRepository())
  private val quiet   = GlobalOptions(quiet = true)

  private def tempDir(): Path = {
    val d = Files.createTempDirectory("parqueteer_write_schema_")
    d.toFile.deleteOnExit()
    d
  }

  private def file(dir: Path, name: String, content: String): String = {
    val p = dir.resolve(name)
    Files.writeString(p, content)
    p.toFile.deleteOnExit()
    p.toString
  }

  private def silently[A](block: => A): A =
    Console.withErr(new PrintStream(new ByteArrayOutputStream()))(block)

  private def columnsOf(path: String): List[(String, String, Boolean)] =
    service
      .getFileInfo(path)
      .toOption
      .get
      .schema
      .get
      .columns
      .map(c => (c.name, c.dataType, c.isOptional))

  private val contract =
    """{"columns": [
      |  {"name": "id",    "dataType": "INT32",         "optional": false},
      |  {"name": "price", "dataType": "DECIMAL(10,2)", "optional": true},
      |  {"name": "day",   "dataType": "DATE",          "optional": true},
      |  {"name": "name",  "dataType": "STRING",        "optional": true}
      |]}""".stripMargin

  private val rows =
    """{"id": 1, "price": 1.5, "day": "2026-01-02", "name": "a"}
      |{"id": 2, "price": 3, "day": "2026-01-03"}
      |""".stripMargin

  "write --schema" should "write the contract's types instead of inferred ones" in {
    val dir = tempDir()
    val out = dir.resolve("out.parquet").toString
    val cmd = WriteCommand(
      file(dir, "in.ndjson", rows),
      out,
      inputFormat = InputFormat.NDJson,
      schemaFile = Some(file(dir, "contract.json", contract))
    )
    WriteCommands.executeWrite(service, cmd, quiet) shouldBe 0
    columnsOf(out) shouldBe List(
      ("id", "INT32", false),
      ("price", "DECIMAL(10,2)", true),
      ("day", "DATE", true),
      ("name", "STRING", true)
    )
    val read = service.readFile(out, ReadConfig()).toOption.get.content.get.rows
    read.map(_("price")) shouldBe List(
      CellValue.Dec(BigDecimal("1.50")),
      CellValue.Dec(BigDecimal("3.00"))
    )
    read(1)("name") shouldBe CellValue.Null
  }

  it should "reproduce a file's schema from its own `schema --format json` output" in {
    val dir      = tempDir()
    val in       = file(dir, "in.ndjson", rows)
    val inferred = dir.resolve("inferred.parquet").toString
    WriteCommands.executeWrite(
      service,
      WriteCommand(in, inferred, inputFormat = InputFormat.NDJson),
      quiet
    ) shouldBe 0
    val schemaJson = CliOutputFormatter.formatSchemaJson(service.getFileInfo(inferred).toOption.get)

    val again = dir.resolve("again.parquet").toString
    WriteCommands.executeWrite(
      service,
      WriteCommand(
        in,
        again,
        inputFormat = InputFormat.NDJson,
        schemaFile = Some(file(dir, "c.json", schemaJson))
      ),
      quiet
    ) shouldBe 0
    columnsOf(again) shouldBe columnsOf(inferred)
  }

  it should "exit 3 when the contract file does not exist" in {
    val dir = tempDir()
    val cmd = WriteCommand(
      file(dir, "in.ndjson", rows),
      dir.resolve("out.parquet").toString,
      inputFormat = InputFormat.NDJson,
      schemaFile = Some(dir.resolve("missing.json").toString)
    )
    silently(WriteCommands.executeWrite(service, cmd, quiet)) shouldBe 3
  }

  it should "exit 2 before reading input when the contract declares an unwritable type" in {
    val dir = tempDir()
    val out = dir.resolve("out.parquet")
    val cmd = WriteCommand(
      dir.resolve("never-read.ndjson").toString,
      out.toString,
      inputFormat = InputFormat.NDJson,
      schemaFile = Some(
        file(
          dir,
          "c.json",
          """{"columns": [{"name": "t", "dataType": "INT96", "optional": true}]}"""
        )
      )
    )
    silently(WriteCommands.executeWrite(service, cmd, quiet)) shouldBe 2
    Files.exists(out) shouldBe false
  }

  it should "exit 2 and leave no output when a row has a column the contract lacks" in {
    val dir = tempDir()
    val out = dir.resolve("out.parquet")
    val cmd = WriteCommand(
      file(dir, "in.ndjson", """{"id": 1, "extra": "x"}""" + "\n"),
      out.toString,
      inputFormat = InputFormat.NDJson,
      schemaFile = Some(file(dir, "c.json", contract))
    )
    silently(WriteCommands.executeWrite(service, cmd, quiet)) shouldBe 2
    Files.exists(out) shouldBe false
  }

  it should "exit 2 when a row leaves a required column empty" in {
    val dir = tempDir()
    val cmd = WriteCommand(
      file(dir, "in.ndjson", """{"name": "no id"}""" + "\n"),
      dir.resolve("out.parquet").toString,
      inputFormat = InputFormat.NDJson,
      schemaFile = Some(file(dir, "c.json", contract))
    )
    silently(WriteCommands.executeWrite(service, cmd, quiet)) shouldBe 2
  }

  it should "exit 2 when a value cannot be converted to its declared type" in {
    val dir = tempDir()
    val cmd = WriteCommand(
      file(dir, "in.ndjson", """{"id": "one"}""" + "\n"),
      dir.resolve("out.parquet").toString,
      inputFormat = InputFormat.NDJson,
      schemaFile = Some(file(dir, "c.json", contract))
    )
    silently(WriteCommands.executeWrite(service, cmd, quiet)) shouldBe 2
  }

  it should "apply the contract to every file of a multi-file write" in {
    val dir = tempDir()
    val a   = file(dir, "a.ndjson", """{"id": 1, "name": "a"}""" + "\n")
    val b   = file(dir, "b.ndjson", """{"id": 2, "day": "2026-01-02"}""" + "\n")
    val out = dir.resolve("out.parquet").toString
    WriteCommands.executeWriteMulti(
      service,
      List(a, b),
      WriteCommand(
        dir.resolve("*.ndjson").toString,
        out,
        inputFormat = InputFormat.NDJson,
        schemaFile = Some(file(dir, "c.json", contract))
      ),
      quiet
    ) shouldBe 0
    columnsOf(out).map(_._2) shouldBe List("INT32", "DECIMAL(10,2)", "DATE", "STRING")
    service.readFile(out, ReadConfig()).toOption.get.content.get.rows.size shouldBe 2
  }

  it should "show where the schema comes from in a dry run" in {
    val dir      = tempDir()
    val contract = file(dir, "c.json", this.contract)
    val out      = new ByteArrayOutputStream()
    val code = Console.withOut(new PrintStream(out)) {
      WriteCommands.executeWrite(
        service,
        WriteCommand(
          file(dir, "in.ndjson", rows),
          dir.resolve("out.parquet").toString,
          inputFormat = InputFormat.NDJson,
          dryRun = true,
          schemaFile = Some(contract)
        ),
        quiet
      )
    }
    code shouldBe 0
    out.toString("UTF-8") should include(s"$contract (4 columns)")
  }

  "convert --schema" should "apply the contract to a text → parquet conversion" in {
    val dir = tempDir()
    val out = dir.resolve("out.parquet").toString
    WriteCommands.executeConvert(
      service,
      ConvertCommand(
        file(dir, "in.ndjson", rows),
        out,
        schemaFile = Some(file(dir, "c.json", contract))
      ),
      quiet
    ) shouldBe 0
    columnsOf(out).map(_._2) shouldBe List("INT32", "DECIMAL(10,2)", "DATE", "STRING")
  }

  it should "exit 9 for a parquet input, whose schema is fixed" in {
    val dir = tempDir()
    val pq  = dir.resolve("in.parquet").toString
    service.writeFile(pq, List(Map("id" -> CellValue.I32(1)))).isRight shouldBe true
    silently(
      WriteCommands.executeConvert(
        service,
        ConvertCommand(pq, dir.resolve("out.json").toString, schemaFile = Some("c.json")),
        quiet
      )
    ) shouldBe 9
  }
}
