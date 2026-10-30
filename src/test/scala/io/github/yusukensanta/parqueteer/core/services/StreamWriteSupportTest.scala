package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.cli.CliTestSupport
import io.github.yusukensanta.parqueteer.core.models.*
import scala.util.{Failure, Success}

class StreamWriteSupportTest extends CliTestSupport {

  private val support = new StreamWriteSupport(new FakeParquetRepository())

  "StreamWriteSupport.projectRow" should "reorder fields and fill missing ones with Null" in {
    val names = Array("b", "a", "c")
    val index = names.zipWithIndex.toMap
    val row   = Map[String, CellValue]("a" -> CellValue.I64(1L), "b" -> CellValue.Str("x"))
    val out   = support.projectRow(row, names, index)
    out.keys.toList shouldBe List("b", "a", "c")
    out("c") shouldBe CellValue.Null
  }

  it should "drop fields that are not in the target order" in {
    val names = Array("a")
    val out = support.projectRow(
      Map("a" -> CellValue.I64(1L), "extra" -> CellValue.I64(2L)),
      names,
      names.zipWithIndex.toMap
    )
    out.keys.toList shouldBe List("a")
  }

  "StreamWriteSupport.buildExplicitSchema" should "mark optional fields with definition level 1" in {
    val schema = support.buildExplicitSchema(
      List(
        FieldSummary("id", "INT64", isOptional = false),
        FieldSummary("name", "STRING", isOptional = true)
      ),
      "SNAPPY"
    )
    schema.columns.map(c => (c.name, c.isOptional, c.maxDefinitionLevel)) shouldBe
      List(("id", false, 0), ("name", true, 1))
    schema.columns.map(_.compressionType).distinct shouldBe List("SNAPPY")
  }

  "StreamWriteSupport.abortOnReadError" should "throw on a failed read and be a no-op on success" in {
    noException should be thrownBy support.abortOnReadError(Success(1L))
    an[RuntimeException] should be thrownBy support.abortOnReadError(
      Failure(new java.io.IOException("boom"))
    )
  }

  "StreamWriteSupport.handleStreamWriteResult" should "pass a successful count through" in {
    support.handleStreamWriteResult(LocalPath("/tmp/out.parquet"), Success(5L)) shouldBe Right(5L)
  }

  it should "map an already-exists failure to OutputExists without deleting" in {
    val out = LocalPath("/tmp/out.parquet")
    val res = support.handleStreamWriteResult(
      out,
      Failure(new java.nio.file.FileAlreadyExistsException("/tmp/out.parquet"))
    )
    res shouldBe Left(ParqueteerError.OutputExists(out.path))
  }
}
