package io.github.yusukensanta.parqueteer.cli

import io.circe.{parser, Json}
import io.github.yusukensanta.parqueteer.core.formatters.TableFormatter
import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.repositories.LogicalTypeFixtures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scopt.OParser

class SchemaDetailTest extends AnyFlatSpec with Matchers {

  private val schema            = LogicalTypeFixtures.schema
  private def col(name: String) = schema.columns.find(_.name == name).get

  "FooterReader.buildParquetSchema" should "keep the compact dataType unchanged" in {
    col("created_at").dataType shouldBe "TIMESTAMP_MICROS"
    col("status").dataType shouldBe "STRING"
    col("amount").dataType shouldBe "DECIMAL(38,9)"
  }

  it should "expose TIMESTAMP unit and isAdjustedToUTC" in {
    col("created_at").physicalType shouldBe "INT64"
    col("created_at").logicalType.map(_.render) shouldBe Some(
      "TIMESTAMP(unit=MICROS, isAdjustedToUTC=true)"
    )
  }

  it should "expose TIME, INTEGER and DECIMAL parameters" in {
    col("local_time").logicalType.map(_.render) shouldBe Some(
      "TIME(unit=MILLIS, isAdjustedToUTC=false)"
    )
    col("small_id").logicalType.map(_.render) shouldBe Some(
      "INTEGER(bitWidth=16, isSigned=false)"
    )
    col("amount").logicalType.map(_.render) shouldBe Some("DECIMAL(precision=38, scale=9)")
  }

  it should "tell ENUM, JSON, UUID and DATE apart where dataType collapses them" in {
    col("status").logicalType.map(_.name) shouldBe Some("ENUM")
    col("payload").logicalType.map(_.name) shouldBe Some("JSON")
    col("id").logicalType.map(_.name) shouldBe Some("UUID")
    col("day").logicalType.map(_.name) shouldBe Some("DATE")
  }

  it should "report FIXED_LEN_BYTE_ARRAY length and no length for other types" in {
    col("id").typeLength shouldBe Some(16)
    col("created_at").typeLength shouldBe None
  }

  it should "leave logicalType empty for an unannotated column and carry the converted type" in {
    col("plain").logicalType shouldBe None
    col("plain").convertedType shouldBe None
    col("status").convertedType shouldBe Some("ENUM")
  }

  "CliOutputFormatter.formatSchemaJson" should "add structured physicalType/logicalType fields" in {
    val file = ParquetFile(LocalPath("/tmp/x.parquet"), schema = Some(schema))
    val cols = parser
      .parse(CliOutputFormatter.formatSchemaJson(file))
      .toOption
      .get
      .hcursor
      .downField("columns")
      .focus
      .get
      .asArray
      .get
    def obj(name: String) = cols.find(_.hcursor.get[String]("name") == Right(name)).get

    val ts = obj("created_at").hcursor
    ts.get[String]("dataType") shouldBe Right("TIMESTAMP_MICROS")
    ts.get[String]("physicalType") shouldBe Right("INT64")
    ts.downField("logicalType").get[String]("type") shouldBe Right("TIMESTAMP")
    ts.downField("logicalType").get[String]("unit") shouldBe Right("MICROS")
    ts.downField("logicalType").get[Boolean]("isAdjustedToUTC") shouldBe Right(true)

    obj("small_id").hcursor.downField("logicalType").get[Int]("bitWidth") shouldBe Right(16)
    obj("id").hcursor.get[Int]("typeLength") shouldBe Right(16)
    obj("plain").hcursor.downField("logicalType").focus shouldBe Some(Json.Null)
  }

  "TableFormatter.formatSchemaDetail" should "show physical type with length and the full annotation" in {
    val out = new TableFormatter().formatSchemaDetail(schema)
    out should include("FIXED_LEN_BYTE_ARRAY(16)")
    out should include("TIMESTAMP(unit=MICROS, isAdjustedToUTC=true)")
    out should include("INTEGER(bitWidth=16, isSigned=false)")
    out.linesIterator.filter(_.contains("plain")).toList.head should include("-")
  }

  "ArgumentParser schema --detail" should "set the detail flag, defaulting to false" in {
    def parse(args: String*) =
      OParser
        .parse(ArgumentParser.parser, args.toArray, ArgumentParser.Config())
        .get
        .command
        .get
        .asInstanceOf[SchemaCommand]
    parse("schema", "/tmp/a.parquet").detail shouldBe false
    parse("schema", "/tmp/a.parquet", "--detail").detail shouldBe true
  }

  "InspectCommands.formatSchemaText" should "switch to the detail table only when asked" in {
    val file = ParquetFile(LocalPath("/tmp/x.parquet"), schema = Some(schema))
    InspectCommands.formatSchemaText(file, OutputFormat.Table) should not include "isAdjustedToUTC"
    InspectCommands.formatSchemaText(file, OutputFormat.Table, detail = true) should include(
      "isAdjustedToUTC=true"
    )
  }

  "schema --format json" should "still parse as a schema contract" in {
    val file = ParquetFile(LocalPath("/tmp/x.parquet"), schema = Some(schema))
    val json = CliOutputFormatter.formatSchemaJson(file)
    io.github.yusukensanta.parqueteer.core.services.SchemaContract
      .parse(json)
      .map(_.map(_.name)) shouldBe Right(schema.columns.map(_.name))
  }
}
