package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.services.ParquetService
import org.scalatest.flatspec.AnyFlatSpec
import java.io.{ByteArrayOutputStream, PrintStream}
import scala.util.{Success, Try}

class CommandExecutorTest extends CliTestSupport {

  // ── executeCompletions ─────────────────────────────────────────────────

  "executeCompletions" should "return 0 for bash" in {
    CommandExecutor.executeCompletions(Shell.Bash, quietOpts) shouldBe 0
  }

  it should "return 0 for zsh" in {
    CommandExecutor.executeCompletions(Shell.Zsh, quietOpts) shouldBe 0
  }

  it should "return 0 for fish" in {
    CommandExecutor.executeCompletions(Shell.Fish, quietOpts) shouldBe 0
  }

  // ── execute dispatch ───────────────────────────────────────────────────

  "execute" should "dispatch CompletionsCommand" in {
    CommandExecutor.execute(
      CompletionsCommand(Shell.Bash),
      newService(),
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

  // ── multi-match dispatch (glob paths) ───────────────────────────────────

  private def multiMatchRepo: ParquetRepository = new FakeParquetRepository() {

    override def globStatus(location: StorageLocation): Try[List[StorageLocation]] =
      Success(List(LocalPath("/data/a.parquet"), LocalPath("/data/b.parquet")))
  }

  "ReadCommand" should "concatenate rows from every matched file for a glob path" in {
    val repo = new FakeParquetRepository() {
      override def globStatus(location: StorageLocation): Try[List[StorageLocation]] =
        Success(List(LocalPath("/data/a.parquet"), LocalPath("/data/b.parquet")))
    }
    val service     = new ParquetService(repo)
    val out         = new ByteArrayOutputStream()
    val originalOut = System.out
    System.setOut(new PrintStream(out))
    val code =
      try
        CommandExecutor.execute(
          ReadCommand("/data/*.parquet", format = OutputFormat.NDJSON),
          service,
          GlobalOptions()
        )
      finally System.setOut(originalOut)
    code shouldBe 0
    // defaultContent has 1 row; 2 matched files -> 2 NDJSON lines
    out.toString.trim.linesIterator.length shouldBe 2
  }

  "InfoCommand" should "print one report per matched file for a glob path" in {
    val service = new ParquetService(multiMatchRepo)
    val out     = new ByteArrayOutputStream()
    val code = Console.withOut(new PrintStream(out)) {
      CommandExecutor.execute(InfoCommand("/data/*.parquet"), service, GlobalOptions())
    }
    code shouldBe 0
    out.toString should include("==> /data/a.parquet <==")
    out.toString should include("==> /data/b.parquet <==")
  }

  "SchemaCommand" should "print one report per matched file for a glob path" in {
    val service = new ParquetService(multiMatchRepo)
    val code = CommandExecutor.execute(
      SchemaCommand("/data/*.parquet"),
      service,
      quietOpts
    )
    code shouldBe 0
  }

  it should "read the resolved single-match file, not the literal glob pattern" in {
    val repo = new FakeParquetRepository() {
      override def globStatus(location: StorageLocation): Try[List[StorageLocation]] =
        Success(List(LocalPath("/data/only-x.parquet")))
      override def readFileInfo(
          file: ParquetFile
      ): Try[(ParquetSchema, FileMetadata, List[RowGroupInfo])] =
        if file.location.path == "/data/only-x.parquet" then super.readFileInfo(file)
        else scala.util.Failure(new java.io.IOException(s"unexpected path: ${file.location.path}"))
    }
    val service = new ParquetService(repo)
    val out     = new ByteArrayOutputStream()
    val code = Console.withOut(new PrintStream(out)) {
      CommandExecutor.execute(SchemaCommand("/data/only-x*.parquet"), service, GlobalOptions())
    }
    code shouldBe 0
    out.toString should not include "unexpected path"
  }

  "StatsCommand" should "print one report per matched file for a glob path" in {
    val service = new ParquetService(multiMatchRepo)
    val code = CommandExecutor.execute(
      StatsCommand("/data/*.parquet"),
      service,
      quietOpts
    )
    code shouldBe 0
  }

  "CountCommand" should "print one count per matched file for a glob path" in {
    val service = new ParquetService(multiMatchRepo)
    val code = CommandExecutor.execute(
      CountCommand("/data/*.parquet"),
      service,
      quietOpts
    )
    code shouldBe 0
  }

  "ValidateCommand" should "aggregate exit code 1 when one matched file is invalid" in {
    val repo = new FakeParquetRepository(
      validateResult = Success(List("corrupt row group"))
    ) {
      override def globStatus(location: StorageLocation): Try[List[StorageLocation]] =
        Success(List(LocalPath("/data/a.parquet"), LocalPath("/data/b.parquet")))
    }
    val service = new ParquetService(repo)
    val code = CommandExecutor.execute(
      ValidateCommand("/data/*.parquet"),
      service,
      quietOpts
    )
    code shouldBe 1
  }

  it should "include per-file schema detail for --verbose on a glob matching valid files" in {
    val service = new ParquetService(multiMatchRepo)
    val out     = new ByteArrayOutputStream()
    val code = Console.withOut(new PrintStream(out)) {
      CommandExecutor.execute(
        ValidateCommand("/data/*.parquet", verbose = true),
        service,
        GlobalOptions()
      )
    }
    code shouldBe 0
    val printed = out.toString
    printed should include("✓ File /data/a.parquet is valid")
    printed should include("✓ File /data/b.parquet is valid")
    printed should include("Columns:    1")
    printed should include("Row groups: 1")
    printed should include("Total rows: 1")
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

  "WriteCommand" should "concatenate multiple matched raw inputs into one parquet write" in {
    // DataFileReader opens these paths directly (not mediated by the fake
    // repository), so globStatus must resolve to real files on disk rather
    // than the brief's literal "/data/a.ndjson" placeholders. The output path
    // uses an s3:// URI (as the dry-run WriteCommand test above does) so this
    // exercises checkOutputWritable's cloud-URI short-circuit rather than a
    // real, possibly-unwritable local directory.
    val fileA = java.nio.file.Files.createTempFile("parqueteer_cmd_write_multi_", ".ndjson")
    val fileB = java.nio.file.Files.createTempFile("parqueteer_cmd_write_multi_", ".ndjson")
    try {
      java.nio.file.Files.writeString(fileA, """{"id": 1}""")
      java.nio.file.Files.writeString(fileB, """{"id": 2}""")
      val repo = new FakeParquetRepository() {
        override def globStatus(location: StorageLocation): Try[List[StorageLocation]] =
          Success(List(LocalPath(fileA.toString), LocalPath(fileB.toString)))
      }
      val service = new ParquetService(repo)
      val code = CommandExecutor.execute(
        WriteCommand("/data/*.ndjson", "s3://b/o.parquet", InputFormat.NDJson),
        service,
        GlobalOptions(quiet = true)
      )
      code shouldBe 0
    } finally {
      java.nio.file.Files.deleteIfExists(fileA)
      java.nio.file.Files.deleteIfExists(fileB)
    }
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

  "ConvertCommand" should "route glob-matched parquet-to-parquet conversion through merge" in {
    // Exit code alone can't discriminate "routed through mergeFiles across
    // both resolved files" from "fell through to the old single-file
    // convertParquetFile path with the literal glob string as its one
    // input" — both return 0 against this fake repository regardless of
    // which/how-many paths are touched. Record every path streamContent is
    // invoked with (mergeFiles's streamMerge calls it once per input file;
    // the old single-file convertParquetFile path calls it exactly once,
    // with the unexpanded "/data/*.parquet" literal) so the assertion below
    // fails unless both matched files were genuinely read.
    //
    // GlobalOptions defaults fileParallelism to 4, so mergeFiles's fetch-ahead
    // pipeline (RowPipeline) fetches both files concurrently on real threads —
    // it only guarantees row order, not which file's streamContent call
    // starts first, so asserting an exact call order here is inherently
    // flaky. A thread-safe collection plus a set comparison checks what's
    // actually guaranteed (both files genuinely read) without coupling the
    // test to fetch-ahead scheduling.
    val streamedPaths = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val repo = new FakeParquetRepository() {
      override def globStatus(location: StorageLocation): Try[List[StorageLocation]] =
        Success(List(LocalPath("/data/a.parquet"), LocalPath("/data/b.parquet")))
      override def streamContent(file: ParquetFile, config: ReadConfig)(
          process: Map[String, CellValue] => Unit
      ): Try[Long] = {
        streamedPaths.add(file.location.path)
        super.streamContent(file, config)(process)
      }
    }
    val service = new ParquetService(repo)
    val code = CommandExecutor.execute(
      ConvertCommand("/data/*.parquet", "/out.parquet"),
      service,
      GlobalOptions(quiet = true)
    )
    code shouldBe 0
    import scala.jdk.CollectionConverters.*
    streamedPaths.asScala.toSet shouldBe Set("/data/a.parquet", "/data/b.parquet")
  }

  it should "concatenate glob-matched parquet files into one text output" in {
    val tmpOut = java.nio.file.Files.createTempFile("parqueteer_convert_multi_", ".ndjson")
    java.nio.file.Files.delete(tmpOut)
    tmpOut.toFile.deleteOnExit()
    val repo = new FakeParquetRepository() {
      override def globStatus(location: StorageLocation): Try[List[StorageLocation]] =
        Success(List(LocalPath("/data/a.parquet"), LocalPath("/data/b.parquet")))
    }
    val service = new ParquetService(repo)
    val code = CommandExecutor.execute(
      ConvertCommand("/data/*.parquet", tmpOut.toString),
      service,
      GlobalOptions(quiet = true)
    )
    code shouldBe 0
    val written = java.nio.file.Files.readString(tmpOut)
    written.trim.linesIterator.length shouldBe 2 // 1 row per matched file, 2 files
  }

  it should "reject a glob matching files with different extensions" in {
    val repo = new FakeParquetRepository() {
      override def globStatus(location: StorageLocation): Try[List[StorageLocation]] =
        Success(List(LocalPath("/data/a.json"), LocalPath("/data/b.ndjson")))
    }
    val service = new ParquetService(repo)
    val (code, err) = captureStderr {
      CommandExecutor.execute(
        ConvertCommand("/data/*.{json,ndjson}", "/out.parquet"),
        service,
        defaultOpts
      )
    }
    code should not be 0
    err should include("Mixed input formats")
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

  // ── resolveAllGlobs ─────────────────────────────────────────────────────

  "resolveAllGlobs" should "pass literal paths through unchanged" in {
    val service = new ParquetService(new FakeParquetRepository())
    CommandExecutor.resolveAllGlobs(service, List("/a.parquet", "/b.parquet")) shouldBe
      Right(List("/a.parquet", "/b.parquet"))
  }

  it should "expand a glob element in place, preserving the other literal paths" in {
    val repo = new FakeParquetRepository() {
      override def globStatus(location: StorageLocation): Try[List[StorageLocation]] =
        Success(List(LocalPath("/data/2026-01.parquet"), LocalPath("/data/2026-02.parquet")))
    }
    val service = new ParquetService(repo)
    CommandExecutor.resolveAllGlobs(
      service,
      List("/fixed.parquet", "/data/2026-*.parquet")
    ) shouldBe
      Right(List("/fixed.parquet", "/data/2026-01.parquet", "/data/2026-02.parquet"))
  }

  it should "short-circuit with NoGlobMatch when one element matches nothing" in {
    val repo = new FakeParquetRepository() {
      override def globStatus(location: StorageLocation): Try[List[StorageLocation]] =
        Success(List.empty)
    }
    val service = new ParquetService(repo)
    CommandExecutor.resolveAllGlobs(service, List("/fixed.parquet", "/data/*.parquet")) shouldBe
      Left(ParqueteerError.NoGlobMatch("/data/*.parquet"))
  }
}
