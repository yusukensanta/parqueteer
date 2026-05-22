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

  it should "report Parquet format version (1.0 or 2.0), not writer library string" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.readMetadata(ParquetFile(loc))
    result.isSuccess shouldBe true
    val version = result.get.version
    version should (equal("1.0") or equal("2.0"))
    version should not include "parquet"
    version should not include "version"
  }

  // ── Profile/region threading ───────────────────────────────────────────

  it should "accept profile and region params without breaking local file operations" taggedAs IntegrationTest in {
    val repoWithOpts = new ParquetRepository(
      profile = Some("custom"),
      region = Some("us-east-2")
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repoWithOpts.writeContent(loc, sampleData, None).isSuccess shouldBe true
    val result = repoWithOpts.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    result.get.rows should have size 3
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

  it should "write and read back INT32 (Int) values preserving type" taggedAs IntegrationTest in {
    val data = List(
      Map[String, Any]("id" -> 1, "count" -> 100),
      Map[String, Any]("id" -> 2, "count" -> -50),
      Map[String, Any]("id" -> 3, "count" -> Int.MaxValue)
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    val rows = result.get.rows
    rows should have length 3
    rows.head("id") shouldBe 1
    rows.head("count") shouldBe 100
    rows.last("count") shouldBe Int.MaxValue
  }

  it should "write and read back FLOAT (Float) values" taggedAs IntegrationTest in {
    val data = List(
      Map[String, Any]("x" -> 1.5f, "y" -> -2.5f),
      Map[String, Any]("x" -> 0.0f, "y" -> 100.0f)
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    val rows = result.get.rows
    rows should have length 2
    rows.head("x").asInstanceOf[Float] shouldBe 1.5f +- 0.001f
    rows.head("y").asInstanceOf[Float] shouldBe -2.5f +- 0.001f
  }

  it should "write and read back BOOLEAN values" taggedAs IntegrationTest in {
    val data = List(
      Map[String, Any]("name" -> "Alice", "active" -> true),
      Map[String, Any]("name" -> "Bob", "active" -> false),
      Map[String, Any]("name" -> "Charlie", "active" -> true)
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    val rows = result.get.rows
    rows should have length 3
    rows.head("active") shouldBe true
    rows(1)("active") shouldBe false
    rows.last("active") shouldBe true
  }

  it should "read null field as null value (key present, value null) via sequential path" taggedAs IntegrationTest in {
    val data = List(
      Map[String, Any]("id" -> 1L, "note" -> "present"),
      Map[String, Any]("id" -> 2L, "note" -> null),
      Map[String, Any]("id" -> 3L, "note" -> "also present")
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    val rows = result.get.rows
    rows should have length 3
    rows.head.get("note") shouldBe Some("present")
    rows(1).keys should contain("note")
    Option(rows(1)("note")) shouldBe None
    rows.last.get("note") shouldBe Some("also present")
  }

  it should "write and read back DATE (LocalDate) values as ISO date strings" taggedAs IntegrationTest in {
    val data = List(
      Map[String, Any](
        "id" -> 1L,
        "dob" -> java.time.LocalDate.of(1990, 6, 15)
      ),
      Map[String, Any]("id" -> 2L, "dob" -> java.time.LocalDate.of(2001, 12, 1))
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    val rows = result.get.rows
    rows should have length 2
    rows.head("dob") shouldBe "1990-06-15"
    rows(1)("dob") shouldBe "2001-12-01"
  }

  it should "write and read back TIMESTAMP (Instant) values as ISO timestamp strings" taggedAs IntegrationTest in {
    val ts1 = java.time.Instant.parse("2024-01-01T08:00:00Z")
    val ts2 = java.time.Instant.parse("2024-06-15T23:59:59Z")
    val data = List(
      Map[String, Any]("event" -> "login", "ts" -> ts1),
      Map[String, Any]("event" -> "logout", "ts" -> ts2)
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    val rows = result.get.rows
    rows should have length 2
    rows.head("ts") shouldBe ts1.toString
    rows(1)("ts") shouldBe ts2.toString
  }

  it should "omit null fields in parallel (low-level) read path" taggedAs IntegrationTest in {
    val manyRows = (1 to 10).map { i =>
      if (i == 5) Map[String, Any]("id" -> i.toLong, "note" -> null)
      else Map[String, Any]("id" -> i.toLong, "note" -> s"row$i")
    }.toList
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo
      .writeContent(loc, manyRows, None, WriteConfig(rowGroupSize = 1L))
      .isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig(parallelism = 4))
    result.isSuccess shouldBe true
    val nullRow = result.get.rows.find(_("id") == 5L).get
    nullRow.keys should not contain "note"
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

  it should "report numerically correct min/max across multiple row groups, not lexicographic" taggedAs IntegrationTest in {
    // Values 1-200: lexicographic max is "99", numeric max is "200"
    // Lexicographic min is "1", numeric min is "1" (same here)
    // Use a tiny rowGroupSize to force the writer to flush multiple row groups
    val data = (1 to 200).toList.map(i =>
      Map[String, Any]("id" -> i.toLong, "label" -> s"item_$i")
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo
      .writeContent(loc, data, None, WriteConfig(rowGroupSize = 1024L))
      .isSuccess shouldBe true

    val result = repo.readStats(ParquetFile(loc))
    result.isSuccess shouldBe true

    val stats = result.get
    // Must have produced multiple row groups for this test to be meaningful
    stats.rowGroupCount should be > 1L

    val idStats = stats.columns.find(_.name == "id").get
    idStats.minValue.get shouldBe "1"
    // Lexicographic ordering would give "99"; numeric ordering gives "200"
    idStats.maxValue.get shouldBe "200"
  }
}
