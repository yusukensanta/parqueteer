package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.*
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.schema.MessageTypeParser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files

/** Reads LIST/MAP annotations back from a real file's footer, not an in-memory schema. */
class NestedSchemaFileTest extends AnyFlatSpec with Matchers {

  private val message = MessageTypeParser.parseMessageType(
    """message m {
      |  required int64 id;
      |  optional group tags (LIST) {
      |    repeated group list {
      |      optional binary element (STRING);
      |    }
      |  }
      |  optional group attrs (MAP) {
      |    repeated group key_value {
      |      required binary key (STRING);
      |      optional binary value (STRING);
      |    }
      |  }
      |}""".stripMargin
  )

  private def writeFile(): ParquetFile = {
    val f = Files.createTempFile("parqueteer_nested_", ".parquet").toFile
    f.delete()
    f.deleteOnExit()
    val writer =
      ExampleParquetWriter.builder(new HadoopPath(f.getAbsolutePath)).withType(message).build()
    try writer.write(new SimpleGroupFactory(message).newGroup().append("id", 1L))
    finally writer.close()
    ParquetFile(LocalPath(f.getAbsolutePath))
  }

  private val repo = new HadoopParquetRepository()

  "schema read from a real footer" should "carry LIST and MAP group annotations on the leaves" in {
    val (schema, _, _) = repo.readFileInfo(writeFile()).get
    val byName         = schema.columns.map(c => c.name -> c).toMap
    byName("tags.list.element").groups.flatMap(_.annotation) shouldBe List("tags=LIST")
    byName("attrs.key_value.key").groups.flatMap(_.annotation) shouldBe List("attrs=MAP")
    byName("id").groups shouldBe Nil
  }

  "metadata read from a real footer" should "carry groups per column and a schema tree" in {
    val report = repo.readDetailedMetadata(writeFile()).get
    report.columns.find(_.path == "tags.list.element").get.groups.map(_.name) shouldBe
      List("tags", "list")
    val tags = report.schemaTree.get.children.find(_.name == "tags").get
    tags.logicalType.map(_.name) shouldBe Some("LIST")
    tags.children.head.children.map(_.name) shouldBe List("element")
  }
}
