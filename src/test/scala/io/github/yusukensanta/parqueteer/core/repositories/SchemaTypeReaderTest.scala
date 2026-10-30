package io.github.yusukensanta.parqueteer.core.repositories

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SchemaTypeReaderTest extends AnyFlatSpec with Matchers {

  private val tree                = SchemaTypeReader.tree(LogicalTypeFixtures.message)
  private def child(name: String) = tree.children.find(_.name == name).get

  "SchemaTypeReader.tree" should "root the tree at the message with no repetition" in {
    tree.name shouldBe "m"
    tree.repetition shouldBe None
    tree.children.map(_.name).take(3) shouldBe List("created_at", "local_time", "small_id")
  }

  it should "annotate LIST and MAP groups and keep their children" in {
    val tags = child("tags")
    tags.logicalType.map(_.name) shouldBe Some("LIST")
    tags.physicalType shouldBe None
    tags.children.map(_.name) shouldBe List("list")
    tags.children.head.children.map(_.name) shouldBe List("element")
    child("attrs").logicalType.map(_.name) shouldBe Some("MAP")
  }

  it should "describe leaves with physical type, length and parameterised logical type" in {
    child("id").typeLength shouldBe Some(16)
    child("id").physicalType shouldBe Some("FIXED_LEN_BYTE_ARRAY")
    child("created_at").logicalType.map(_.render) shouldBe Some(
      "TIMESTAMP(unit=MICROS, isAdjustedToUTC=true)"
    )
    child("addr").logicalType shouldBe None
    child("addr").children.map(_.name) shouldBe List("city")
  }

  "SchemaTypeReader.groupsAbove" should "list ancestors outermost first" in {
    SchemaTypeReader
      .groupsAbove(LogicalTypeFixtures.message, Array("tags", "list", "element"))
      .map(_.name) shouldBe List("tags", "list")
    SchemaTypeReader.groupsAbove(LogicalTypeFixtures.message, Array("plain")) shouldBe Nil
  }
}
