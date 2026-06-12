package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models._
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.toParqueteerError
import org.scalatest.Tag
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files

object IntegrationTest extends Tag("IntegrationTest")

class ParquetRepositoryIntegrationTest extends AnyFlatSpec with Matchers {

  private val repo = new HadoopParquetRepository()

  private def tempFile(): java.io.File = {
    val f = Files.createTempFile("parqueteer_it_", ".parquet").toFile
    f.delete() // Hadoop refuses to overwrite existing files; free the path
    f.deleteOnExit()
    f
  }

  private val sampleData: List[Map[String, CellValue]] = List(
    Map(
      "id" -> CellValue.I64(1L),
      "name" -> CellValue.Str("Alice"),
      "score" -> CellValue.F64(95.5)
    ),
    Map(
      "id" -> CellValue.I64(2L),
      "name" -> CellValue.Str("Bob"),
      "score" -> CellValue.F64(87.3)
    ),
    Map(
      "id" -> CellValue.I64(3L),
      "name" -> CellValue.Str("Charlie"),
      "score" -> CellValue.F64(92.1)
    )
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

  it should "write and read back data with Gzip compression" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo
      .writeContent(
        loc,
        sampleData,
        None,
        WriteConfig(compressionType = CompressionType.Gzip)
      )
      .isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    result.get.rows should have length 3
    result.get.totalRows shouldBe 3L
    result.get.rows.head.keys should contain allOf ("id", "name", "score")
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
    result.get.createdAt shouldBe None
    result.get.modifiedAt shouldBe defined
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
    val repoWithOpts = new HadoopParquetRepository(
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

  it should "return zero rows for maxRows = Some(0L)" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result =
      repo.readContent(ParquetFile(loc), ReadConfig(maxRows = Some(0L)))
    result.isSuccess shouldBe true
    result.get.rows shouldBe empty
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
    result.get.rows.map(_("name")) shouldBe List(
      CellValue.Str("Alice"),
      CellValue.Str("Bob"),
      CellValue.Str("Charlie")
    )
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

    val collected =
      scala.collection.mutable.ListBuffer[Map[String, CellValue]]()
    val result =
      repo.streamContent(ParquetFile(loc), ReadConfig())(collected += _)
    result.isSuccess shouldBe true
    result.get shouldBe 3L
    collected.map(_("name")).toSet shouldBe Set(
      CellValue.Str("Alice"),
      CellValue.Str("Bob"),
      CellValue.Str("Charlie")
    )
  }

  it should "stream respects maxRows limit" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val collected =
      scala.collection.mutable.ListBuffer[Map[String, CellValue]]()
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

    val collected =
      scala.collection.mutable.ListBuffer[Map[String, CellValue]]()
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

    val collected =
      scala.collection.mutable.ListBuffer[Map[String, CellValue]]()
    val result = repo.streamContent(
      ParquetFile(loc),
      ReadConfig(filter = Some("""name = "Alice""""))
    )(collected += _)
    result.isSuccess shouldBe true
    collected should have length 1
    collected.head("name") shouldBe CellValue.Str("Alice")
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
      .map(i =>
        Map[String, CellValue](
          "id" -> CellValue.I64(i.toLong),
          "name" -> CellValue.Str(s"user$i")
        )
      )
      .toList
    repo
      .writeContent(loc, manyRows, None, WriteConfig(rowGroupSize = 1L))
      .isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig(parallelism = 4))
    result.isSuccess shouldBe true
    result.get.rows should have length 30
    result.get.rows
      .map(_("id"))
      .toSet shouldBe (1L to 30L).map(CellValue.I64(_)).toSet
  }

  it should "respect maxRows limit in parallel mode" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    val manyRows = (1 to 20)
      .map(i =>
        Map[String, CellValue](
          "id" -> CellValue.I64(i.toLong),
          "name" -> CellValue.Str(s"u$i")
        )
      )
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

  it should "return exactly maxRows rows when parallel + maxRows spans fewer row groups than total" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    val rows = (1 to 100)
      .map(i => Map[String, CellValue]("id" -> CellValue.I64(i.toLong)))
      .toList
    // rowGroupSize = 1L forces one row per row group → 100 row groups total
    repo
      .writeContent(loc, rows, None, WriteConfig(rowGroupSize = 1L))
      .isSuccess shouldBe true

    val config = ReadConfig(maxRows = Some(5L), parallelism = 4)
    val result = repo.readContent(ParquetFile(loc), config)
    result.isSuccess shouldBe true
    result.get.rows should have length 5
    result.get.isPartial shouldBe true
  }

  it should "project columns in parallel mode" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    val manyRows = (1 to 10)
      .map(i =>
        Map[String, CellValue](
          "id" -> CellValue.I64(i.toLong),
          "name" -> CellValue.Str(s"u$i"),
          "score" -> CellValue.F64(i.toDouble)
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
    result.get.rows.head("name") shouldBe CellValue.Str("Alice")
  }

  // ── IS NULL / IS NOT NULL type-aware filter ────────────────────────────

  it should "filter IS NULL on INT64 column without IllegalArgumentException" taggedAs IntegrationTest in {
    val data = List(
      Map[String, CellValue](
        "id" -> CellValue.I64(1L),
        "name" -> CellValue.Str("Alice")
      ),
      Map[String, CellValue](
        "id" -> CellValue.I64(2L),
        "name" -> CellValue.Null
      ),
      Map[String, CellValue](
        "id" -> CellValue.I64(3L),
        "name" -> CellValue.Str("Charlie")
      )
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result = repo.readContent(
      ParquetFile(loc),
      ReadConfig(filter = Some("id IS NOT NULL"))
    )
    result.isSuccess shouldBe true
    result.get.rows should have length 3
  }

  it should "filter IS NULL on DOUBLE column without IllegalArgumentException" taggedAs IntegrationTest in {
    val data = List(
      Map[String, CellValue](
        "id" -> CellValue.I64(1L),
        "score" -> CellValue.F64(9.5)
      ),
      Map[String, CellValue](
        "id" -> CellValue.I64(2L),
        "score" -> CellValue.Null
      ),
      Map[String, CellValue](
        "id" -> CellValue.I64(3L),
        "score" -> CellValue.F64(7.1)
      )
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result =
      repo.readContent(
        ParquetFile(loc),
        ReadConfig(filter = Some("score IS NOT NULL"))
      )
    result.isSuccess shouldBe true
  }

  // ── Edge cases ─────────────────────────────────────────────────────────

  it should "fail to write empty data (no schema to infer)" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    val result = repo.writeContent(loc, List.empty, None)
    result.isFailure shouldBe true
  }

  it should "write and read back INT32 (Int) values preserving type" taggedAs IntegrationTest in {
    val data = List(
      Map[String, CellValue](
        "id" -> CellValue.I32(1),
        "count" -> CellValue.I32(100)
      ),
      Map[String, CellValue](
        "id" -> CellValue.I32(2),
        "count" -> CellValue.I32(-50)
      ),
      Map[String, CellValue](
        "id" -> CellValue.I32(3),
        "count" -> CellValue.I32(Int.MaxValue)
      )
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    val rows = result.get.rows
    rows should have length 3
    rows.head("id") shouldBe CellValue.I32(1)
    rows.head("count") shouldBe CellValue.I32(100)
    rows.last("count") shouldBe CellValue.I32(Int.MaxValue)
  }

  it should "write and read back FLOAT (Float) values" taggedAs IntegrationTest in {
    val data = List(
      Map[String, CellValue](
        "x" -> CellValue.F32(1.5f),
        "y" -> CellValue.F32(-2.5f)
      ),
      Map[String, CellValue](
        "x" -> CellValue.F32(0.0f),
        "y" -> CellValue.F32(100.0f)
      )
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    val rows = result.get.rows
    rows should have length 2
    rows.head("x") match {
      case CellValue.F32(f) => f shouldBe 1.5f +- 0.001f
      case v                => fail(s"Expected CellValue.F32, got $v")
    }
    rows.head("y") match {
      case CellValue.F32(f) => f shouldBe -2.5f +- 0.001f
      case v                => fail(s"Expected CellValue.F32, got $v")
    }
  }

  it should "write and read back BOOLEAN values" taggedAs IntegrationTest in {
    val data = List(
      Map[String, CellValue](
        "name" -> CellValue.Str("Alice"),
        "active" -> CellValue.Bool(true)
      ),
      Map[String, CellValue](
        "name" -> CellValue.Str("Bob"),
        "active" -> CellValue.Bool(false)
      ),
      Map[String, CellValue](
        "name" -> CellValue.Str("Charlie"),
        "active" -> CellValue.Bool(true)
      )
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    val rows = result.get.rows
    rows should have length 3
    rows.head("active") shouldBe CellValue.Bool(true)
    rows(1)("active") shouldBe CellValue.Bool(false)
    rows.last("active") shouldBe CellValue.Bool(true)
  }

  it should "read null field as CellValue.Null (key present) via sequential path" taggedAs IntegrationTest in {
    val data = List(
      Map[String, CellValue](
        "id" -> CellValue.I64(1L),
        "note" -> CellValue.Str("present")
      ),
      Map[String, CellValue](
        "id" -> CellValue.I64(2L),
        "note" -> CellValue.Null
      ),
      Map[String, CellValue](
        "id" -> CellValue.I64(3L),
        "note" -> CellValue.Str("also present")
      )
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    val rows = result.get.rows
    rows should have length 3
    rows.head.get("note") shouldBe Some(CellValue.Str("present"))
    rows(1).keys should contain("note")
    rows(1)("note") shouldBe CellValue.Null
    rows.last.get("note") shouldBe Some(CellValue.Str("also present"))
  }

  it should "write and read back DATE (LocalDate) values as LocalDate" taggedAs IntegrationTest in {
    val data = List(
      Map[String, CellValue](
        "id" -> CellValue.I64(1L),
        "dob" -> CellValue.Date(java.time.LocalDate.of(1990, 6, 15))
      ),
      Map[String, CellValue](
        "id" -> CellValue.I64(2L),
        "dob" -> CellValue.Date(java.time.LocalDate.of(2001, 12, 1))
      )
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    val rows = result.get.rows
    rows should have length 2
    rows.head("dob") shouldBe CellValue.Date(
      java.time.LocalDate.of(1990, 6, 15)
    )
    rows(1)("dob") shouldBe CellValue.Date(java.time.LocalDate.of(2001, 12, 1))
  }

  it should "write and read back TIMESTAMP (Instant) values as Instant" taggedAs IntegrationTest in {
    val ts1 = java.time.Instant.parse("2024-01-01T08:00:00Z")
    val ts2 = java.time.Instant.parse("2024-06-15T23:59:59Z")
    val data = List(
      Map[String, CellValue](
        "event" -> CellValue.Str("login"),
        "ts" -> CellValue.Ts(ts1)
      ),
      Map[String, CellValue](
        "event" -> CellValue.Str("logout"),
        "ts" -> CellValue.Ts(ts2)
      )
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    val rows = result.get.rows
    rows should have length 2
    rows.head("ts") shouldBe CellValue.Ts(ts1)
    rows(1)("ts") shouldBe CellValue.Ts(ts2)
  }

  it should "emit CellValue.Null for absent fields in parallel (low-level) read path" taggedAs IntegrationTest in {
    val manyRows = (1 to 10).map { i =>
      if (i == 5)
        Map[String, CellValue](
          "id" -> CellValue.I64(i.toLong),
          "note" -> CellValue.Null
        )
      else
        Map[String, CellValue](
          "id" -> CellValue.I64(i.toLong),
          "note" -> CellValue.Str(s"row$i")
        )
    }.toList
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo
      .writeContent(loc, manyRows, None, WriteConfig(rowGroupSize = 1L))
      .isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig(parallelism = 4))
    result.isSuccess shouldBe true
    val nullRow = result.get.rows.find(_("id") == CellValue.I64(5L)).get
    nullRow.contains("note") shouldBe true
    nullRow("note") shouldBe CellValue.Null
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
    // Use a tiny rowGroupSize to force the writer to flush multiple row groups
    val data = (1 to 200).toList.map(i =>
      Map[String, CellValue](
        "id" -> CellValue.I64(i.toLong),
        "label" -> CellValue.Str(s"item_$i")
      )
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

  it should "return min/max for BINARY-encoded DECIMAL columns" taggedAs IntegrationTest in {
    val data = List(
      Map[String, CellValue]("amount" -> CellValue.Dec(BigDecimal("123.45"))),
      Map[String, CellValue]("amount" -> CellValue.Dec(BigDecimal("-9.99"))),
      Map[String, CellValue]("amount" -> CellValue.Dec(BigDecimal("1000.00")))
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    val schema = io.github.yusukensanta.parqueteer.core.models.ParquetSchema(
      columns = List(
        io.github.yusukensanta.parqueteer.core.models.ColumnInfo(
          "amount",
          "DECIMAL(10,2)",
          isOptional = true,
          maxDefinitionLevel = 1,
          maxRepetitionLevel = 0,
          compressionType = "SNAPPY"
        )
      ),
      rowGroupCount = 1L,
      totalRowCount = 3L
    )
    repo.writeContent(loc, data, Some(schema)).isSuccess shouldBe true

    val result = repo.readStats(ParquetFile(loc))
    result.isSuccess shouldBe true
    val amountStats = result.get.columns.find(_.name == "amount").get
    amountStats.minValue shouldBe defined
    amountStats.maxValue shouldBe defined
    amountStats.minValue.get shouldBe "-9.99"
    amountStats.maxValue.get shouldBe "1000.00"
  }

  // ── validateFile row-group integrity (#151) ──────────────────────────────

  it should "report issue for a file that cannot be opened as Parquet" taggedAs IntegrationTest in {
    val f = tempFile()
    java.nio.file.Files.write(f.toPath, "not a parquet file".getBytes("UTF-8"))

    val result = repo.validateFile(ParquetFile(LocalPath(f.getAbsolutePath)))
    result.isSuccess shouldBe true
    result.get should not be empty
    result.get.head should include("cannot be opened")
  }

  it should "spot-check (deep=false default) a multi-row-group file with no issues" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    val rows = (1 to 5)
      .map(i => Map[String, CellValue]("id" -> CellValue.I64(i.toLong)))
      .toList
    repo
      .writeContent(loc, rows, None, WriteConfig(rowGroupSize = 1L))
      .isSuccess shouldBe true

    val result = repo.validateFile(ParquetFile(loc))
    result.isSuccess shouldBe true
    result.get shouldBe empty
  }

  it should "deep-validate a multi-row-group file with no issues" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    val rows = (1 to 5)
      .map(i => Map[String, CellValue]("id" -> CellValue.I64(i.toLong)))
      .toList
    repo
      .writeContent(loc, rows, None, WriteConfig(rowGroupSize = 1L))
      .isSuccess shouldBe true

    val result = repo.validateFile(ParquetFile(loc), deep = true)
    result.isSuccess shouldBe true
    result.get shouldBe empty
  }

  // Exercises the skipNextRowGroup path: with 4 row groups, spot-check reads
  // indices {0, 2, 3} and skips index 1. If skipNextRowGroup throws it must
  // be collected into issues (not escape as Failure).
  it should "spot-check a 4-row-group file collecting any skipNextRowGroup failure as an issue" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    val rows = (1 to 4)
      .map(i => Map[String, CellValue]("id" -> CellValue.I64(i.toLong)))
      .toList
    repo
      .writeContent(loc, rows, None, WriteConfig(rowGroupSize = 1L))
      .isSuccess shouldBe true

    val result = repo.validateFile(ParquetFile(loc), deep = false)
    result.isSuccess shouldBe true
    result.get shouldBe empty
  }

  // ── writeContentStream sparse-column error (#153) ───────────────────────

  it should "give actionable error for column absent from schema in writeContentStream" taggedAs IntegrationTest in {
    val schema = ParquetSchema(
      columns = List(
        ColumnInfo("id", "INT64", isOptional = true, 1, 0, "UNCOMPRESSED")
      ),
      rowGroupCount = 0L,
      totalRowCount = 0L
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    val result = repo.writeContentStream(loc, schema) { write =>
      write(
        Map(
          "id" -> CellValue.I64(1L),
          "unknown_col" -> CellValue.Str("surprise")
        )
      )
    }
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("unknown_col")
    result.failed.get.getMessage should include("schema")
  }

  // ── Filter parse error propagation ────────────────────────────────────

  it should "return Failure when filter expression fails schema-aware parsing" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo
      .writeContent(loc, sampleData, None)
      .get // sampleData has columns: id, name, score

    // "score > \"hello\"" causes parseWithSchema to return Left because '>'
    // requires a numeric value but "hello" is a String.
    // Before fix: Left is silently dropped via .toOption → noopFilter → Success with all 3 rows.
    // After fix: Left causes a throw inside Try { } → Failure propagates to the caller.
    val config = ReadConfig(filter = Some("""score > "hello""""))
    val result = repo.readContent(ParquetFile(loc), config)

    result.isFailure shouldBe true
    result.failed.get.getMessage should include("Cannot apply filter")
    result.failed.get.getMessage should include("""score > "hello"""")
  }

  // ── Cloud auth error mapping (#H3) ─────────────────────────────────────

  "HadoopParquetRepository" should "wrap S3 auth failure as CloudAuthException" in {
    // Unset AWS credentials by pointing to a non-existent profile
    val repo =
      new HadoopParquetRepository(profile = Some("__nonexistent_profile_xyz__"))
    val file = ParquetFile(S3Location("test-bucket", "key.parquet", None))
    val result = repo.readSchema(file)
    result.isFailure shouldBe true
    // After our fix, the error should be a CloudAuthException
    result.failed.get shouldBe a[ParqueteerError.CloudAuthException]
    // Also verify the full error pipeline maps to CloudAuthError
    result.toParqueteerError shouldBe a[Left[?, ?]]
    result.toParqueteerError.swap.toOption.get shouldBe a[
      ParqueteerError.CloudAuthError
    ]
  }

  // ── TIMESTAMP_MICROS sequential decode (#154) ────────────────────────────

  it should "decode TIMESTAMP_MICROS as ISO-8601 Instant in sequential read" taggedAs IntegrationTest in {
    import org.apache.parquet.hadoop.example.ExampleParquetWriter
    import org.apache.parquet.schema.{Types => PTypes}
    import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
    import org.apache.parquet.schema.Type.Repetition
    import org.apache.parquet.schema.LogicalTypeAnnotation
    import org.apache.parquet.example.data.simple.SimpleGroupFactory

    val microsSchema = PTypes
      .buildMessage()
      .addField(
        PTypes
          .primitive(PrimitiveTypeName.INT64, Repetition.OPTIONAL)
          .as(
            LogicalTypeAnnotation
              .timestampType(true, LogicalTypeAnnotation.TimeUnit.MICROS)
          )
          .named("ts")
      )
      .named("root")

    val f = tempFile()
    val conf = new org.apache.hadoop.conf.Configuration()
    val factory = new SimpleGroupFactory(microsSchema)
    val writer = ExampleParquetWriter
      .builder(new org.apache.hadoop.fs.Path(f.getAbsolutePath))
      .withType(microsSchema)
      .withConf(conf)
      .build()
    // 1716336000000000 microseconds = 2024-05-22T00:00:00Z
    val group = factory.newGroup()
    group.add("ts", 1716336000000000L)
    writer.write(group)
    writer.close()

    val loc = LocalPath(f.getAbsolutePath)
    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    result.get.rows.head("ts") shouldBe CellValue.Ts(
      java.time.Instant.parse("2024-05-22T00:00:00Z")
    )
  }

  "HadoopParquetRepository.cacheStats" should "count footer cache hits and misses" taggedAs IntegrationTest in {
    val freshRepo = new HadoopParquetRepository()
    val loc = LocalPath(tempFile().getAbsolutePath)
    freshRepo
      .writeContent(loc, List(Map("x" -> CellValue.I64(1L))), None)
      .get

    val before = freshRepo.cacheStats()
    before.footerHits shouldBe 0L
    before.footerMisses shouldBe 0L

    freshRepo.readContent(ParquetFile(loc), ReadConfig()).get
    val afterFirst = freshRepo.cacheStats()
    afterFirst.footerMisses shouldBe 1L
    afterFirst.footerHits shouldBe 0L

    freshRepo.readContent(ParquetFile(loc), ReadConfig()).get
    val afterSecond = freshRepo.cacheStats()
    afterSecond.footerHits shouldBe 1L
    afterSecond.footerMisses shouldBe 1L
  }

  "HadoopParquetRepository.writeContent" should "infer schema from all rows (not just first 1000), so late-appearing columns are included" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    // Rows 1..1000 have only "id". Row 1001 introduces "extra".
    // Schema is inferred from all rows, so "extra" is in the schema and the write succeeds.
    val rows = (1 to 1000)
      .map(n => Map[String, CellValue]("id" -> CellValue.I64(n.toLong)))
      .toList
    val rowWithExtra = Map[String, CellValue](
      "id" -> CellValue.I64(1001L),
      "extra" -> CellValue.Str("hello")
    )
    val allRows = rows :+ rowWithExtra
    val result = repo.writeContent(loc, allRows, None)
    result.isSuccess shouldBe true
    val readBack = repo.readContent(ParquetFile(loc), ReadConfig()).get.rows
    readBack should have size 1001
    readBack.last.get("extra") shouldBe Some(CellValue.Str("hello"))
  }

  // ── logicalTypeName round-trip: DECIMAL / INT96 / FIXED_LEN_BYTE_ARRAY ──

  it should "readSchemaFields returns DECIMAL(p,s) for a BINARY-backed decimal column" taggedAs IntegrationTest in {
    val data = List(
      Map[String, CellValue]("price" -> CellValue.Dec(BigDecimal("9.99"))),
      Map[String, CellValue]("price" -> CellValue.Dec(BigDecimal("100.00")))
    )
    val schema = ParquetSchema(
      columns = List(
        ColumnInfo(
          "price",
          "DECIMAL(10,2)",
          isOptional = true,
          1,
          0,
          "UNCOMPRESSED"
        )
      ),
      rowGroupCount = 1L,
      totalRowCount = 2L
    )
    val loc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(loc, data, Some(schema)).isSuccess shouldBe true

    val fields = repo.readSchemaFields(ParquetFile(loc)).get
    fields should have size 1
    fields.head.name shouldBe "price"
    fields.head.dataType shouldBe "DECIMAL(10,2)"
  }

  it should "readSchemaFields round-trips DECIMAL type so convert/merge schema stays intact" taggedAs IntegrationTest in {
    val data = List(
      Map[String, CellValue]("amount" -> CellValue.Dec(BigDecimal("1.50"))),
      Map[String, CellValue]("amount" -> CellValue.Dec(BigDecimal("-3.00")))
    )
    val schema = ParquetSchema(
      columns = List(
        ColumnInfo(
          "amount",
          "DECIMAL(18,4)",
          isOptional = true,
          1,
          0,
          "UNCOMPRESSED"
        )
      ),
      rowGroupCount = 1L,
      totalRowCount = 2L
    )
    val srcLoc = LocalPath(tempFile().getAbsolutePath)
    val dstLoc = LocalPath(tempFile().getAbsolutePath)
    repo.writeContent(srcLoc, data, Some(schema)).isSuccess shouldBe true

    val fields = repo.readSchemaFields(ParquetFile(srcLoc)).get
    fields.head.dataType should startWith("DECIMAL(")

    // Mirror what convert does: build ColumnInfo from FieldSummary and re-write.
    val dstSchema = ParquetSchema(
      columns = fields.map(f =>
        ColumnInfo(
          f.name,
          f.dataType,
          f.isOptional,
          if (f.isOptional) 1 else 0,
          0,
          "UNCOMPRESSED"
        )
      ),
      rowGroupCount = 1L,
      totalRowCount = 2L
    )
    // Parallel path uses low-level decoder which supports BINARY-backed DECIMAL.
    val rows = repo
      .readContent(ParquetFile(srcLoc), ReadConfig(parallelism = 4))
      .get
      .rows
    repo.writeContent(dstLoc, rows, Some(dstSchema)).isSuccess shouldBe true

    val result = repo
      .readContent(ParquetFile(dstLoc), ReadConfig(parallelism = 4))
      .get
      .rows
    result should have size 2
    result.map(_("amount")).toSet shouldBe Set(
      CellValue.Dec(BigDecimal("1.5000")),
      CellValue.Dec(BigDecimal("-3.0000"))
    )
  }

  it should "parallel readContent with maxRows smaller than result set returns exactly maxRows rows" taggedAs IntegrationTest in {
    val loc = LocalPath(tempFile().getAbsolutePath)
    val rows = (1 to 20)
      .map(i => Map[String, CellValue]("n" -> CellValue.I64(i.toLong)))
      .toList
    repo.writeContent(loc, rows, None).isSuccess shouldBe true

    val result = repo
      .readContent(
        ParquetFile(loc),
        ReadConfig(parallelism = 4, maxRows = Some(7L))
      )
      .get
    result.rows should have size 7
  }
}
