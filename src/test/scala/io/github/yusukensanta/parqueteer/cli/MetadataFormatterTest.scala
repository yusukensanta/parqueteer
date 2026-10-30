package io.github.yusukensanta.parqueteer.cli

import io.circe.parser
import io.github.yusukensanta.parqueteer.core.models.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MetadataFormatterTest extends AnyFlatSpec with Matchers {

  private val report = MetadataFixtures.report

  "formatJson" should "emit valid JSON with file, key-value, schema and row group sections" in {
    val json   = parser.parse(MetadataFormatter.formatJson(report)).toOption.get.hcursor
    val file   = json.downField("file")
    val chunks = json.downField("rowGroups").downArray.downField("columns")

    file.get[Long]("rowCount") shouldBe Right(100L)
    file.get[String]("createdBy") shouldBe Right("parquet-mr version 1.15.2")
    json.downField("keyValueMetadata").get[String]("writer.model.name") shouldBe Right("example")
    json.get[String]("messageSchema").toOption.get should include("required int64 id")
    json.downField("columns").downArray.get[Int]("fieldId") shouldBe Right(1)
    chunks.downArray.get[String]("codec") shouldBe Right("SNAPPY")
    chunks.downArray.get[List[String]]("encodings") shouldBe Right(List("PLAIN", "RLE"))
    chunks.downArray.get[Boolean]("hasColumnIndex") shouldBe Right(true)
    chunks.downArray.get[Option[Long]]("dictionaryPageOffset") shouldBe Right(None)
    chunks.downN(1).get[Boolean]("hasBloomFilter") shouldBe Right(true)
    chunks.downN(1).get[Option[Long]]("dictionaryPageOffset") shouldBe Right(Some(404L))
  }

  it should "render absent statistics as null" in {
    val json   = parser.parse(MetadataFormatter.formatJson(report)).toOption.get.hcursor
    val second = json.downField("rowGroups").downArray.downField("columns").downN(1)
    second.get[Option[String]]("minValue") shouldBe Right(None)
    second.get[Option[Long]]("nullCount") shouldBe Right(None)
  }

  it should "emit an empty key-value object when the file has none" in {
    val out = MetadataFormatter.formatJson(report.copy(keyValueMetadata = Nil))
    parser.parse(out).toOption.get.hcursor.downField("keyValueMetadata").keys.map(_.toList) shouldBe
      Some(Nil)
  }

  "formatTable" should "include file-level fields" in {
    val out = MetadataFormatter.formatTable(report)
    out should include("File Metadata")
    out should include("2 KB (2048 bytes)")
    out should include("parquet-mr version 1.15.2")
    out should include("Row Groups:      1")
  }

  it should "list key-value metadata sorted as given" in {
    val out = MetadataFormatter.formatTable(report)
    out should include("writer.model.name = example")
    out.indexOf("writer.model.name") should be < out.indexOf("z.key")
  }

  it should "state when there is no key-value metadata" in {
    MetadataFormatter.formatTable(report.copy(keyValueMetadata = Nil)) should include("(none)")
  }

  it should "show the message schema and column schema details" in {
    val out = MetadataFormatter.formatTable(report)
    out should include("message schema {")
    out should include("optional binary name (STRING);")
    out should include("REQUIRED")
    out should include("STRING")
  }

  it should "show per-row-group column chunks with flags" in {
    val out = MetadataFormatter.formatTable(report)
    out should include("Row Group 0: 100 rows")
    out should include("PLAIN,RLE,RLE_DICTIONARY")
    out should include("--CO") // id: column + offset index only
    out should include("DB--") // name: dictionary page + bloom filter
    out should include("Flags: D=dictionary page")
  }

  it should "not leave trailing whitespace on any line" in {
    val lines = MetadataFormatter.formatTable(report).linesIterator.toList
    lines.filter(l => l != l.stripTrailing()) shouldBe Nil
  }

  it should "draw each table rule as wide as its header" in {
    val lines = MetadataFormatter.formatTable(report).linesIterator.toList
    // Section underlines are short; table rules span the whole header row.
    val tableRules = lines.zipWithIndex.filter { case (l, _) => l.startsWith("-" * 40) }
    tableRules should have size 2 // column schema + the one row group's chunks
    tableRules.foreach { case (rule, i) => rule.length shouldBe lines(i - 1).length }
  }

  it should "print n/a for missing min/max/null count" in {
    val out = MetadataFormatter.formatTable(report)
    out should include("n/a")
  }

  it should "render a file with no row groups without a row group section" in {
    val out = MetadataFormatter.formatTable(report.copy(rowGroups = Nil))
    out should not include "Row Group 0"
  }

  it should "print n/a when createdBy is missing and omit modified when unknown" in {
    val noDetails = report.copy(file = report.file.copy(createdBy = None, modifiedAt = None))
    val out       = MetadataFormatter.formatTable(noDetails)
    out should include("Created By:      n/a")
    out should not include "Modified:"
  }

  "truncateKeyValue" should "leave values that fit untouched" in {
    MetadataFormatter.truncateKeyValue("short", 80) shouldBe "short"
  }

  it should "leave a value of exactly maxLen untouched" in {
    MetadataFormatter.truncateKeyValue("abcde", 5) shouldBe "abcde"
  }

  it should "cut long values and report the original length" in {
    val out = MetadataFormatter.truncateKeyValue("x" * 200, 10)
    out shouldBe ("x" * 10) + "… (200 chars)"
  }

  it should "collapse line breaks so the entry stays on one line" in {
    MetadataFormatter.truncateKeyValue("{\n  \"a\": 1\n}", 80) shouldBe "{ \"a\": 1 }"
  }

  it should "report the original, not the flattened, length when cutting" in {
    val out = MetadataFormatter.truncateKeyValue("a\n\n\n" + "b" * 20, 5)
    out should startWith("a bbb")
    out should endWith("(24 chars)")
  }

  "formatTable" should "shorten long key-value values but formatJson keeps them whole" in {
    val long = report.copy(keyValueMetadata = List("big" -> ("y" * 500)))
    MetadataFormatter.formatTable(long) should include("(500 chars)")
    MetadataFormatter.formatTable(long) should not include ("y" * 500)
    MetadataFormatter.formatJson(long) should include("y" * 500)
  }

  "MetadataFormatter.formatJson" should "include groups per column and the schema tree" in {
    val tree = SchemaNode(
      "m",
      None,
      None,
      None,
      None,
      None,
      None,
      List(
        SchemaNode(
          "tags",
          Some("OPTIONAL"),
          None,
          None,
          Some(LogicalTypeDetail("LIST")),
          Some("LIST"),
          None,
          Nil
        )
      )
    )
    val base = MetadataFixtures.report
    val report = base.copy(
      schemaTree = Some(tree),
      columns = base.columns.map(c =>
        c.copy(groups = List(GroupInfo("tags", "OPTIONAL", Some(LogicalTypeDetail("LIST")))))
      )
    )
    val json = io.circe.parser.parse(MetadataFormatter.formatJson(report)).toOption.get.hcursor
    json.downField("columns").downN(0).downField("groups").downN(0).get[String]("name") shouldBe
      Right("tags")
    json
      .downField("schemaTree")
      .downField("children")
      .downN(0)
      .downField("logicalType")
      .get[String]("type") shouldBe Right("LIST")
  }

  it should "emit a null schemaTree when none was read" in {
    val json =
      io.circe.parser.parse(MetadataFormatter.formatJson(MetadataFixtures.report)).toOption.get
    json.hcursor.downField("schemaTree").focus shouldBe Some(io.circe.Json.Null)
  }

  private val extendedChunk = ExtendedChunkDetail(
    dictionaryPages = List(EncodingPageCount("PLAIN", 1)),
    dataPages = List(EncodingPageCount("RLE_DICTIONARY", 3)),
    usesV2Pages = Some(true),
    distinctCount = Some(7L),
    isMinExact = Some(true),
    isMaxExact = Some(false),
    bloomFilter = Some(FileRegion(900L, Some(64L))),
    columnIndex = Some(FileRegion(100L, Some(20L))),
    offsetIndex = Some(FileRegion(120L, None)),
    sizeStatistics = Some(SizeStatisticsDetail(Some(2048L), List(5L), List(1L, 4L))),
    isEncrypted = false
  )

  private val extendedReport = {
    val base = MetadataFixtures.report
    base.copy(
      extended = Some(ExtendedFileDetail(List("TYPE_DEFINED_ORDER"), Some("AES_GCM_V1"), true)),
      rowGroups = base.rowGroups.map(rg =>
        rg.copy(
          extended = Some(
            ExtendedRowGroupDetail(
              Some(0),
              Some(4L),
              Some(0L),
              List(SortingColumnDetail("id", descending = false, nullsFirst = false))
            )
          ),
          columns = rg.columns.map(_.copy(extended = Some(extendedChunk)))
        )
      )
    )
  }

  "formatTable with extended detail" should "add file, row group and chunk sections" in {
    val out = MetadataFormatter.formatTable(extendedReport)
    out should include("Extended File Detail")
    out should include("Encryption:       AES_GCM_V1")
    out should include("sorted by id ASC NULLS LAST")
    out should include("RLE_DICTIONARY:3")
    out should include("@900+64")
    out should include("@120+?")
    out should include("Y/N")
  }

  it should "not print extended sections for a plain report" in {
    MetadataFormatter.formatTable(report) should not include "Extended"
  }

  "formatJson with extended detail" should "nest chunk, row group and file extras" in {
    val json = parser.parse(MetadataFormatter.formatJson(extendedReport)).toOption.get.hcursor
    json.downField("extended").get[Boolean]("hasFooterSigningKey") shouldBe Right(true)
    val rg = json.downField("rowGroups").downN(0)
    rg.downField("extended").downField("sortingColumns").downN(0).get[String]("column") shouldBe
      Right("id")
    val chunk = rg.downField("columns").downN(0).downField("extended")
    chunk.get[Long]("distinctCount") shouldBe Right(7L)
    chunk.downField("bloomFilter").get[Long]("offset") shouldBe Right(900L)
    chunk.downField("sizeStatistics").get[List[Long]]("definitionLevelHistogram") shouldBe
      Right(List(1L, 4L))
  }

  private val pagedReport = {
    val base = MetadataFixtures.report
    base.copy(rowGroups =
      base.rowGroups.map(rg =>
        rg.copy(columns =
          rg.columns.map(c =>
            c.copy(
              pageIndex = Some(
                PageIndexDetail(
                  Some("ASCENDING"),
                  List(
                    IndexedPage(
                      0,
                      Some(4L),
                      Some(120),
                      Some(0L),
                      Some(false),
                      Some(0L),
                      Some("1"),
                      Some("50")
                    ),
                    IndexedPage(
                      1,
                      Some(124L),
                      Some(80),
                      Some(50L),
                      Some(true),
                      Some(50L),
                      None,
                      None
                    )
                  )
                )
              ),
              pages = Some(
                List(
                  PageHeaderDetail(
                    "DICTIONARY_PAGE",
                    4L,
                    20,
                    100,
                    110,
                    Some(5),
                    Some("PLAIN"),
                    None,
                    None,
                    false,
                    false
                  ),
                  PageHeaderDetail(
                    "DATA_PAGE_V2",
                    124L,
                    25,
                    80,
                    90,
                    Some(50),
                    Some("RLE_DICTIONARY"),
                    Some(50),
                    Some(3),
                    true,
                    true
                  )
                )
              )
            )
          )
        )
      )
    )
  }

  "formatTable with page detail" should "print an index table and a page header table per column" in {
    val out = MetadataFormatter.formatTable(pagedReport)
    out should include("Row Group 0 / id: column & offset index (boundary order ASCENDING)")
    out should include("Row Group 0 / name: 2 page headers")
    out should include("DICTIONARY_PAGE")
    out should include("RLE_DICTIONARY")
    out.linesIterator.filter(_.trim.startsWith("1 ")).exists(_.contains("Y")) shouldBe true
  }

  it should "not print page sections for a plain report" in {
    MetadataFormatter.formatTable(report) should not include "page headers"
    MetadataFormatter.formatTable(report) should not include "column & offset index"
  }

  "formatJson with page detail" should "nest pageIndex and pages per chunk, null when absent" in {
    val json = parser.parse(MetadataFormatter.formatJson(pagedReport)).toOption.get.hcursor
    val c0   = json.downField("rowGroups").downN(0).downField("columns").downN(0)
    c0.downField("pageIndex").get[String]("boundaryOrder") shouldBe Right("ASCENDING")
    c0.downField("pageIndex").downField("pages").downN(1).get[Boolean]("isNullPage") shouldBe Right(
      true
    )
    c0.downField("pages").downN(0).get[String]("pageType") shouldBe Right("DICTIONARY_PAGE")

    val plain = parser.parse(MetadataFormatter.formatJson(report)).toOption.get.hcursor
    plain
      .downField("rowGroups")
      .downN(0)
      .downField("columns")
      .downN(0)
      .downField("pages")
      .focus shouldBe
      Some(io.circe.Json.Null)
  }
}
