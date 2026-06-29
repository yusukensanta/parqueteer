package io.github.yusukensanta.parqueteer.cli

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

  "InputFormat.toServiceString" should "map Json to json" in {
    InputFormat.toServiceString(InputFormat.Json) shouldBe "json"
  }

  it should "map NDJson to ndjson" in {
    InputFormat.toServiceString(InputFormat.NDJson) shouldBe "ndjson"
  }

  it should "map Csv to csv" in {
    InputFormat.toServiceString(InputFormat.Csv) shouldBe "csv"
  }

  it should "map Ltsv to ltsv" in {
    InputFormat.toServiceString(InputFormat.Ltsv) shouldBe "ltsv"
  }

  it should "round-trip with fromString for all variants" in {
    InputFormat.values.foreach { fmt =>
      val str    = InputFormat.toServiceString(fmt)
      val parsed = InputFormat.fromString(str)
      parsed shouldBe Some(fmt)
    }
  }
}
