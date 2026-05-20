package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.services.{SchemaDiff, ColumnChange}
import io.github.yusukensanta.parqueteer.core.models.{
  ColumnInfo,
  ColumnStats,
  FileMetadata,
  FileStats,
  LocalPath,
  ParquetFile,
  ParquetSchema
}
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class CliOutputFormatterTest extends AnyFlatSpec with Matchers {

  // ─── helpers ────────────────────────────────────────────────────────────────

  private def localFile(path: String = "/tmp/test.parquet"): LocalPath =
    LocalPath(path)

  private def makeParquetFile(
      schema: Option[ParquetSchema] = None,
      metadata: Option[FileMetadata] = None
  ): ParquetFile =
    ParquetFile(location = localFile(), schema = schema, metadata = metadata)

  private def makeColumnInfo(
      name: String,
      dataType: String,
      isOptional: Boolean = false,
      compressionType: String = "SNAPPY"
  ): ColumnInfo =
    ColumnInfo(
      name = name,
      dataType = dataType,
      isOptional = isOptional,
      maxDefinitionLevel = if (isOptional) 1 else 0,
      maxRepetitionLevel = 0,
      compressionType = compressionType
    )

  private def makeSchema(
      columns: List[ColumnInfo],
      totalRowCount: Long = 100L,
      rowGroupCount: Long = 1L
  ): ParquetSchema =
    ParquetSchema(columns = columns, rowGroupCount = rowGroupCount, totalRowCount = totalRowCount)

  private def makeMetadata(
      fileSize: Long = 1024L,
      version: String = "2.0",
      createdBy: Option[String] = None,
      createdAt: Option[Instant] = None,
      modifiedAt: Option[Instant] = None,
      compressionRatio: Option[Double] = None
  ): FileMetadata =
    FileMetadata(
      fileSize = fileSize,
      createdAt = createdAt,
      modifiedAt = modifiedAt,
      compressionRatio = compressionRatio,
      version = version,
      createdBy = createdBy
    )

  private def makeStats(
      columns: List[ColumnStats],
      totalRows: Long = 50L,
      rowGroupCount: Long = 2L
  ): FileStats =
    FileStats(columns = columns, totalRows = totalRows, rowGroupCount = rowGroupCount)

  private def makeColumnStats(
      name: String,
      dataType: String = "INT64",
      nullCount: Long = 0L,
      minValue: Option[String] = None,
      maxValue: Option[String] = None
  ): ColumnStats =
    ColumnStats(name = name, dataType = dataType, nullCount = nullCount, minValue = minValue, maxValue = maxValue)

  // ─── formatBytesForDisplay ──────────────────────────────────────────────────

  "CliOutputFormatter.formatBytesForDisplay" should "format bytes" in {
    CliOutputFormatter.formatBytesForDisplay(512L) shouldBe "512.0 B"
  }

  it should "format kilobytes" in {
    CliOutputFormatter.formatBytesForDisplay(1024L) shouldBe "1.0 KB"
  }

  it should "format megabytes" in {
    CliOutputFormatter.formatBytesForDisplay(1024L * 1024L) shouldBe "1.0 MB"
  }

  it should "format gigabytes" in {
    CliOutputFormatter.formatBytesForDisplay(1024L * 1024L * 1024L) shouldBe "1.0 GB"
  }

  it should "format terabytes" in {
    CliOutputFormatter.formatBytesForDisplay(1024L * 1024L * 1024L * 1024L) shouldBe "1.0 TB"
  }

  it should "cap at TB even for very large values" in {
    val huge = 1024L * 1024L * 1024L * 1024L * 5L
    CliOutputFormatter.formatBytesForDisplay(huge) should include("TB")
  }

  it should "format zero bytes" in {
    CliOutputFormatter.formatBytesForDisplay(0L) shouldBe "0.0 B"
  }

  it should "format fractional kilobytes correctly" in {
    // 1536 bytes = 1.5 KB
    CliOutputFormatter.formatBytesForDisplay(1536L) shouldBe "1.5 KB"
  }

  it should "handle negative byte values by formatting as-is" in {
    CliOutputFormatter.formatBytesForDisplay(-1L) should startWith("-")
  }

  // ─── formatInfoJson ─────────────────────────────────────────────────────────

  "CliOutputFormatter.formatInfoJson" should "return empty JSON object when metadata is absent" in {
    val file = makeParquetFile()
    val result = CliOutputFormatter.formatInfoJson(file)
    val json = parse(result).toOption
    json shouldBe defined
    json.get.asObject.get.isEmpty shouldBe true
  }

  it should "include all metadata fields when present" in {
    val ts = Instant.parse("2024-01-01T00:00:00Z")
    val meta = makeMetadata(
      fileSize = 2048L,
      version = "2.6",
      createdBy = Some("parquet-mr"),
      createdAt = Some(ts),
      modifiedAt = Some(ts),
      compressionRatio = Some(0.75)
    )
    val file = makeParquetFile(metadata = Some(meta))
    val result = CliOutputFormatter.formatInfoJson(file)
    val json = parse(result).toOption.get.asObject.get

    json("fileSize").get.asNumber.get.toLong.get shouldBe 2048L
    json("version").get.asString.get shouldBe "2.6"
    json("createdBy").get.asString.get shouldBe "parquet-mr"
    json("createdAt").get.asString.get shouldBe ts.toString
    json("compressionRatio").get.asNumber.get.toDouble shouldBe 0.75 +- 0.001
  }

  it should "render null for optional metadata fields when absent" in {
    val meta = makeMetadata()
    val file = makeParquetFile(metadata = Some(meta))
    val result = CliOutputFormatter.formatInfoJson(file)
    val json = parse(result).toOption.get.asObject.get

    json("createdAt").get.isNull shouldBe true
    json("modifiedAt").get.isNull shouldBe true
    json("compressionRatio").get.isNull shouldBe true
    json("createdBy").get.isNull shouldBe true
  }

  // ─── formatSchemaJson ───────────────────────────────────────────────────────

  "CliOutputFormatter.formatSchemaJson" should "return empty JSON object when schema is absent" in {
    val file = makeParquetFile()
    val result = CliOutputFormatter.formatSchemaJson(file)
    val json = parse(result).toOption.get
    json.asObject.get.isEmpty shouldBe true
  }

  it should "include totalRowCount, rowGroupCount, and columns array" in {
    val col = makeColumnInfo("id", "INT64", isOptional = false, compressionType = "SNAPPY")
    val schema = makeSchema(List(col), totalRowCount = 200L, rowGroupCount = 3L)
    val file = makeParquetFile(schema = Some(schema))
    val result = CliOutputFormatter.formatSchemaJson(file)
    val json = parse(result).toOption.get.asObject.get

    json("totalRowCount").get.asNumber.get.toLong.get shouldBe 200L
    json("rowGroupCount").get.asNumber.get.toLong.get shouldBe 3L
    val cols = json("columns").get.asArray.get
    cols.length shouldBe 1
    val c0 = cols(0).asObject.get
    c0("name").get.asString.get shouldBe "id"
    c0("dataType").get.asString.get shouldBe "INT64"
    c0("optional").get.asBoolean.get shouldBe false
    c0("compressionType").get.asString.get shouldBe "SNAPPY"
  }

  it should "handle multiple columns" in {
    val cols = List(
      makeColumnInfo("a", "INT64"),
      makeColumnInfo("b", "BINARY", isOptional = true)
    )
    val file = makeParquetFile(schema = Some(makeSchema(cols)))
    val result = CliOutputFormatter.formatSchemaJson(file)
    val arr = parse(result).toOption.get.asObject.get("columns").get.asArray.get
    arr.length shouldBe 2
    arr(1).asObject.get("optional").get.asBoolean.get shouldBe true
  }

  // ─── formatStatsTable ───────────────────────────────────────────────────────

  "CliOutputFormatter.formatStatsTable" should "include header with row and row-group counts" in {
    val stats = makeStats(Nil, totalRows = 10L, rowGroupCount = 1L)
    val result = CliOutputFormatter.formatStatsTable(stats)
    result should include("10 rows")
    result should include("1 row groups")
  }

  it should "include column data in the table" in {
    val col = makeColumnStats("age", "INT32", nullCount = 2L, minValue = Some("18"), maxValue = Some("65"))
    val stats = makeStats(List(col))
    val result = CliOutputFormatter.formatStatsTable(stats)
    result should include("age")
    result should include("INT32")
    result should include("2")
    result should include("18")
    result should include("65")
  }

  it should "show n/a for negative nullCount" in {
    val col = makeColumnStats("col", nullCount = -1L)
    val stats = makeStats(List(col))
    val result = CliOutputFormatter.formatStatsTable(stats)
    result should include("n/a")
  }

  it should "show n/a for missing min/max" in {
    val col = makeColumnStats("col", minValue = None, maxValue = None)
    val stats = makeStats(List(col))
    val result = CliOutputFormatter.formatStatsTable(stats)
    result should include("n/a")
  }

  it should "not end with trailing whitespace" in {
    val col = makeColumnStats("x")
    val stats = makeStats(List(col))
    val result = CliOutputFormatter.formatStatsTable(stats)
    result should not endWith " "
  }

  // ─── formatStatsJson ────────────────────────────────────────────────────────

  "CliOutputFormatter.formatStatsJson" should "return a valid Json object" in {
    val col = makeColumnStats("score", "DOUBLE", nullCount = 0L, minValue = Some("0.0"), maxValue = Some("100.0"))
    val stats = makeStats(List(col), totalRows = 99L, rowGroupCount = 2L)
    val json = CliOutputFormatter.formatStatsJson(stats).asObject.get

    json("totalRows").get.asNumber.get.toLong.get shouldBe 99L
    json("rowGroupCount").get.asNumber.get.toLong.get shouldBe 2L
    val arr = json("columns").get.asArray.get
    arr.length shouldBe 1
    val c0 = arr(0).asObject.get
    c0("name").get.asString.get shouldBe "score"
    c0("dataType").get.asString.get shouldBe "DOUBLE"
    c0("nullCount").get.asNumber.get.toLong.get shouldBe 0L
    c0("minValue").get.asString.get shouldBe "0.0"
    c0("maxValue").get.asString.get shouldBe "100.0"
  }

  it should "render null for missing min/max values" in {
    val col = makeColumnStats("x", minValue = None, maxValue = None)
    val stats = makeStats(List(col))
    val json = CliOutputFormatter.formatStatsJson(stats).asObject.get
    val c0 = json("columns").get.asArray.get(0).asObject.get
    c0("minValue").get.isNull shouldBe true
    c0("maxValue").get.isNull shouldBe true
  }

  it should "return valid JSON string via .spaces2" in {
    val stats = makeStats(Nil)
    val jsonStr = CliOutputFormatter.formatStatsJson(stats).spaces2
    parse(jsonStr).isRight shouldBe true
  }

  // ─── formatSchemaDiffTable ──────────────────────────────────────────────────

  "CliOutputFormatter.formatSchemaDiffTable" should "report identical schemas" in {
    val diff = SchemaDiff(added = Nil, removed = Nil, changed = Nil, unchanged = List("id"))
    val result = CliOutputFormatter.formatSchemaDiffTable("a.parquet", "b.parquet", diff)
    result should include("a.parquet")
    result should include("b.parquet")
    result should include("identical")
    result should not include ("= ")
  }

  it should "list added columns with +" in {
    val added = makeColumnInfo("new_col", "INT32", isOptional = true)
    val diff = SchemaDiff(added = List(added), removed = Nil, changed = Nil, unchanged = Nil)
    val result = CliOutputFormatter.formatSchemaDiffTable("f1", "f2", diff)
    result should include("+ new_col")
    result should include("optional")
  }

  it should "list removed columns with -" in {
    val removed = makeColumnInfo("old_col", "INT64", isOptional = false)
    val diff = SchemaDiff(added = Nil, removed = List(removed), changed = Nil, unchanged = Nil)
    val result = CliOutputFormatter.formatSchemaDiffTable("f1", "f2", diff)
    result should include("- old_col")
    result should include("required")
  }

  it should "list changed columns with ~ for type changes only" in {
    val change = ColumnChange("col", fromType = "INT32", toType = "INT64", fromOptional = false, toOptional = false)
    val diff = SchemaDiff(added = Nil, removed = Nil, changed = List(change), unchanged = Nil)
    val result = CliOutputFormatter.formatSchemaDiffTable("f1", "f2", diff)
    result should include("~ col")
    result should include("INT32 → INT64")
  }

  it should "list changed columns with ~ for optionality changes only" in {
    val change = ColumnChange("col", fromType = "INT32", toType = "INT32", fromOptional = false, toOptional = true)
    val diff = SchemaDiff(added = Nil, removed = Nil, changed = List(change), unchanged = Nil)
    val result = CliOutputFormatter.formatSchemaDiffTable("f1", "f2", diff)
    result should include("~ col")
    result should include("required → optional")
  }

  it should "list changed columns with both type and optionality changes" in {
    val change = ColumnChange("col", fromType = "INT32", toType = "INT64", fromOptional = false, toOptional = true)
    val diff = SchemaDiff(added = Nil, removed = Nil, changed = List(change), unchanged = Nil)
    val result = CliOutputFormatter.formatSchemaDiffTable("f1", "f2", diff)
    result should include("~ col")
    result should include("INT32 required → INT64 optional")
  }

  it should "list unchanged columns with = when there are also differences" in {
    val removed = makeColumnInfo("old_col", "INT64", isOptional = false)
    val diff = SchemaDiff(added = Nil, removed = List(removed), changed = Nil, unchanged = List("id", "name"))
    val result = CliOutputFormatter.formatSchemaDiffTable("f1", "f2", diff)
    result should include("= id, name")
  }

  it should "not end with trailing whitespace" in {
    val diff = SchemaDiff(added = Nil, removed = Nil, changed = Nil, unchanged = List("id"))
    val result = CliOutputFormatter.formatSchemaDiffTable("f1", "f2", diff)
    result should not endWith " "
  }

  // ─── formatSchemaDiffJson ───────────────────────────────────────────────────

  "CliOutputFormatter.formatSchemaDiffJson" should "return valid JSON for identical schemas" in {
    val diff = SchemaDiff(added = Nil, removed = Nil, changed = Nil, unchanged = List("id"))
    val result = CliOutputFormatter.formatSchemaDiffJson(diff)
    val json = parse(result).toOption.get.asObject.get

    json("identical").get.asBoolean.get shouldBe true
    json("added").get.asArray.get shouldBe empty
    json("removed").get.asArray.get shouldBe empty
    json("changed").get.asArray.get shouldBe empty
    json("unchanged").get.asArray.get.map(_.asString.get) shouldBe Vector("id")
  }

  it should "serialize added columns" in {
    val added = makeColumnInfo("new_col", "INT32", isOptional = true)
    val diff = SchemaDiff(added = List(added), removed = Nil, changed = Nil, unchanged = Nil)
    val result = CliOutputFormatter.formatSchemaDiffJson(diff)
    val json = parse(result).toOption.get.asObject.get

    json("identical").get.asBoolean.get shouldBe false
    val arr = json("added").get.asArray.get
    arr.length shouldBe 1
    val c0 = arr(0).asObject.get
    c0("name").get.asString.get shouldBe "new_col"
    c0("type").get.asString.get shouldBe "INT32"
    c0("optional").get.asBoolean.get shouldBe true
  }

  it should "serialize removed columns" in {
    val removed = makeColumnInfo("old_col", "BINARY", isOptional = false)
    val diff = SchemaDiff(added = Nil, removed = List(removed), changed = Nil, unchanged = Nil)
    val result = CliOutputFormatter.formatSchemaDiffJson(diff)
    val arr = parse(result).toOption.get.asObject.get("removed").get.asArray.get
    arr.length shouldBe 1
    arr(0).asObject.get("name").get.asString.get shouldBe "old_col"
  }

  it should "serialize changed columns with from/to type and optional fields" in {
    val change = ColumnChange("col", fromType = "INT32", toType = "INT64", fromOptional = false, toOptional = true)
    val diff = SchemaDiff(added = Nil, removed = Nil, changed = List(change), unchanged = Nil)
    val result = CliOutputFormatter.formatSchemaDiffJson(diff)
    val arr = parse(result).toOption.get.asObject.get("changed").get.asArray.get
    arr.length shouldBe 1
    val c0 = arr(0).asObject.get
    c0("name").get.asString.get shouldBe "col"
    c0("from_type").get.asString.get shouldBe "INT32"
    c0("to_type").get.asString.get shouldBe "INT64"
    c0("from_optional").get.asBoolean.get shouldBe false
    c0("to_optional").get.asBoolean.get shouldBe true
  }

  it should "produce parseable JSON string" in {
    val diff = SchemaDiff(added = Nil, removed = Nil, changed = Nil, unchanged = Nil)
    val result = CliOutputFormatter.formatSchemaDiffJson(diff)
    parse(result).isRight shouldBe true
  }
}
