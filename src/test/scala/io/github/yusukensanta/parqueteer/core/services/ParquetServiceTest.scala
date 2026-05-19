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
      streamResult: Try[Unit] = Success(())
  ) extends ParquetRepository {
    override def readContent(
        file: ParquetFile,
        config: ReadConfig
    ): Try[FileContent] = contentResult
    override def readSchema(file: ParquetFile): Try[ParquetSchema] =
      schemaResult
    override def readMetadata(file: ParquetFile): Try[FileMetadata] =
      metadataResult
    override def validateFile(file: ParquetFile): Try[List[String]] =
      validateResult
    override def writeContent(
        location: StorageLocation,
        data: List[Map[String, Any]],
        schema: Option[ParquetSchema],
        config: WriteConfig = WriteConfig()
    ): Try[Unit] = writeResult
    override def streamContent(
        file: ParquetFile,
        config: ReadConfig
    )(process: Map[String, Any] => Unit): Try[Long] =
      streamResult.map { _ =>
        contentResult.get.rows.foreach(process)
        contentResult.get.rows.length.toLong
      }
    override def writeContentStream(
        location: StorageLocation,
        schema: ParquetSchema,
        config: WriteConfig
    )(feed: (Map[String, Any] => Unit) => Unit): Try[Long] =
      writeResult.map { _ =>
        var count = 0L
        feed { _ => count += 1 }
        count
      }
  }

  // ── Shared fixtures ──────────────────────────────────────────────────────
  private val defaultContent = FileContent(
    rows = List(Map("id" -> 1L, "name" -> "Alice")),
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
    result.toOption.get.content.get.rows.head("name") shouldBe "Alice"
  }

  it should "return Left for invalid path" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readFile("ftp://unsupported/path")
    result.isLeft shouldBe true
  }

  // ── getFileInfo ───────────────────────────────────────────────────────────
  "ParquetService.getFileInfo" should "return Success with schema and metadata but no content" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.getFileInfo("/tmp/test.parquet")

    result.isSuccess shouldBe true
    result.get.content shouldBe empty
    result.get.schema shouldBe defined
    result.get.metadata shouldBe defined
  }

  // ── validateFile ──────────────────────────────────────────────────────────
  "ParquetService.validateFile" should "return valid result when no issues found" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.validateFile("/tmp/test.parquet")

    result.isSuccess shouldBe true
    result.get.isValid shouldBe true
    result.get.issues shouldBe empty
  }

  it should "return invalid result when issues exist" in {
    val service = new ParquetService(
      new FakeParquetRepository(validateResult =
        Success(List("corrupted row group"))
      )
    )
    val result = service.validateFile("/tmp/test.parquet")

    result.isSuccess shouldBe true
    result.get.isValid shouldBe false
    result.get.issues should contain("corrupted row group")
  }

  // ── convertFile ───────────────────────────────────────────────────────────
  "ParquetService.convertFile" should "succeed for parquet-to-parquet" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.convertFile("/in.parquet", "/tmp/out_convert.parquet")
    result.isSuccess shouldBe true
  }

  it should "succeed for parquet-to-json" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.convertFile("/in.parquet", "/tmp/out_convert.json")
    result.isSuccess shouldBe true
  }

  it should "succeed for parquet-to-csv" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.convertFile("/in.parquet", "/tmp/out_convert.csv")
    result.isSuccess shouldBe true
  }

  // ── formatContent ─────────────────────────────────────────────────────────
  "ParquetService.formatContent" should "format with Table format" in {
    val service = new ParquetService(new FakeParquetRepository())
    val file = ParquetFile(
      LocalPath("/tmp/test.parquet"),
      content = Some(defaultContent)
    )
    service.formatContent(file, OutputFormat.Table) should include("Alice")
  }

  it should "format with JSON format" in {
    val service = new ParquetService(new FakeParquetRepository())
    val file = ParquetFile(
      LocalPath("/tmp/test.parquet"),
      content = Some(defaultContent)
    )
    val result = service.formatContent(file, OutputFormat.JSON)
    result should include("{")
    result should include("Alice")
  }

  it should "return 'No content available' when file has no content" in {
    val service = new ParquetService(new FakeParquetRepository())
    val file = ParquetFile(LocalPath("/tmp/test.parquet"))
    service.formatContent(
      file,
      OutputFormat.Table
    ) shouldBe "No content available"
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

  "ParquetService.getFileInfo" should "propagate Failure when readSchema fails" in {
    val service = new ParquetService(
      new FakeParquetRepository(schemaResult =
        Failure(new RuntimeException("no schema"))
      )
    )
    val result = service.getFileInfo("/tmp/test.parquet")
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("no schema")
  }

  it should "propagate Failure when readMetadata fails" in {
    val service = new ParquetService(
      new FakeParquetRepository(metadataResult =
        Failure(new RuntimeException("no metadata"))
      )
    )
    val result = service.getFileInfo("/tmp/test.parquet")
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("no metadata")
  }

  "ParquetService.validateFile" should "propagate Failure when repository validateFile fails" in {
    val service = new ParquetService(
      new FakeParquetRepository(validateResult =
        Failure(new RuntimeException("cannot open file"))
      )
    )
    val result = service.validateFile("/tmp/test.parquet")
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("cannot open file")
  }

  "ParquetService.writeFile" should "propagate Failure when writeContent fails" in {
    val service = new ParquetService(
      new FakeParquetRepository(writeResult =
        Failure(new RuntimeException("write denied"))
      )
    )
    val result = service.writeFile("/tmp/out.parquet", List(Map("id" -> 1L)))
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("write denied")
  }

  // ── CSV/JSON → Parquet conversion ─────────────────────────────────────────

  private class CapturingRepository extends ParquetRepository {
    var lastWrittenData: List[Map[String, Any]] = List.empty
    override def readContent(
        file: ParquetFile,
        config: ReadConfig
    ): Try[FileContent] = Success(defaultContent)
    override def readSchema(file: ParquetFile): Try[ParquetSchema] = Success(
      defaultSchema
    )
    override def readMetadata(file: ParquetFile): Try[FileMetadata] = Success(
      defaultMetadata
    )
    override def validateFile(file: ParquetFile): Try[List[String]] = Success(
      List.empty
    )
    override def writeContent(
        location: StorageLocation,
        data: List[Map[String, Any]],
        schema: Option[ParquetSchema],
        config: WriteConfig = WriteConfig()
    ): Try[Unit] = {
      lastWrittenData = data
      Success(())
    }
    override def writeContentStream(
        location: StorageLocation,
        schema: ParquetSchema,
        config: WriteConfig
    )(feed: (Map[String, Any] => Unit) => Unit): Try[Long] = {
      val buf = scala.collection.mutable.ListBuffer.empty[Map[String, Any]]
      feed { row => buf += row }
      lastWrittenData = buf.toList
      Success(buf.size.toLong)
    }
  }

  "ParquetService.convertFile" should "succeed for json-to-parquet and pass parsed data to repository" in {
    import java.nio.file.Files
    val jsonFile = java.io.File.createTempFile("parqueteer_test_input", ".json")
    jsonFile.deleteOnExit()
    Files.writeString(
      jsonFile.toPath,
      """[{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}]"""
    )

    val repo = new CapturingRepository()
    val service = new ParquetService(repo)
    val result = service.convertFile(
      jsonFile.getAbsolutePath,
      "/tmp/parqueteer_test_out.parquet"
    )

    result.isSuccess shouldBe true
    repo.lastWrittenData should have length 2
    repo.lastWrittenData.head("name") shouldBe "Alice"
    repo.lastWrittenData(1)("name") shouldBe "Bob"
    repo.lastWrittenData.head("id") shouldBe 1L
    repo.lastWrittenData(1)("id") shouldBe 2L
  }

  it should "succeed for csv-to-parquet and pass parsed data to repository" in {
    import java.nio.file.Files
    val csvFile = java.io.File.createTempFile("parqueteer_test_input", ".csv")
    csvFile.deleteOnExit()
    Files.writeString(csvFile.toPath, "id,name\n1,Alice\n2,Bob\n")

    val repo = new CapturingRepository()
    val service = new ParquetService(repo)
    val result = service.convertFile(
      csvFile.getAbsolutePath,
      "/tmp/parqueteer_test_out2.parquet"
    )

    result.isSuccess shouldBe true
    repo.lastWrittenData should have length 2
    repo.lastWrittenData.head("name") shouldBe "Alice"
  }

  it should "fail for json-to-parquet when input file does not exist" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.convertFile(
      "/tmp/nonexistent_parqueteer.json",
      "/tmp/out.parquet"
    )
    result.isFailure shouldBe true
  }

  it should "fail for json-to-parquet when input is not a JSON array" in {
    import java.nio.file.Files
    val badFile = java.io.File.createTempFile("parqueteer_bad_input", ".json")
    badFile.deleteOnExit()
    Files.writeString(badFile.toPath, """{"not": "an array"}""")

    val service = new ParquetService(new FakeParquetRepository())
    val result =
      service.convertFile(badFile.getAbsolutePath, "/tmp/out.parquet")
    result.isFailure shouldBe true
  }

  // ── readDataFile ──────────────────────────────────────────────────────────
  "ParquetService.readDataFile" should "parse JSON file into rows" in {
    import java.nio.file.Files
    val f = java.io.File.createTempFile("parqueteer_rdf", ".json")
    f.deleteOnExit()
    Files.writeString(f.toPath, """[{"id": 1, "name": "Alice"}]""")

    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readDataFile(f.getAbsolutePath, "json")

    result.isSuccess shouldBe true
    result.get should have length 1
    result.get.head("name") shouldBe "Alice"
  }

  it should "parse CSV file into rows" in {
    import java.nio.file.Files
    val f = java.io.File.createTempFile("parqueteer_rdf", ".csv")
    f.deleteOnExit()
    Files.writeString(f.toPath, "id,name\n1,Alice\n")

    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readDataFile(f.getAbsolutePath, "csv")

    result.isSuccess shouldBe true
    result.get.head("name") shouldBe "Alice"
  }

  it should "return Failure for unsupported format" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readDataFile("/any/file.tsv", "tsv")
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("Unsupported input format")
  }

  // ── stdin / pipe support (#42) ────────────────────────────────────────────
  "ParquetService.readDataFile" should "read JSON from stdin when path is -" in {
    val json = """[{"id": 1, "name": "Alice"}]""".getBytes("UTF-8")
    val stdin = new ByteArrayInputStream(json)

    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readDataFile("-", "json", stdin)

    result.isSuccess shouldBe true
    result.get should have length 1
    result.get.head("name") shouldBe "Alice"
  }

  it should "read CSV from stdin when path is -" in {
    val csv = "id,name\n1,Alice\n2,Bob\n".getBytes("UTF-8")
    val stdin = new ByteArrayInputStream(csv)

    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readDataFile("-", "csv", stdin)

    result.isSuccess shouldBe true
    result.get should have length 2
    result.get.head("name") shouldBe "Alice"
  }

  it should "return Failure for unsupported format from stdin" in {
    val stdin = new ByteArrayInputStream("data".getBytes("UTF-8"))
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readDataFile("-", "tsv", stdin)
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("Unsupported input format")
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
    rows.head("x") shouldBe 1L
  }

  it should "preserve Long precision for large integers" in {
    val service = new ParquetService(new FakeParquetRepository())
    val largeId = 9876543210L
    val rows = service.parseJsonContent(s"""[{"id": $largeId}]""")
    rows.head("id") shouldBe largeId
  }

  it should "keep Double for fractional numbers" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service.parseJsonContent("""[{"score": 9.5}]""")
    rows.head("score") shouldBe 9.5
  }

  it should "throw for non-array JSON" in {
    val service = new ParquetService(new FakeParquetRepository())
    an[IllegalArgumentException] should be thrownBy {
      service.parseJsonContent("""{"not": "array"}""")
    }
  }

  "ParquetService.parseCsvContent" should "parse CSV string" in {
    val service = new ParquetService(new FakeParquetRepository())
    val rows = service.parseCsvContent("a,b\n1,2\n3,4\n")
    rows should have length 2
    rows.head("a") shouldBe "1"
  }

  it should "return empty list for empty input" in {
    val service = new ParquetService(new FakeParquetRepository())
    service.parseCsvContent("") shouldBe empty
  }

  it should "parse CSV with quoted fields containing newlines" in {
    val service = new ParquetService(new FakeParquetRepository())
    val csv =
      "name,bio\n\"Alice\",\"Line one\nLine two\"\n\"Bob\",\"Single line\"\n"
    val result = service.parseCsvContent(csv)
    result should have length 2
    result.head("name") shouldBe "Alice"
    result.head("bio") shouldBe "Line one\nLine two"
    result(1)("name") shouldBe "Bob"
  }

  // ── streamRead ────────────────────────────────────────────────────────────
  "ParquetService.streamRead" should "stream all rows via callback" in {
    val service = new ParquetService(new FakeParquetRepository())
    val collected = scala.collection.mutable.ListBuffer[Map[String, Any]]()
    val result =
      service.streamRead("/tmp/test.parquet", ReadConfig())(collected += _)
    result.isRight shouldBe true
    result.toOption.get shouldBe 1L
    collected should have length 1
    collected.head("name") shouldBe "Alice"
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
}
