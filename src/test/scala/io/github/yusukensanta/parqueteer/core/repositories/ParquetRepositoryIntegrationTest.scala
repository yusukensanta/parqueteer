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

  it should "return only requested columns (column projection)" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.readContent(
      ParquetFile(loc),
      ReadConfig(columns = Some(List("id", "name")))
    )
    result.isSuccess shouldBe true
    result.get.rows should have length 3
    result.get.rows.foreach { row =>
      row.keys should contain allOf ("id", "name")
      row.keys should not contain "score"
    }
  }

  it should "return all columns when columns is None" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    result.get.rows.head.keys should contain allOf ("id", "name", "score")
  }

  it should "return single projected column" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.readContent(
      ParquetFile(loc),
      ReadConfig(columns = Some(List("name")))
    )
    result.isSuccess shouldBe true
    result.get.rows.map(_("name")) shouldBe List("Alice", "Bob", "Charlie")
    result.get.rows.head.keys should not contain "id"
    result.get.rows.head.keys should not contain "score"
  }

  it should "fail when no requested columns exist in the file" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.readContent(
      ParquetFile(loc),
      ReadConfig(columns = Some(List("nonexistent")))
    )
    result.isFailure shouldBe true
    result.failed.get.getMessage should include(
      "None of the requested columns exist"
    )
  }

  // ── Streaming ──────────────────────────────────────────────────────────

  it should "stream all rows via streamContent callback" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val collected = scala.collection.mutable.ListBuffer[Map[String, Any]]()
    val result =
      repo.streamContent(ParquetFile(loc), ReadConfig())(collected += _)
    result.isSuccess shouldBe true
    result.get shouldBe 3L
    collected.map(_("name")).toSet shouldBe Set("Alice", "Bob", "Charlie")
  }

  it should "stream respects maxRows limit" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val collected = scala.collection.mutable.ListBuffer[Map[String, Any]]()
    val result =
      repo.streamContent(ParquetFile(loc), ReadConfig(maxRows = Some(2L)))(
        collected += _
      )
    result.isSuccess shouldBe true
    result.get shouldBe 2L
    collected should have length 2
  }

  it should "stream projects requested columns" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val collected = scala.collection.mutable.ListBuffer[Map[String, Any]]()
    val result = repo.streamContent(
      ParquetFile(loc),
      ReadConfig(columns = Some(List("name")))
    )(collected += _)
    result.isSuccess shouldBe true
    collected.foreach { row =>
      row.keys should contain("name")
      row.keys should not contain "id"
    }
  }

  it should "stream returns rows matching filter" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val collected = scala.collection.mutable.ListBuffer[Map[String, Any]]()
    val result = repo.streamContent(
      ParquetFile(loc),
      ReadConfig(filter = Some("""name = "Alice""""))
    )(collected += _)
    result.isSuccess shouldBe true
    collected should have length 1
    collected.head("name") shouldBe "Alice"
  }

  // ── Parallel row group reading ──────────────────────────────────────────

  it should "return same rows with parallelism > 1 as sequential (single row group)" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val sequential = repo.readContent(ParquetFile(loc), ReadConfig()).get
    val parallel =
      repo.readContent(ParquetFile(loc), ReadConfig(parallelism = 4)).get

    parallel.rows.map(_("name")).toSet shouldBe sequential.rows
      .map(_("name"))
      .toSet
    parallel.totalRows shouldBe sequential.totalRows
  }

  it should "read multiple row groups in parallel and return all rows" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    val manyRows = (1 to 30)
      .map(i => Map[String, Any]("id" -> i.toLong, "name" -> s"user$i"))
      .toList
    repo
      .writeContent(loc, manyRows, None, WriteConfig(rowGroupSize = 1L))
      .isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig(parallelism = 4))
    result.isSuccess shouldBe true
    result.get.rows should have length 30
    result.get.rows
      .map(_("id").asInstanceOf[Long])
      .toSet shouldBe (1L to 30L).toSet
  }

  it should "respect maxRows limit in parallel mode" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    val manyRows = (1 to 20)
      .map(i => Map[String, Any]("id" -> i.toLong, "name" -> s"u$i"))
      .toList
    repo
      .writeContent(loc, manyRows, None, WriteConfig(rowGroupSize = 1L))
      .isSuccess shouldBe true

    val result = repo.readContent(
      ParquetFile(loc),
      ReadConfig(parallelism = 4, maxRows = Some(5L))
    )
    result.isSuccess shouldBe true
    result.get.rows should have length 5
    result.get.isPartial shouldBe true
  }

  it should "project columns in parallel mode" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    val manyRows = (1 to 10)
      .map(i =>
        Map[String, Any](
          "id" -> i.toLong,
          "name" -> s"u$i",
          "score" -> i.toDouble
        )
      )
      .toList
    repo
      .writeContent(loc, manyRows, None, WriteConfig(rowGroupSize = 1L))
      .isSuccess shouldBe true

    val result = repo.readContent(
      ParquetFile(loc),
      ReadConfig(parallelism = 4, columns = Some(List("id", "name")))
    )
    result.isSuccess shouldBe true
    result.get.rows should have length 10
    result.get.rows.foreach { row =>
      row.keys should contain allOf ("id", "name")
      row.keys should not contain "score"
    }
  }

  it should "fall back to sequential when filter is set (no parallel + filter)" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    // filter = Some(...) forces sequential path even with parallelism > 1
    val result = repo.readContent(
      ParquetFile(loc),
      ReadConfig(parallelism = 4, filter = Some("""name = "Alice""""))
    )
    result.isSuccess shouldBe true
    result.get.rows should have length 1
    result.get.rows.head("name") shouldBe "Alice"
  }

  // ── Edge cases ─────────────────────────────────────────────────────────

  it should "fail to write empty data (no schema to infer)" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    val result = repo.writeContent(loc, List.empty, None)
    result.isFailure shouldBe true
  }

  // ── Stats ───────────────────────────────────────────────────────────────

  "ParquetRepository.readStats" should "return stats for all columns" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.readStats(ParquetFile(loc))
    result.isSuccess shouldBe true

    val stats = result.get
    stats.totalRows shouldBe 3L
    stats.rowGroupCount shouldBe 1L

    val cols = stats.columns.map(_.name)
    cols should contain allOf ("id", "name", "score")
  }

  it should "have correct null count (0) when no nulls present" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.readStats(ParquetFile(loc))
    result.isSuccess shouldBe true

    val idStats = result.get.columns.find(_.name == "id").get
    idStats.nullCount shouldBe 0L
  }

  it should "return min and max values for numeric columns" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.readStats(ParquetFile(loc))
    result.isSuccess shouldBe true

    val idStats = result.get.columns.find(_.name == "id").get
    idStats.minValue shouldBe defined
    idStats.maxValue shouldBe defined
    idStats.minValue.get shouldBe "1"
    idStats.maxValue.get shouldBe "3"
  }
}
