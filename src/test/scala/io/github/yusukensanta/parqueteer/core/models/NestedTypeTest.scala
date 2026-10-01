package io.github.yusukensanta.parqueteer.core.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NestedTypeTest extends AnyFlatSpec with Matchers {

  "NestedType.isNested" should "recognise STRUCT, MAP and LIST type names" in {
    NestedType.isNested("STRUCT<a:INT32>") shouldBe true
    NestedType.isNested("MAP<STRING,INT64>") shouldBe true
    NestedType.isNested("LIST<STRING>") shouldBe true
  }

  it should "reject primitive and logical type names" in {
    List("INT64", "STRING", "DECIMAL(10,2)", "TIMESTAMP_MILLIS").foreach(
      NestedType.isNested(_) shouldBe false
    )
  }

  "NestedType.struct" should "produce a name isNested recognises" in {
    NestedType.isNested(NestedType.struct("a:INT32")) shouldBe true
    NestedType.struct("a:INT32") shouldBe "STRUCT<a:INT32>"
  }

  "FieldSummary.isNested" should "delegate to NestedType" in {
    FieldSummary("s", "STRUCT<x:INT32>", isOptional = true).isNested shouldBe true
    FieldSummary("id", "INT64", isOptional = false).isNested shouldBe false
  }
}
