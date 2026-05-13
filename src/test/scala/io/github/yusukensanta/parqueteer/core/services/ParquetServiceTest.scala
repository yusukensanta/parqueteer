package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models._
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.{Try, Success}
import java.time.Instant

class ParquetServiceTest extends AnyFlatSpec with Matchers {

  // ── Test Double ──────────────────────────────────────────────────────────
  private class FakeParquetRepository(
      contentResult: Try[FileContent] = Success(defaultContent),
      schemaResult: Try[ParquetSchema] = Success(defaultSchema),
      metadataResult: Try[FileMetadata] = Success(defaultMetadata),
      validateResult: Try[List[String]] = Success(List.empty),
      writeResult: Try[Unit] = Success(())
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
  "ParquetService.readFile" should "return Success with populated ParquetFile" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readFile("/tmp/test.parquet")

    result.isSuccess shouldBe true
    result.get.content shouldBe defined
    result.get.schema shouldBe defined
    result.get.metadata shouldBe defined
  }

  it should "propagate content rows" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readFile("/tmp/test.parquet")

    result.get.content.get.rows should have length 1
    result.get.content.get.rows.head("name") shouldBe "Alice"
  }

  it should "return Failure for invalid path" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.readFile("ftp://unsupported/path")
    result.isFailure shouldBe true
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

  it should "fail for unsupported csv-to-parquet conversion" in {
    val service = new ParquetService(new FakeParquetRepository())
    val result = service.convertFile("/in.csv", "/out.parquet")
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("Cannot convert")
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
}
