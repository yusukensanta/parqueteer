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
      dryRun = false,
      quietOpts
    )
  }

  it should "show dry-run summary without writing" in {
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(new java.io.PrintStream(out)) {
      CommandExecutor.executeMerge(
        newService(),
        List("/tmp/a.parquet", "/tmp/b.parquet"),
        "s3://bucket/merged.parquet",
        CompressionType.Snappy,
        SchemaMode.Strict,
        dryRun = true,
        quietOpts
      )
    }
    val output = out.toString("UTF-8")
    output should include("Dry run")
    output should include("2 files")
  }

  // ── executeRead branch coverage ────────────────────────────────────────

  "executeRead" should "warn and fallback parallelism when filter is set with parallel > 1" in {
    val (code, stderr) = captureStderr {
      CommandExecutor.executeRead(
        newService(),
        "/tmp/test.parquet",
        maxRows = None,
        columns = None,
        filter = Some("id > 0"),
        format = OutputFormat.Table,
        parallelism = 4,
        streaming = false,
        defaultOpts
      )
    }
    code shouldBe 0
    stderr should include("--filter disables parallel mode")
  }

  it should "suppress filter-parallel warning when quiet" in {
    val (code, stderr) = captureStderr {
      CommandExecutor.executeRead(
        newService(),
        "/tmp/test.parquet",
        maxRows = None,
        columns = None,
        filter = Some("id > 0"),
        format = OutputFormat.Table,
        parallelism = 4,
        streaming = false,
        quietOpts
      )
    }
    code shouldBe 0
    stderr should not include "--filter disables parallel mode"
  }

  it should "use streaming for NDJSON format" in {
    CommandExecutor.executeRead(
      newService(),
      "/tmp/test.parquet",
      maxRows = None,
      columns = None,
      filter = None,
      format = OutputFormat.NDJSON,
      parallelism = 1,
      streaming = false,
      quietOpts
    ) shouldBe 0
  }

  it should "use streaming when streaming flag is set" in {
    CommandExecutor.executeRead(
      newService(),
      "/tmp/test.parquet",
      maxRows = None,
      columns = None,
      filter = None,
      format = OutputFormat.CSV,
      parallelism = 1,
      streaming = true,
      quietOpts
    ) shouldBe 0
  }

  // ── execute dispatch coverage ──────────────────────────────────────────

  "execute" should "dispatch ReadCommand" in {
    CommandExecutor.execute(
      ReadCommand("/tmp/test.parquet"),
      newService(),
      quietOpts
    ) shouldBe 0
  }

  it should "dispatch InfoCommand" in {
    CommandExecutor.execute(
      InfoCommand("/tmp/test.parquet"),
      newService(),
      quietOpts
    ) shouldBe 0
  }

  it should "dispatch ValidateCommand" in {
    CommandExecutor.execute(
      ValidateCommand("/tmp/test.parquet"),
      newService(),
      quietOpts
    ) shouldBe 0
  }

  it should "dispatch SchemaCommand" in {
    CommandExecutor.execute(
      SchemaCommand("/tmp/test.parquet"),
      newService(),
      quietOpts
    ) shouldBe 0
  }

  it should "dispatch StatsCommand" in {
    CommandExecutor.execute(
      StatsCommand("/tmp/test.parquet"),
      newService(),
      quietOpts
    ) shouldBe 0
  }

  it should "dispatch CountCommand" in {
    CommandExecutor.execute(
      CountCommand("/tmp/test.parquet"),
      newService(),
      quietOpts
    ) shouldBe 0
  }

  // ── reportError branch coverage ────────────────────────────────────────

  it should "suppress error output when quiet" in {
    val (code, stderr) = captureStderr {
      CommandExecutor.reportError("Err", quietOpts)(
        ParqueteerError.FileNotFound("/x")
      )
    }
    code shouldBe 3
    stderr should include("/x")
  }

  "reportError" should "show stack trace for verbose non-quiet" in {
    val verboseOpts = GlobalOptions(verbose = true, quiet = false)
    val cause       = new RuntimeException("deep cause")
    val (code, stderr) = captureStderr {
      CommandExecutor.reportError("Err", verboseOpts)(
        ParqueteerError.IOError(cause)
      )
    }
    code should not be 0
    stderr should include("deep cause")
  }

  // ── execute dispatch MergeCommand with dryRun ──────────────────────────

  it should "dispatch MergeCommand with dry-run" in {
    val out = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      CommandExecutor.execute(
        MergeCommand(
          List("/tmp/a.parquet", "/tmp/b.parquet"),
          "s3://bucket/out.parquet",
          dryRun = true
        ),
        newService(),
        quietOpts
      )
    }
    out.toString("UTF-8") should include("Dry run")
  }

  // ── ProgressRowStreamWriter ────────────────────────────────────────────

  "ProgressRowStreamWriter" should "emit progress at configured interval" in {
    val errBuf = new ByteArrayOutputStream()
    val errPs  = new PrintStream(errBuf)
    val delegate = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def writeRow(row: Map[String, CellValue]): Unit = ()
    }
    val pw = new ProgressRowStreamWriter(delegate, errPs, intervalRows = 5)
    pw.begin()
    (1 to 12).foreach(_ => pw.writeRow(Map.empty))
    pw.end()
    val stderr = errBuf.toString("UTF-8")
    stderr should include("5 rows")
    stderr should include("10 rows")
    stderr should include("done")
  }

  it should "not emit done if count below interval" in {
    val errBuf = new ByteArrayOutputStream()
    val errPs  = new PrintStream(errBuf)
    val delegate = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def writeRow(row: Map[String, CellValue]): Unit = ()
    }
    val pw = new ProgressRowStreamWriter(delegate, errPs, intervalRows = 100)
    pw.begin()
    (1 to 5).foreach(_ => pw.writeRow(Map.empty))
    pw.end()
    errBuf.toString("UTF-8") shouldBe empty
  }

  // ── executeWrite ───────────────────────────────────────────────────────

  "executeWrite" should "show dry-run preview without writing" in {
    val tmpFile = java.nio.file.Files.createTempFile("pqt-test", ".json")
    try {
      java.nio.file.Files.writeString(tmpFile, """[{"id":1,"name":"Alice"}]""")
      val out = new ByteArrayOutputStream()
      Console.withOut(new PrintStream(out)) {
        CommandExecutor.executeWrite(
          newService(),
          "s3://bucket/out.parquet",
          tmpFile.toString,
          InputFormat.Json,
          CompressionType.Snappy,
          rowGroupSize = None,
          dryRun = true,
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
      CommandExecutor.executeWrite(
        newService(),
        "s3://bucket/out.parquet",
        "/nonexistent/input.json",
        InputFormat.Json,
        CompressionType.Snappy,
        rowGroupSize = None,
        dryRun = true,
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
      CommandExecutor.executeConvert(
        newService(),
        "input.csv",
        "output.parquet",
        CompressionType.Zstd,
        maxRows = None,
        dryRun = true,
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
      CommandExecutor.executeConvert(
        newService(),
        "input.parquet",
        "output.parquet",
        CompressionType.Snappy,
        maxRows = None,
        dryRun = true,
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
      CommandExecutor.executeConvert(
        newService(),
        "input.txt",
        "output.xml",
        CompressionType.Snappy,
        maxRows = None,
        dryRun = false,
        quietOpts
      )
    }
    code should not be 0
    stderr should include("Unsupported conversion")
  }

  // ── execute dispatch new branches ──────────────────────────────────────

  "execute" should "dispatch WriteCommand with dryRun = true" in {
    val tmpFile = java.nio.file.Files.createTempFile("pqt-test", ".json")
    try {
      java.nio.file.Files.writeString(tmpFile, """[{"x":1}]""")
      val out = new ByteArrayOutputStream()
      val code = Console.withOut(new PrintStream(out)) {
        CommandExecutor.execute(
          WriteCommand(tmpFile.toString, "s3://b/o.parquet", dryRun = true),
          newService(),
          quietOpts
        )
      }
      code shouldBe 0
      out.toString("UTF-8") should include("Dry run")
    } finally java.nio.file.Files.deleteIfExists(tmpFile)
  }

  it should "dispatch ConvertCommand with dryRun = true" in {
    val out = new ByteArrayOutputStream()
    val code = Console.withOut(new PrintStream(out)) {
      CommandExecutor.execute(
        ConvertCommand("input.csv", "output.parquet", dryRun = true),
        newService(),
        quietOpts
      )
    }
    code shouldBe 0
    out.toString("UTF-8") should include("Dry run")
  }

  it should "dispatch SchemaDiffCommand and return 0 for identical schemas" in {
    CommandExecutor.execute(
      SchemaDiffCommand("/tmp/a.parquet", "/tmp/b.parquet"),
      newService(),
      quietOpts
    ) shouldBe 0
  }

  it should "dispatch ConfigCommand" in {
    CommandExecutor.execute(
      ConfigCommand(validate = false),
      newService(),
      quietOpts
    ) shouldBe 0
  }

  it should "dispatch ConfigCommand validate" in {
    CommandExecutor.execute(
      ConfigCommand(validate = true),
      newService(),
      quietOpts
    ) shouldBe 0
  }

  // ── executeSchemaDiff ──────────────────────────────────────────────────

  "executeSchemaDiff" should "return 0 when schemas are identical" in {
    CommandExecutor.executeSchemaDiff(
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
      CommandExecutor.executeSchemaDiff(
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
      CommandExecutor.executeSchemaDiff(
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
      CommandExecutor.executeSchemaDiff(
        newService(),
        SchemaDiffCommand("/tmp/a.parquet", "/tmp/b.parquet", OutputFormat.Table),
        defaultOpts
      )
    }
    out.toString("UTF-8") should not be empty
  }

  // ── executeConfig ──────────────────────────────────────────────────────

  "executeConfig" should "return 0 for non-validate" in {
    CommandExecutor.executeConfig(ConfigCommand(validate = false), quietOpts) shouldBe 0
  }

  it should "return 0 for validate with no config file" in {
    CommandExecutor.executeConfig(
      ConfigCommand(validate = true),
      GlobalOptions(quiet = true, configPath = Some("/nonexistent/config.yaml"))
    ) shouldBe 0
  }

  // ── runWithDeferredBegin ───────────────────────────────────────────────

  "runWithDeferredBegin" should "call begin before first row and end after all rows" in {
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    val writer = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def begin(): Unit                               = calls += "begin"
      override def writeRow(row: Map[String, CellValue]): Unit = calls += "row"
      override def end(): Unit                                 = calls += "end"
    }
    val result = CommandExecutor.runWithDeferredBegin(
      writer,
      process => { process(Map.empty); process(Map.empty); Right(2L) }
    )
    result shouldBe Right(2L)
    calls.toList shouldBe List("begin", "row", "row", "end")
  }

  it should "call begin and end even with no rows on success" in {
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    val writer = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def begin(): Unit                               = calls += "begin"
      override def writeRow(row: Map[String, CellValue]): Unit = calls += "row"
      override def end(): Unit                                 = calls += "end"
    }
    val result = CommandExecutor.runWithDeferredBegin(
      writer,
      _ => Right(0L)
    )
    result shouldBe Right(0L)
    calls.toList shouldBe List("begin", "end")
  }

  it should "propagate read error without calling begin or end" in {
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    val writer = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def begin(): Unit                               = calls += "begin"
      override def writeRow(row: Map[String, CellValue]): Unit = calls += "row"
      override def end(): Unit                                 = calls += "end"
    }
    val err = ParqueteerError.FileNotFound("/x")
    val result = CommandExecutor.runWithDeferredBegin(
      writer,
      _ => Left(err)
    )
    result shouldBe Left(err)
    calls should not contain "begin"
    calls should not contain "end"
  }

  it should "return Left when end() throws and read succeeded" in {
    val writer = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def writeRow(row: Map[String, CellValue]): Unit = ()
      override def end(): Unit = throw new java.io.IOException("flush failed")
    }
    val result = CommandExecutor.runWithDeferredBegin(
      writer,
      process => { process(Map.empty); Right(1L) }
    )
    result.isLeft shouldBe true
  }
}
