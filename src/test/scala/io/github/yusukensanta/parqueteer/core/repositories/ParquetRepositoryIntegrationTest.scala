package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models._
import org.scalatest.Tag
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files

object IntegrationTest extends Tag("IntegrationTest")

class ParquetRepositoryIntegrationTest extends AnyFlatSpec with Matchers {

  private val repo = new ParquetRepository()

  private def tempFile(): java.io.File = {
    val f = Files.createTempFile("parqueteer_it_", ".parquet").toFile
    f.delete() // Hadoop refuses to overwrite existing files; free the path
    f.deleteOnExit()
    f
  }

  private val sampleData: List[Map[String, Any]] = List(
    Map("id" -> 1L, "name" -> "Alice", "score" -> 95.5),
    Map("id" -> 2L, "name" -> "Bob", "score" -> 87.3),
    Map("id" -> 3L, "name" -> "Charlie", "score" -> 92.1)
  )

  // ── Compression roundtrips ──────────────────────────────────────────────

  "ParquetRepository" should "write and read back data with Snappy compression" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo
      .writeContent(
        loc,
        sampleData,
        None,
        WriteConfig(compressionType = CompressionType.Snappy)
      )
      .isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    result.get.rows should have length 3
    result.get.totalRows shouldBe 3L
    result.get.rows.head.keys should contain allOf ("id", "name", "score")
  }

  // TODO(human): implement Gzip compression roundtrip
  // Write sampleData with CompressionType.Gzip to a tempFile(), then read it back.
  // Assert: write succeeds, rows length == 3, at least one name value is correct.
  it should "write and read back data with Gzip compression" taggedAs IntegrationTest in {
    pending
  }

  it should "write and read back data with Uncompressed" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo
      .writeContent(
        loc,
        sampleData,
        None,
        WriteConfig(compressionType = CompressionType.Uncompressed)
      )
      .isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    result.get.rows should have length 3
  }

  it should "write and read back data with Zstd compression" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo
      .writeContent(
        loc,
        sampleData,
        None,
        WriteConfig(compressionType = CompressionType.Zstd)
      )
      .isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    result.get.rows should have length 3
  }

  // ── Schema ─────────────────────────────────────────────────────────────

  it should "read schema columns from a written file" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.readSchema(ParquetFile(loc))
    result.isSuccess shouldBe true
    result.get.columns.map(_.name) should contain allOf ("id", "name", "score")
    result.get.totalRowCount shouldBe 3L
    result.get.rowGroupCount shouldBe 1L
  }

  // ── Metadata ───────────────────────────────────────────────────────────

  it should "read metadata with non-zero file size" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.readMetadata(ParquetFile(loc))
    result.isSuccess shouldBe true
    result.get.fileSize should be > 0L
    result.get.createdAt shouldBe defined
  }

  // ── Validation ─────────────────────────────────────────────────────────

  it should "report no issues for a valid written file" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.validateFile(ParquetFile(loc))
    result.isSuccess shouldBe true
    result.get shouldBe empty
  }

  it should "report file-not-found issue for missing path" taggedAs IntegrationTest in {
    val loc = LocalPath("/tmp/parqueteer_no_such_file_xyz.parquet")
    val result = repo.validateFile(ParquetFile(loc))
    result.isSuccess shouldBe true
    result.get should contain("File does not exist")
  }

  // ── ReadConfig ─────────────────────────────────────────────────────────

  it should "respect maxRows limit and mark content as partial" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result =
      repo.readContent(ParquetFile(loc), ReadConfig(maxRows = Some(2L)))
    result.isSuccess shouldBe true
    result.get.rows should have length 2
    result.get.isPartial shouldBe true
  }

  // ── Edge cases ─────────────────────────────────────────────────────────

  it should "fail to write empty data (no schema to infer)" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    val result = repo.writeContent(loc, List.empty, None)
    result.isFailure shouldBe true
  }
}
