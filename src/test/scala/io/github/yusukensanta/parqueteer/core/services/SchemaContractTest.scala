package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.FieldSummary
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SchemaContractTest extends AnyFlatSpec with Matchers {

  "SchemaContract.parse" should "read name, dataType and optional and ignore other keys" in {
    val json =
      """{
        |  "totalRowCount": 3,
        |  "rowGroupCount": 1,
        |  "columns": [
        |    { "name": "id", "dataType": "INT64", "optional": false, "encodings": ["PLAIN"] },
        |    { "name": "name", "dataType": "STRING", "optional": true, "encodings": [] }
        |  ]
        |}""".stripMargin
    SchemaContract.parse(json) shouldBe Right(
      List(FieldSummary("id", "INT64", false), FieldSummary("name", "STRING", true))
    )
  }

  it should "reject invalid JSON" in {
    SchemaContract.parse("{ not json").left.toOption.get should startWith("not valid JSON")
  }

  it should "reject a document without a columns array" in {
    SchemaContract.parse("""{"fields": []}""").left.toOption.get should include("\"columns\" array")
  }

  it should "reject an empty columns array" in {
    SchemaContract.parse("""{"columns": []}""").left.toOption.get should include(
      "must not be empty"
    )
  }

  it should "name the first malformed column" in {
    val json =
      """{"columns": [{"name": "id", "dataType": "INT64", "optional": false}, {"name": "x"}]}"""
    SchemaContract.parse(json).left.toOption.get should include("column #2")
  }
}
