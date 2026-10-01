package io.github.yusukensanta.parqueteer.core.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InputFormatTest extends AnyFlatSpec with Matchers {

  "InputFormat.fromString" should "parse json" in {
    InputFormat.fromString("json") shouldBe Some(InputFormat.Json)
  }

  it should "parse ndjson" in {
    InputFormat.fromString("ndjson") shouldBe Some(InputFormat.NDJson)
  }

  it should "parse csv" in {
    InputFormat.fromString("csv") shouldBe Some(InputFormat.Csv)
  }

  it should "parse ltsv" in {
    InputFormat.fromString("ltsv") shouldBe Some(InputFormat.Ltsv)
  }

  it should "be case-insensitive" in {
    InputFormat.fromString("JSON") shouldBe Some(InputFormat.Json)
    InputFormat.fromString("CSV") shouldBe Some(InputFormat.Csv)
    InputFormat.fromString("NDJSON") shouldBe Some(InputFormat.NDJson)
    InputFormat.fromString("LTSV") shouldBe Some(InputFormat.Ltsv)
  }

  it should "return None for unknown format" in {
    InputFormat.fromString("xml") shouldBe None
    InputFormat.fromString("parquet") shouldBe None
    InputFormat.fromString("") shouldBe None
  }

  "InputFormat.name" should "map each variant to its lowercase CLI/extension name" in {
    InputFormat.Json.name shouldBe "json"
    InputFormat.NDJson.name shouldBe "ndjson"
    InputFormat.Csv.name shouldBe "csv"
    InputFormat.Ltsv.name shouldBe "ltsv"
  }

  it should "round-trip with fromString for all variants" in {
    InputFormat.values.foreach(fmt => InputFormat.fromString(fmt.name) shouldBe Some(fmt))
  }

  "InputFormat.isStreamable" should "be false only for JSON arrays" in {
    InputFormat.values.filterNot(_.isStreamable).toList shouldBe List(InputFormat.Json)
  }

  "InputFormat.FromName" should "extract a format from a file extension" in {
    ("csv" match {
      case InputFormat.FromName(f) => Some(f)
      case _                       => None
    }) shouldBe Some(InputFormat.Csv)
    ("parquet" match {
      case InputFormat.FromName(f) => Some(f)
      case _                       => None
    }) shouldBe None
  }
}
