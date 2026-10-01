package io.github.yusukensanta.parqueteer.core.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EnumParsingTest extends AnyFlatSpec with Matchers {

  "OutputFormat.fromString" should "parse every CLI name case-insensitively" in {
    val names =
      Map(
        "table"    -> OutputFormat.Table,
        "JSON"     -> OutputFormat.JSON,
        "csv"      -> OutputFormat.CSV,
        "Pretty"   -> OutputFormat.Pretty,
        "markdown" -> OutputFormat.Markdown,
        "ndjson"   -> OutputFormat.NDJSON,
        "LTSV"     -> OutputFormat.LTSV
      )
    names.foreach((name, fmt) => OutputFormat.fromString(name) shouldBe Some(fmt))
    OutputFormat.fromString("xml") shouldBe None
  }

  "CompressionType.fromString" should "parse names and aliases" in {
    CompressionType.fromString("none") shouldBe Some(CompressionType.Uncompressed)
    CompressionType.fromString("uncompressed") shouldBe Some(CompressionType.Uncompressed)
    CompressionType.fromString("GZ") shouldBe Some(CompressionType.Gzip)
    CompressionType.fromString("zstd") shouldBe Some(CompressionType.Zstd)
    CompressionType.fromString("bogus") shouldBe None
  }

  it should "parse every codec's own lowercase name" in {
    CompressionType.values.filterNot(_ == CompressionType.Uncompressed).foreach { ct =>
      CompressionType.fromString(ct.toString.toLowerCase) shouldBe Some(ct)
    }
  }

  "SchemaMode.fromString" should "parse strict and union case-insensitively" in {
    SchemaMode.fromString("strict") shouldBe Some(SchemaMode.Strict)
    SchemaMode.fromString("UNION") shouldBe Some(SchemaMode.Union)
    SchemaMode.fromString("loose") shouldBe None
  }
}
