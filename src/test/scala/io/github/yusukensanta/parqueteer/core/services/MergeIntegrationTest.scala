package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models._
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.models.SchemaMode
import org.scalatest.Tag
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import org.apache.parquet.schema.{MessageType, Types}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.schema.Type.Repetition

object MergeIntegrationTest extends Tag("IntegrationTest")

class MergeIntegrationTest extends AnyFlatSpec with Matchers {

  private val repo = new ParquetRepository()
  private val service = new ParquetService(repo)

  private def tempFile(): java.io.File = {
    val f = Files.createTempFile("parqueteer_merge_", ".parquet").toFile
    f.delete()
    f.deleteOnExit()
    f
  }

  private def writeTemp(data: List[Map[String, Any]]): String = {
    val f = tempFile()
    repo
      .writeContent(LocalPath(f.getAbsolutePath), data, None)
      .fold(e => throw e, _ => f.getAbsolutePath)
  }

  private def writeTempWithSchema(schema: MessageType): String = {
    import org.apache.parquet.hadoop.example.ExampleParquetWriter
    val f = tempFile()
    val conf = new org.apache.hadoop.conf.Configuration()
    val writer = ExampleParquetWriter
      .builder(new org.apache.hadoop.fs.Path(f.getAbsolutePath))
      .withType(schema)
      .withConf(conf)
      .build()
    writer.close()
    f.getAbsolutePath
  }

  private val data1 = List(
    Map("id" -> 1L, "name" -> "Alice"),
    Map("id" -> 2L, "name" -> "Bob")
  )
  private val data2 = List(
    Map("id" -> 3L, "name" -> "Charlie"),
    Map("id" -> 4L, "name" -> "Dana")
  )

  // ── Strict mode ─────────────────────────────────────────────────────────

  "ParquetService.mergeFiles" should "merge two identical-schema files" taggedAs MergeIntegrationTest in {
    val in1 = writeTemp(data1)
    val in2 = writeTemp(data2)
    val out = tempFile().getAbsolutePath

    val result =
      service.mergeFiles(List(in1, in2), out, WriteConfig(), SchemaMode.Strict)
    result.isRight shouldBe true
    result.toOption.get shouldBe 4L

    val content =
      repo.readContent(ParquetFile(LocalPath(out)), ReadConfig()).get
    content.rows should have length 4
    content.rows.map(_("id")) should contain allOf (1L, 2L, 3L, 4L)
  }

  it should "fail strict merge when schemas differ" taggedAs MergeIntegrationTest in {
    val in1 = writeTemp(List(Map("id" -> 1L, "name" -> "Alice")))
    val in2 = writeTemp(List(Map("id" -> 2L, "score" -> 99.0)))
    val out = tempFile().getAbsolutePath

    val result =
      service.mergeFiles(List(in1, in2), out, WriteConfig(), SchemaMode.Strict)
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("Schema mismatch")
  }

  it should "fail strict merge when nested struct schemas differ" taggedAs MergeIntegrationTest in {
    val schemaWithStreet = Types
      .buildMessage()
      .addField(
        Types
          .optionalGroup()
          .addField(
            Types
              .primitive(PrimitiveTypeName.BINARY, Repetition.OPTIONAL)
              .named("street")
          )
          .named("address")
      )
      .named("msg")
    val schemaWithZip = Types
      .buildMessage()
      .addField(
        Types
          .optionalGroup()
          .addField(
            Types
              .primitive(PrimitiveTypeName.INT32, Repetition.OPTIONAL)
              .named("zip")
          )
          .named("address")
      )
      .named("msg")

    val in1 = writeTempWithSchema(schemaWithStreet)
    val in2 = writeTempWithSchema(schemaWithZip)
    val out = tempFile().getAbsolutePath

    val result =
      service.mergeFiles(List(in1, in2), out, WriteConfig(), SchemaMode.Strict)
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("Schema mismatch")
  }

  it should "detect type conflict in union merge when nested struct schemas differ" taggedAs MergeIntegrationTest in {
    val schemaWithStreet = Types
      .buildMessage()
      .addField(
        Types
          .optionalGroup()
          .addField(
            Types
              .primitive(PrimitiveTypeName.BINARY, Repetition.OPTIONAL)
              .named("street")
          )
          .named("address")
      )
      .named("msg")
    val schemaWithZip = Types
      .buildMessage()
      .addField(
        Types
          .optionalGroup()
          .addField(
            Types
              .primitive(PrimitiveTypeName.INT32, Repetition.OPTIONAL)
              .named("zip")
          )
          .named("address")
      )
      .named("msg")

    val in1 = writeTempWithSchema(schemaWithStreet)
    val in2 = writeTempWithSchema(schemaWithZip)
    val out = tempFile().getAbsolutePath

    val result =
      service.mergeFiles(List(in1, in2), out, WriteConfig(), SchemaMode.Union)
    result.isLeft shouldBe true
    result.left.toOption.get.userMessage should include("Type conflicts")
  }

  it should "fail with fewer than two input files" taggedAs MergeIntegrationTest in {
    val in1 = writeTemp(data1)
    val out = tempFile().getAbsolutePath

    val result =
      service.mergeFiles(List(in1), out, WriteConfig(), SchemaMode.Strict)
    result.isLeft shouldBe true
  }

  it should "fail with empty input list" taggedAs MergeIntegrationTest in {
    val out = tempFile().getAbsolutePath
    val result =
      service.mergeFiles(List.empty, out, WriteConfig(), SchemaMode.Strict)
    result.isLeft shouldBe true
  }

  // ── Union mode ──────────────────────────────────────────────────────────

  it should "merge files with different schemas in union mode" taggedAs MergeIntegrationTest in {
    val in1 = writeTemp(List(Map("id" -> 1L, "name" -> "Alice")))
    val in2 = writeTemp(List(Map("id" -> 2L, "score" -> 99.0)))
    val out = tempFile().getAbsolutePath

    val result =
      service.mergeFiles(List(in1, in2), out, WriteConfig(), SchemaMode.Union)
    result.isRight shouldBe true
    result.toOption.get shouldBe 2L

    val rows =
      repo.readContent(ParquetFile(LocalPath(out)), ReadConfig()).get.rows
    rows should have length 2
  }

  it should "call progress callback for each input file" taggedAs MergeIntegrationTest in {
    val in1 = writeTemp(data1)
    val in2 = writeTemp(data2)
    val out = tempFile().getAbsolutePath

    val calls = scala.collection.mutable.ListBuffer[(Int, Int, String)]()
    service.mergeFiles(
      List(in1, in2),
      out,
      WriteConfig(),
      SchemaMode.Strict,
      (i, n, p) => calls += ((i, n, p))
    )

    calls should have length 2
    calls(0)._1 shouldBe 1
    calls(1)._1 shouldBe 2
    calls(0)._2 shouldBe 2
  }
}
