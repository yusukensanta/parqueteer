package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.services.ParquetService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.{ByteArrayOutputStream, PrintStream}
import java.time.Instant
import scala.util.{Success, Try}

class CommandExecutorTest extends AnyFlatSpec with Matchers {

  private class FakeParquetRepository(
      contentResult: Try[FileContent] = Success(defaultContent),
      schemaResult: Try[ParquetSchema] = Success(defaultSchema),
      metadataResult: Try[FileMetadata] = Success(defaultMetadata),
      validateResult: Try[List[String]] = Success(List.empty),
      writeResult: Try[Unit] = Success(()),
      statsResult: Try[FileStats] = Success(defaultStats),
      schemaFieldsResult: Try[List[FieldSummary]] = Success(List.empty),
      deleteResult: Try[Unit] = Success(())
  ) extends ParquetRepository {

    override def readContent(file: ParquetFile, config: ReadConfig): Try[FileContent] =
      contentResult
    override def readSchema(file: ParquetFile): Try[ParquetSchema]  = schemaResult
    override def readMetadata(file: ParquetFile): Try[FileMetadata] = metadataResult

    override def readFileInfo(
        file: ParquetFile
    ): Try[(ParquetSchema, FileMetadata, List[RowGroupInfo])] =
      for {
        s <- schemaResult
        m <- metadataResult
      } yield (s, m, Nil)
    override def validateFile(file: ParquetFile, deep: Boolean): Try[List[String]] = validateResult

    override def writeContent(
        location: StorageLocation,
        data: List[Map[String, CellValue]],
        schema: Option[ParquetSchema],
        config: WriteConfig
    ): Try[Unit] = writeResult

    override def streamContent(file: ParquetFile, config: ReadConfig)(
        process: Map[String, CellValue] => Unit
    ): Try[Long] =
      contentResult.map { fc =>
        fc.rows.foreach(process); fc.rows.length.toLong
      }

    override def writeContentStream(
        location: StorageLocation,
        schema: ParquetSchema,
        config: WriteConfig
    )(feed: (Map[String, CellValue] => Unit) => Unit): Try[Long] =
      writeResult.map { _ =>
        var c = 0L; feed(_ => c += 1); c
      }
    override def readStats(file: ParquetFile): Try[FileStats]                 = statsResult
    override def readSchemaFields(file: ParquetFile): Try[List[FieldSummary]] = schemaFieldsResult
    override def deleteFile(location: StorageLocation): Try[Unit]             = deleteResult
  }

  private val defaultContent = FileContent(
    rows = List(Map("id" -> CellValue.I64(1L), "name" -> CellValue.Str("Alice"))),
    totalRows = 1L,
    isPartial = false
  )

  private val defaultSchema = ParquetSchema(
    columns = List(ColumnInfo("id", "INT64", isOptional = false, 1, 0, "SNAPPY")),
    rowGroupCount = 1L,
    totalRowCount = 1L
  )

  private val defaultMetadata = FileMetadata(
    fileSize = 512L,
    createdAt = Some(Instant.parse("2024-01-01T00:00:00Z")),
    modifiedAt = None,
    compressionRatio = None,
    version = "2.0",
    createdBy = Some("test")
  )

  private val defaultStats = FileStats(
    columns = List(ColumnStats("id", "INT64", 0L, Some("1"), Some("100"))),
    totalRows = 1L,
    rowGroupCount = 1L
  )

  private val quietOpts   = GlobalOptions(quiet = true)
  private val defaultOpts = GlobalOptions(quiet = false)

  private def newService(repo: FakeParquetRepository = new FakeParquetRepository()) =
    new ParquetService(repo)

  private def captureStderr[A](block: => A): (A, String) = {
    val baos = new ByteArrayOutputStream()
    val ps   = new PrintStream(baos)
    val old  = System.err
    System.setErr(ps)
    try {
      val result = block
      ps.flush()
      (result, baos.toString("UTF-8"))
    } finally System.setErr(old)
  }

  // ── reportError ────────────────────────────────────────────────────────

  "reportError" should "return the error's exit code" in {
    val (code, _) = captureStderr {
      CommandExecutor.reportError("Test", quietOpts)(
        ParqueteerError.FileNotFound("/missing")
      )
    }
    code shouldBe 3
  }

  it should "print prefix and error message to stderr" in {
    val (_, stderr) = captureStderr {
      CommandExecutor.reportError("Failed", defaultOpts)(
        ParqueteerError.FileNotFound("/x")
      )
    }
    stderr should include("Failed:")
    stderr should include("/x")
  }

  it should "redact credentials in error messages" in {
    val (_, stderr) = captureStderr {
      CommandExecutor.reportError("Err", defaultOpts)(
        ParqueteerError.IOError(
          new java.io.IOException("AccessKey=AKIAIOSFODNN7EXAMPLE leaked")
        )
      )
    }
    stderr should not include "AKIAIOSFODNN7EXAMPLE"
  }

  it should "print hint when provided" in {
    val (_, stderr) = captureStderr {
      CommandExecutor.reportError("Err", defaultOpts, Some("Try --help"))(
        ParqueteerError.InvalidFormat("f", "bad")
      )
    }
    stderr should include("Try --help")
  }

  // ── checkOutputWritable ────────────────────────────────────────────────

  "checkOutputWritable" should "pass cloud URIs without filesystem check" in {
    CommandExecutor.checkOutputWritable("s3://bucket/key") shouldBe Right(())
    CommandExecutor.checkOutputWritable("gs://bucket/key") shouldBe Right(())
    CommandExecutor.checkOutputWritable("abfss://container@account/path") shouldBe Right(())
  }

  it should "pass for writable local paths" in {
    val tmpDir = java.nio.file.Files.createTempDirectory("pqt-test")
    try
      CommandExecutor.checkOutputWritable(
        tmpDir.resolve("out.parquet").toString
      ) shouldBe Right(())
    finally
      java.nio.file.Files.delete(tmpDir)
  }

  // ── executeCompletions ─────────────────────────────────────────────────

  "executeCompletions" should "return 0 for bash" in {
    CommandExecutor.executeCompletions("bash", quietOpts) shouldBe 0
  }

  it should "return 0 for zsh" in {
    CommandExecutor.executeCompletions("zsh", quietOpts) shouldBe 0
  }

  it should "return 0 for fish" in {
    CommandExecutor.executeCompletions("fish", quietOpts) shouldBe 0
  }

  it should "return 1 for unknown shell" in {
    val (code, stderr) = captureStderr {
      CommandExecutor.executeCompletions("powershell", quietOpts)
    }
    code shouldBe 1
    stderr should include("Unsupported shell")
  }

  // ── execute dispatch ───────────────────────────────────────────────────

  "execute" should "dispatch CompletionsCommand" in {
    CommandExecutor.execute(
      CompletionsCommand("bash"),
      newService(),
      quietOpts
    ) shouldBe 0
  }

  // ── executeInfo ────────────────────────────────────────────────────────

  "executeInfo" should "return 0 on success with quiet" in {
    CommandExecutor.executeInfo(
      newService(),
      "/tmp/test.parquet",
      OutputFormat.Table,
      verbose = false,
      quietOpts
    ) shouldBe 0
  }

  it should "return error exit code on failure" in {
    val repo = new FakeParquetRepository(
      schemaResult = scala.util.Failure(new java.io.FileNotFoundException("/nope")),
      metadataResult = scala.util.Failure(new java.io.FileNotFoundException("/nope"))
    )
    val (code, _) = captureStderr {
      CommandExecutor.executeInfo(
        newService(repo),
        "/nope",
        OutputFormat.Table,
        verbose = false,
        quietOpts
      )
    }
    code should not be 0
  }

  // ── executeValidate ────────────────────────────────────────────────────

  "executeValidate" should "return 0 for valid file" in {
    CommandExecutor.executeValidate(
      newService(),
      "/tmp/test.parquet",
      verbose = false,
      deep = false,
      quietOpts
    ) shouldBe 0
  }

  it should "return 1 for file with issues" in {
    val repo = new FakeParquetRepository(
      validateResult = Success(List("column mismatch"))
    )
    CommandExecutor.executeValidate(
      newService(repo),
      "/tmp/test.parquet",
      verbose = false,
      deep = false,
      quietOpts
    ) shouldBe 1
  }

  // ── executeCount ───────────────────────────────────────────────────────

  "executeCount" should "return 0 on success" in {
    CommandExecutor.executeCount(
      newService(),
      "/tmp/test.parquet",
      OutputFormat.Table,
      quietOpts
    ) shouldBe 0
  }

  // ── executeStats ───────────────────────────────────────────────────────

  "executeStats" should "return 0 on success" in {
    CommandExecutor.executeStats(
      newService(),
      "/tmp/test.parquet",
      OutputFormat.Table,
      quietOpts
    ) shouldBe 0
  }

  it should "return error code on failure" in {
    val repo = new FakeParquetRepository(
      statsResult = scala.util.Failure(new java.io.IOException("disk error"))
    )
    val (code, _) = captureStderr {
      CommandExecutor.executeStats(newService(repo), "/nope", OutputFormat.Table, quietOpts)
    }
    code should not be 0
  }

  // ── executeSchemaInfo ──────────────────────────────────────────────────

  "executeSchemaInfo" should "return 2 when filePath is empty" in {
    val (code, stderr) = captureStderr {
      CommandExecutor.executeSchemaInfo(
        newService(),
        SchemaCommand(""),
        quietOpts
      )
    }
    code shouldBe 2
    stderr should include("schema requires a file path")
  }

  it should "return 0 when filePath is valid" in {
    CommandExecutor.executeSchemaInfo(
      newService(),
      SchemaCommand("/tmp/test.parquet"),
      quietOpts
    ) shouldBe 0
  }

  // ── performConvert ─────────────────────────────────────────────────────

  "performConvert" should "reject unsupported extension pairs" in {
    val result = CommandExecutor.performConvert(
      newService(),
      "input.txt",
      "output.xml",
      ConversionConfig()
    )
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("Unsupported conversion")
  }

  // ── showStatus ─────────────────────────────────────────────────────────

  "showStatus" should "return false when quiet" in {
    CommandExecutor.showStatus(quietOpts) shouldBe false
  }

  // ── executeMerge ───────────────────────────────────────────────────────

  "executeMerge" should "reject unwritable cloud-like but check local paths" in {
    CommandExecutor.executeMerge(
      newService(),
      List("/tmp/a.parquet"),
      "s3://bucket/merged.parquet",
      CompressionType.Snappy,
      SchemaMode.Strict,
      quietOpts
    )
  }
}
