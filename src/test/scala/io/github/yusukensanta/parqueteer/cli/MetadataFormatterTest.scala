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
}
