package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models._
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.{Try, Success, Failure}
import java.io.ByteArrayInputStream
import java.time.Instant

class ParquetServiceTest extends AnyFlatSpec with Matchers {

  // ── Test Double ──────────────────────────────────────────────────────────
  private class FakeParquetRepository(
      contentResult: Try[FileContent] = Success(defaultContent),
      schemaResult: Try[ParquetSchema] = Success(defaultSchema),
      metadataResult: Try[FileMetadata] = Success(defaultMetadata),
      validateResult: Try[List[String]] = Success(List.empty),
      writeResult: Try[Unit] = Success(()),
      streamResult: Try[Unit] = Success(()),
      statsResult: Try[FileStats] = Success(defaultStats),
      schemaFieldsResult: Try[List[FieldSummary]] = Success(
        defaultSchemaFields
      ),
      deleteResult: Try[Unit] = Success(())
  ) extends ParquetRepository {
    override def readContent(
        file: ParquetFile,
        config: ReadConfig
    ): Try[FileContent] = contentResult
    override def readSchema(file: ParquetFile): Try[ParquetSchema] =
      schemaResult
    override def readMetadata(file: ParquetFile): Try[FileMetadata] =
      metadataResult
    override def readFileInfo(
        file: ParquetFile
    ): Try[(ParquetSchema, FileMetadata)] =
      for { s <- schemaResult; m <- metadataResult } yield (s, m)
    override def validateFile(
        file: ParquetFile,
        deep: Boolean = false
    ): Try[List[String]] =
      validateResult
    override def writeContent(
        location: StorageLocation,
        data: List[Map[String, CellValue]],
        schema: Option[ParquetSchema],
        config: WriteConfig = WriteConfig()
    ): Try[Unit] = writeResult
    override def streamContent(
        file: ParquetFile,
        config: ReadConfig
    )(process: Map[String, CellValue] => Unit): Try[Long] =
      streamResult.map { _ =>
        contentResult.get.rows.foreach(process)
        contentResult.get.rows.length.toLong
      }
    override def writeContentStream(
        location: StorageLocation,
        schema: ParquetSchema,
        config: WriteConfig
    )(feed: (Map[String, CellValue] => Unit) => Unit): Try[Long] =
      writeResult.map { _ =>
        var count = 0L
        feed { _ => count += 1 }
        count
      }
    override def readStats(file: ParquetFile): Try[FileStats] = statsResult
    override def readSchemaFields(
        file: ParquetFile
    ): Try[List[FieldSummary]] = schemaFieldsResult
    override def deleteFile(location: StorageLocation): Try[Unit] = deleteResult
  }

  // ── Shared fixtures ──────────────────────────────────────────────────────
  private val defaultContent = FileContent(
    rows = List(
      Map("id" -> CellValue.I64(1L), "name" -> CellValue.Str("Alice"))
    ),
    totalRows = 1L,
    isPartial = false
  )
  private val defaultSchema = ParquetSchema(
    columns =
      List(ColumnInfo("id", "INT64", isOptional = false, 1, 0, "SNAPPY")),
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
  private val defaultSchemaFields =
    List(FieldSummary("id", "INT64", isOptional = false))

  // ── readFile ─────────────────────────────────────────────────────────────
  "ParquetService.readFile" should "return Right with populated ParquetFile" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readFile("/tmp/test.parquet")

    result.isRight shouldBe true
    result.toOption.get.content shouldBe defined
    result.toOption.get.schema shouldBe defined
    result.toOption.get.metadata shouldBe defined
  }

  it should "propagate content rows" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readFile("/tmp/test.parquet")

    result.toOption.get.content.get.rows should have length 1
    result.toOption.get.content.get.rows.head("name") shouldBe CellValue.Str(
      "Alice"
    )
  }

  it should "return Left for invalid path" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readFile("ftp://unsupported/path")
    result.isLeft shouldBe true
  }

  // ── getFileInfo ───────────────────────────────────────────────────────────
  "ParquetService.getFileInfo" should "return Right with schema and metadata but no content" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.getFileInfo("/tmp/test.parquet")

    result.isRight shouldBe true
    result.toOption.get.content shouldBe empty
    result.toOption.get.schema shouldBe defined
    result.toOption.get.metadata shouldBe defined
  }

  // ── validateFile ──────────────────────────────────────────────────────────
  "ParquetService.validateFile" should "return Right(valid) when no issues found" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.validateFile("/tmp/test.parquet")

    result.isRight shouldBe true
    result.toOption.get.isValid shouldBe true
    result.toOption.get.issues shouldBe empty
  }

  it should "return Right(invalid) when issues exist" in {
    val service = new ParquetService(
      new FakeParquetRepository(validateResult =
        Success(List("corrupted row group"))
      )
    )
    val result = service.validateFile("/tmp/test.parquet")

    result.isRight shouldBe true
    result.toOption.get.isValid shouldBe false
    result.toOption.get.issues should contain("corrupted row group")
  }

  it should "pass deep=true through to repository" in {
    var capturedDeep = false
    val repo = new FakeParquetRepository() {
      override def validateFile(
          file: ParquetFile,
          deep: Boolean = false
      ): scala.util.Try[List[String]] = {
        capturedDeep = deep
        scala.util.Success(List.empty)
      }
    }
    new ParquetService(repo).validateFile("/tmp/test.parquet", deep = true)
    capturedDeep shouldBe true
  }

  // ── getStats ──────────────────────────────────────────────────────────────
  "ParquetService.getStats" should "return Right with correct totalRows on success" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.getStats("/tmp/test.parquet")

    result.isRight shouldBe true
    result.toOption.get.totalRows shouldBe defaultStats.totalRows
  }

  it should "return Left(IOError) when readStats fails" in {
    val service = new ParquetService(
      new FakeParquetRepository(statsResult =
        Failure(new RuntimeException("stats unavailable"))
      )
    )
    val result = service.getStats("/tmp/test.parquet")

    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("stats unavailable")
  }

  it should "use exception class name when IOError cause has null message" in {
    val nullMsgEx = new RuntimeException(null.asInstanceOf[String])
    val err = ParqueteerError.IOError(nullMsgEx)
    err.userMessage should not include "null"
    err.userMessage should include("RuntimeException")
  }

  it should "return Left(InvalidFormat) for an unsupported path scheme" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.getStats("ftp://unsupported/path")

    result.isLeft shouldBe true
  }

  // ── Error propagation ────────────────────────────────────────────────────
  "ParquetService.readFile" should "propagate Left(IOError) when readContent fails" in {
    val service = new ParquetService(
      new FakeParquetRepository(contentResult =
        Failure(new RuntimeException("disk error"))
      )
    )
    val result = service.readFile("/tmp/test.parquet")
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("disk error")
  }

  it should "propagate Left(IOError) when readSchema fails" in {
    val service = new ParquetService(
      new FakeParquetRepository(schemaResult =
        Failure(new RuntimeException("schema corrupt"))
      )
    )
    val result = service.readFile("/tmp/test.parquet")
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("schema corrupt")
  }

  it should "propagate Left(IOError) when readMetadata fails" in {
    val service = new ParquetService(
      new FakeParquetRepository(metadataResult =
        Failure(new RuntimeException("metadata missing"))
      )
    )
    val result = service.readFile("/tmp/test.parquet")
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("metadata missing")
  }

  "ParquetService.getFileInfo" should "propagate Left when readSchema fails" in {
    val service = new ParquetService(
      new FakeParquetRepository(schemaResult =
        Failure(new RuntimeException("no schema"))
      )
    )
    val result = service.getFileInfo("/tmp/test.parquet")
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("no schema")
  }

  it should "propagate Left when readMetadata fails" in {
    val service = new ParquetService(
      new FakeParquetRepository(metadataResult =
        Failure(new RuntimeException("no metadata"))
      )
    )
    val result = service.getFileInfo("/tmp/test.parquet")
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("no metadata")
  }

  "ParquetService.validateFile" should "propagate Left when repository validateFile fails" in {
    val service = new ParquetService(
      new FakeParquetRepository(validateResult =
        Failure(new RuntimeException("cannot open file"))
      )
    )
    val result = service.validateFile("/tmp/test.parquet")
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("cannot open file")
  }

  "ParquetService.writeFile" should "propagate Left when writeContent fails" in {
    val service = new ParquetService(
      new FakeParquetRepository(writeResult =
        Failure(new RuntimeException("write denied"))
      )
    )
    val result =
      service.writeFile(
        "/tmp/out.parquet",
        List(Map("id" -> CellValue.I64(1L)))
      )
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("write denied")
  }

  // ── readDataFile ──────────────────────────────────────────────────────────
  "ParquetService.readDataFile" should "parse JSON file into rows" in {
    import java.nio.file.Files
    val f = java.io.File.createTempFile("parqueteer_rdf", ".json")
    f.deleteOnExit()
    Files.writeString(f.toPath, """[{"id": 1, "name": "Alice"}]""")

    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readDataFile(f.getAbsolutePath, "json")

    result.isRight shouldBe true
    result.toOption.get should have length 1
    result.toOption.get.head("name") shouldBe CellValue.Str("Alice")
  }

  it should "parse CSV file into rows" in {
    import java.nio.file.Files
    val f = java.io.File.createTempFile("parqueteer_rdf", ".csv")
    f.deleteOnExit()
    Files.writeString(f.toPath, "id,name\n1,Alice\n")

    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readDataFile(f.getAbsolutePath, "csv")

    result.isRight shouldBe true
    result.toOption.get.head("name") shouldBe CellValue.Str("Alice")
  }

  it should "return Left for unsupported format" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readDataFile("/any/file.tsv", "tsv")
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include(
      "Unsupported input format"
    )
  }

  // ── stdin / pipe support (#42) ────────────────────────────────────────────
  "ParquetService.readDataFile" should "read JSON from stdin when path is -" in {
    val json = """[{"id": 1, "name": "Alice"}]""".getBytes("UTF-8")
    val stdin = new ByteArrayInputStream(json)

    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readDataFile("-", "json", stdin)

    result.isRight shouldBe true
    result.toOption.get should have length 1
    result.toOption.get.head("name") shouldBe CellValue.Str("Alice")
  }

  it should "read CSV from stdin when path is -" in {
    val csv = "id,name\n1,Alice\n2,Bob\n".getBytes("UTF-8")
    val stdin = new ByteArrayInputStream(csv)

    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readDataFile("-", "csv", stdin)

    result.isRight shouldBe true
    result.toOption.get should have length 2
    result.toOption.get.head("name") shouldBe CellValue.Str("Alice")
  }

  it should "return Left for unsupported format from stdin" in {
    val stdin = new ByteArrayInputStream("data".getBytes("UTF-8"))
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readDataFile("-", "tsv", stdin)
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include(
      "Unsupported input format"
    )
  }

  it should "close the InputStream after reading from stdin" in {
    var closed = false
    val stdin = new ByteArrayInputStream("""[{"id":1}]""".getBytes("UTF-8")) {
      override def close(): Unit = { closed = true; super.close() }
    }
    val service = new ParquetService(new FakeParquetRepository())
    service.readDataFile("-", "json", stdin)
    closed shouldBe true
  }

  "ParquetService.readFile" should "return Left for stdin path (-)" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readFile("-")
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("stdin")
  }

  "ParquetService.parseJsonContent" should "parse JSON array" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service.parseJsonContent("""[{"x": 1}]""")
    rows should have length 1
    rows.head("x") shouldBe CellValue.I64(1L)
  }

  it should "preserve Long precision for large integers" in {
    val service = new ParquetService(new FakeParquetRepository())
    val largeId = 9876543210L
    val rows = service.parseJsonContent(s"""[{"id": $largeId}]""")
    rows.head("id") shouldBe CellValue.I64(largeId)
  }

  it should "keep Double for fractional numbers" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service.parseJsonContent("""[{"score": 9.5}]""")
    rows.head("score") shouldBe CellValue.F64(9.5)
  }

  it should "infer date string in JSON as LocalDate" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows =
      service.parseJsonContent("""[{"name": "Alice", "dob": "1990-06-15"}]""")
    rows.head("dob") shouldBe CellValue.Date(
      java.time.LocalDate.of(1990, 6, 15)
    )
  }

  it should "infer timestamp string in JSON as Instant" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows =
      service.parseJsonContent(
        """[{"event": "login", "ts": "2024-01-01T08:00:00Z"}]"""
      )
    rows.head("ts") shouldBe a[CellValue.Ts]
  }

  it should "keep JSON boolean strings as CellValue.Str (JSON booleans are already typed)" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows =
      service.parseJsonContent("""[{"note": "true", "active": true}]""")
    rows.head("note") shouldBe CellValue.Str("true")
    rows.head("active") shouldBe CellValue.Bool(true)
  }

  it should "preserve 1.0 as Double, not coerce to Long" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service.parseJsonContent("""[{"x": 1.0}]""")
    rows.head("x") shouldBe CellValue.F64(1.0)
    rows.head("x") shouldBe a[CellValue.F64]
  }

  it should "preserve 0.0 as Double" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service.parseJsonContent("""[{"x": 0.0}]""")
    rows.head("x") shouldBe a[CellValue.F64]
  }

  it should "still keep whole numbers without decimal point as Long" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service.parseJsonContent("""[{"n": 42}]""")
    rows.head("n") shouldBe CellValue.I64(42L)
    rows.head("n") shouldBe a[CellValue.I64]
  }

  it should "map integers exceeding Long.MaxValue to Dec (BigDecimal fallback)" in {
    val service = new ParquetService(new FakeParquetRepository())
    // 9223372036854775808 = Long.MaxValue + 1; no decimal point → toBigDecimal → Dec
    val rows = service.parseJsonContent("""[{"n": 9223372036854775808}]""")
    rows.head("n") shouldBe a[CellValue.Dec]
  }

  it should "parse integer-valued scientific notation (1e10) as I64 not F64" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service.parseJsonContent("""[{"n": 1e10}]""")
    rows.head("n") shouldBe CellValue.I64(10000000000L)
  }

  it should "throw for non-array JSON" in {
    val service = new ParquetService(new FakeParquetRepository())
    an[IllegalArgumentException] should be thrownBy {
      service.parseJsonContent("""{"not": "array"}""")
    }
  }

  "ParquetService.parseNdjsonContent" should "parse NDJSON lines into rows" in {
    val service = new ParquetService(new FakeParquetRepository())
    val ndjson = """{"id": 1, "name": "Alice"}
{"id": 2, "name": "Bob"}"""
    val rows = service.parseNdjsonContent(ndjson)
    rows should have length 2
    rows.head("id") shouldBe CellValue.I64(1L)
    rows.head("name") shouldBe CellValue.Str("Alice")
    rows(1)("id") shouldBe CellValue.I64(2L)
  }

  it should "skip blank lines" in {
    val service = new ParquetService(new FakeParquetRepository())
    val ndjson = """{"x": 1}

{"x": 2}
"""
    val rows = service.parseNdjsonContent(ndjson)
    rows should have length 2
  }

  it should "preserve 1.0 as Double (same logic as parseJsonContent)" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service.parseNdjsonContent("""{"v": 1.0}""")
    rows.head("v") shouldBe a[CellValue.F64]
  }

  it should "throw for a non-object NDJSON line" in {
    val service = new ParquetService(new FakeParquetRepository())
    an[IllegalArgumentException] should be thrownBy {
      service.parseNdjsonContent("""[1, 2, 3]""")
    }
  }

  it should "be reachable via readFromStdin with --input-format ndjson" in {
    val service = new ParquetService(new FakeParquetRepository())
    val stdin = new java.io.ByteArrayInputStream(
      """{"x": 42}""".getBytes("UTF-8")
    )
    val rows = service.readFromStdin("ndjson", stdin).get
    rows.head("x") shouldBe CellValue.I64(42L)
  }

  "ParquetService.parseCsvContent" should "parse CSV string with type inference" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service.parseCsvContent("a,b\n1,2\n3,4\n")
    rows should have length 2
    rows.head("a") shouldBe CellValue.I64(1L)
    rows.head("b") shouldBe CellValue.I64(2L)
  }

  it should "return empty list for empty input" in {
    val service = new ParquetService(new FakeParquetRepository())
    service.parseCsvContent("") shouldBe empty
  }

  it should "infer date strings as LocalDate" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows =
      service.parseCsvContent("name,dob\nAlice,1990-06-15\nBob,2001-12-01\n")
    rows.head("dob") shouldBe CellValue.Date(
      java.time.LocalDate.of(1990, 6, 15)
    )
    rows(1)("dob") shouldBe CellValue.Date(java.time.LocalDate.of(2001, 12, 1))
  }

  it should "infer timestamp strings as Instant" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows =
      service.parseCsvContent(
        "event,ts\nlogin,2024-01-01T08:00:00Z\nlogout,2024-01-01T09:30:00Z\n"
      )
    rows.head("ts") shouldBe a[CellValue.Ts]
  }

  it should "infer boolean strings as Boolean" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service.parseCsvContent("name,active\nAlice,true\nBob,false\n")
    rows.head("active") shouldBe CellValue.Bool(true)
    rows(1)("active") shouldBe CellValue.Bool(false)
  }

  it should "preserve leading-zero strings as String" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service.parseCsvContent("code\n007\n042\n")
    rows.head("code") shouldBe CellValue.Str("007")
    rows(1)("code") shouldBe CellValue.Str("042")
  }

  it should "parse CSV with quoted fields containing newlines" in {
    val service = new ParquetService(new FakeParquetRepository())
    val csv =
      "name,bio\n\"Alice\",\"Line one\nLine two\"\n\"Bob\",\"Single line\"\n"
    val result = service.parseCsvContent(csv)
    result should have length 2
    result.head("name") shouldBe CellValue.Str("Alice")
    result.head("bio") shouldBe CellValue.Str("Line one\nLine two")
    result(1)("name") shouldBe CellValue.Str("Bob")
  }

  // ── streamRead ────────────────────────────────────────────────────────────
  "ParquetService.streamRead" should "stream all rows via callback" in {
    val service = new ParquetService(new FakeParquetRepository())
    val collected =
      scala.collection.mutable.ListBuffer[Map[String, CellValue]]()
    val result =
      service.streamRead("/tmp/test.parquet", ReadConfig())(collected += _)
    result.isRight shouldBe true
    result.toOption.get shouldBe 1L
    collected should have length 1
    collected.head("name") shouldBe CellValue.Str("Alice")
  }

  it should "return Left for invalid path" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result =
      service.streamRead("ftp://unsupported/path", ReadConfig())(_ => ())
    result.isLeft shouldBe true
  }

  it should "return Left for stdin path" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.streamRead("-", ReadConfig())(_ => ())
    result.isLeft shouldBe true
  }

  it should "propagate Left(IOError) when streamContent fails" in {
    val service = new ParquetService(
      new FakeParquetRepository(streamResult =
        Failure(new RuntimeException("stream error"))
      )
    )
    val result = service.streamRead("/tmp/test.parquet", ReadConfig())(_ => ())
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("stream error")
  }

  it should "return Left(FilterParseError) for syntactically invalid filter" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.streamRead(
      "/tmp/test.parquet",
      ReadConfig(filter = Some("age >"))
    )(_ => ())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ParqueteerError.FilterParseError]
    result.left.toOption.get.userMessage should include("age >")
  }

  it should "return Right and invoke callback when filter is valid" in {
    val service = new ParquetService(new FakeParquetRepository())
    val collected =
      scala.collection.mutable.ListBuffer[Map[String, CellValue]]()
    val result = service.streamRead(
      "/tmp/test.parquet",
      ReadConfig(filter = Some("id = 1"))
    )(collected += _)
    result.isRight shouldBe true
  }

  it should "return Left(IOError) typed correctly for repository failure" in {
    val service = new ParquetService(
      new FakeParquetRepository(streamResult =
        Failure(new java.io.IOException("disk read error"))
      )
    )
    val result = service.streamRead("/tmp/test.parquet", ReadConfig())(_ => ())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ParqueteerError.IOError]
    result.left.toOption.get
      .asInstanceOf[ParqueteerError.IOError]
      .cause
      .getMessage shouldBe "disk read error"
  }

  // ── mergeFiles error cause ────────────────────────────────────────────────
  "ParquetService.mergeFiles" should "preserve error cause when streamContent fails" in {
    val originalCause =
      new java.io.IOException("disk read failed: sector error")
    val repo = new FakeParquetRepository(streamResult = Failure(originalCause))
    val service = new ParquetService(repo)
    val result = service.mergeFiles(
      List("/tmp/a.parquet", "/tmp/b.parquet"),
      "/tmp/out.parquet",
      WriteConfig(),
      SchemaMode.Strict
    )
    result.isLeft shouldBe true
    val err = result.left.toOption.get
    err shouldBe a[ParqueteerError.IOError]
    err.asInstanceOf[ParqueteerError.IOError].cause shouldBe originalCause
  }

  // ── executeConvert service-layer paths ────────────────────────────────────
  // (executeConvert in CliApp is private; these tests cover the service methods
  //  it delegates to, which represent the core conversion logic.)

  "parquet→json conversion (service layer)" should "readFile returns rows for formatting" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readFile("/tmp/test.parquet", ReadConfig())
    result.isRight shouldBe true
    result.toOption.get.content.isDefined shouldBe true
    val rows = result.toOption.get.content.get.rows
    rows should not be empty
    rows.head.get("name") shouldBe Some(CellValue.Str("Alice"))
  }

  it should "output valid JSON via OutputFormatter" in {
    import io.github.yusukensanta.parqueteer.core.formatters.OutputFormatter
    val service = new ParquetService(new FakeParquetRepository())
    val fileResult = service.readFile("/tmp/test.parquet", ReadConfig())
    fileResult.isRight shouldBe true
    val content = fileResult.toOption.get.content.get
    val json =
      OutputFormatter(OutputFormat.JSON, useColors = false)
        .formatContent(content, None)
    json should include("rows")
    json should include("Alice")
  }

  it should "output valid NDJSON via OutputFormatter" in {
    import io.github.yusukensanta.parqueteer.core.formatters.OutputFormatter
    val service = new ParquetService(new FakeParquetRepository())
    val content = service
      .readFile("/tmp/test.parquet", ReadConfig())
      .toOption
      .get
      .content
      .get
    val ndjson =
      OutputFormatter(OutputFormat.NDJSON, useColors = false)
        .formatContent(content, None)
    ndjson.trim should not be empty
    ndjson should include("Alice")
    ndjson.split("\n").filter(_.nonEmpty).foreach(_ should startWith("{"))
  }

  it should "output valid CSV via OutputFormatter" in {
    import io.github.yusukensanta.parqueteer.core.formatters.OutputFormatter
    val service = new ParquetService(new FakeParquetRepository())
    val content = service
      .readFile("/tmp/test.parquet", ReadConfig())
      .toOption
      .get
      .content
      .get
    val csv =
      OutputFormatter(OutputFormat.CSV, useColors = false)
        .formatContent(content, None)
    csv should include("name")
    csv should include("Alice")
  }

  "parquet→parquet conversion (service layer)" should "writeFile succeeds for data from readFile" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service
      .readFile("/tmp/test.parquet", ReadConfig())
      .toOption
      .get
      .content
      .get
      .rows
    val writeResult =
      service.writeFile("/tmp/out.parquet", rows, WriteConfig())
    writeResult.isRight shouldBe true
  }

  "json→parquet conversion (service layer)" should "readDataFile parses JSON and writeFile accepts it" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service
      .readDataFile(
        "-",
        "json",
        new ByteArrayInputStream("""[{"id":1}]""".getBytes)
      )
      .toOption
      .get
    rows should have length 1
    val writeResult = service.writeFile("/tmp/out.parquet", rows, WriteConfig())
    writeResult.isRight shouldBe true
  }

  it should "return Left for unsupported input format" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readDataFile("/tmp/file.tsv", "tsv")
    result.isLeft shouldBe true
  }

  it should "wrap IllegalArgumentException from streamContent as InvalidFormat, not IOError" in {
    val illegalArg = new IllegalArgumentException("bad column type")
    val repo = new FakeParquetRepository(streamResult = Failure(illegalArg))
    val service = new ParquetService(repo)
    val result = service.mergeFiles(
      List("/tmp/a.parquet", "/tmp/b.parquet"),
      "/tmp/out.parquet",
      WriteConfig(),
      SchemaMode.Strict
    )
    result.isLeft shouldBe true
    val err = result.left.toOption.get
    err shouldBe a[ParqueteerError.InvalidFormat]
    err.userMessage should include("bad column type")
  }

  it should "return original merge error even when partial-output delete fails" in {
    val mergeError = new RuntimeException("stream read failed")
    val repo = new FakeParquetRepository(
      streamResult = Failure(mergeError),
      deleteResult = Failure(new RuntimeException("delete also failed"))
    )
    val service = new ParquetService(repo)
    val result = service.mergeFiles(
      List("/a.parquet", "/b.parquet"),
      "/out.parquet",
      WriteConfig(),
      SchemaMode.Strict
    )

    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should not include "delete also failed"
  }

  // ── mergeFiles fail-fast on stream error ─────────────────────────────────
  "ParquetService.mergeFiles" should "stop iterating files immediately on first stream error" in {
    var filesAttempted = 0
    val repo = new FakeParquetRepository {
      override def streamContent(file: ParquetFile, config: ReadConfig)(
          process: Map[String, CellValue] => Unit
      ): scala.util.Try[Long] = {
        filesAttempted += 1
        if (filesAttempted == 1)
          scala.util.Failure(
            new RuntimeException("simulated read error on file 1")
          )
        else
          scala.util.Success(0L)
      }
    }
    val svc = new ParquetService(repo)
    val result = svc.mergeFiles(
      List("a.parquet", "b.parquet", "c.parquet"),
      "out.parquet",
      WriteConfig(),
      SchemaMode.Strict
    )
    result shouldBe a[Left[?, ?]]
    filesAttempted shouldBe 1
  }

  // ── mergeFiles union required-flag correctness ───────────────────────────
  it should "mark a column optional in union merge when it first appears in a later file" in {
    // file 1 has only column 'a'; file 2 adds column 'b' (REQUIRED in its own schema).
    // 'b' must be optional in the merged schema because file 1 lacks it.
    var callIndex = 0
    val repo = new FakeParquetRepository(
      schemaFieldsResult = Success(Nil) // unused; overridden below
    ) {
      override def readSchemaFields(
          file: ParquetFile
      ): scala.util.Try[List[FieldSummary]] = {
        val result =
          if (callIndex == 0)
            Success(List(FieldSummary("a", "INT64", isOptional = false)))
          else
            Success(
              List(
                FieldSummary("a", "INT64", isOptional = false),
                FieldSummary("b", "INT64", isOptional = false)
              )
            )
        callIndex += 1
        result
      }
    }
    var capturedSchema: Option[ParquetSchema] = None
    val capturingRepo = new FakeParquetRepository(
      schemaFieldsResult = Success(Nil)
    ) {
      val inner: ParquetRepository = repo
      override def readSchemaFields(
          file: ParquetFile
      ): scala.util.Try[List[FieldSummary]] = inner.readSchemaFields(file)
      override def writeContentStream(
          location: StorageLocation,
          schema: ParquetSchema,
          config: WriteConfig
      )(
          feed: (Map[String, CellValue] => Unit) => Unit
      ): scala.util.Try[Long] = {
        capturedSchema = Some(schema)
        Success(0L)
      }
    }
    val service = new ParquetService(capturingRepo)
    val result = service.mergeFiles(
      List("/a.parquet", "/b.parquet"),
      "/out.parquet",
      WriteConfig(),
      SchemaMode.Union
    )
    result.isRight shouldBe true
    val bCol = capturedSchema.get.columns.find(_.name == "b")
    bCol shouldBe defined
    bCol.get.isOptional shouldBe true
  }

  // ── mergeFiles compression propagation ───────────────────────────────────
  it should "pass WriteConfig compressionType to schema in writeContentStream" in {
    var capturedSchema: Option[ParquetSchema] = None
    val repo = new FakeParquetRepository(
      schemaFieldsResult =
        Success(List(FieldSummary("id", "INT64", isOptional = false)))
    ) {
      override def writeContentStream(
          location: StorageLocation,
          schema: ParquetSchema,
          config: WriteConfig
      )(
          feed: (Map[String, CellValue] => Unit) => Unit
      ): scala.util.Try[Long] = {
        capturedSchema = Some(schema)
        super.writeContentStream(location, schema, config)(feed)
      }
    }
    val service = new ParquetService(repo)
    val result = service.mergeFiles(
      List("/tmp/a.parquet", "/tmp/b.parquet"),
      "/tmp/out.parquet",
      WriteConfig(compressionType = CompressionType.Gzip),
      SchemaMode.Strict
    )
    result.isRight shouldBe true
    capturedSchema shouldBe defined
    capturedSchema.get.columns.map(_.compressionType).distinct shouldBe List(
      "GZIP"
    )
  }
}
