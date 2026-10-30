package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.*
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.schema.MessageTypeParser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files

/** `metadata --index` / `--pages` against a real multi-page file. */
class PageDetailTest extends AnyFlatSpec with Matchers {

  private val message = MessageTypeParser.parseMessageType(
    """message m {
      |  required int64 id;
      |  optional binary name (UTF8);
      |  optional binary empty (UTF8);
      |}""".stripMargin
  )
  private val RowCount = 3000

  private lazy val file: ParquetFile = {
    val f = Files.createTempFile("parqueteer_pages_", ".parquet").toFile
    f.delete()
    f.deleteOnExit()
    val writer = ExampleParquetWriter
      .builder(new HadoopPath(f.getAbsolutePath))
      .withType(message)
      .withPageSize(512)
      .withDictionaryEncoding(true)
      .build()
    try {
      val factory = new SimpleGroupFactory(message)
      (0 until RowCount).foreach { i =>
        writer.write(factory.newGroup().append("id", i.toLong).append("name", s"n${i % 5}"))
      }
    } finally writer.close()
    ParquetFile(LocalPath(f.getAbsolutePath))
  }

  private val repo = new HadoopParquetRepository()

  private def chunk(report: ParquetMetadataReport, path: String) =
    report.rowGroups.head.columns.find(_.path == path).get

  "readDetailedMetadata" should "read neither index nor pages by default" in {
    val report = repo.readDetailedMetadata(file).get
    report.rowGroups.flatMap(_.columns).flatMap(_.pageIndex) shouldBe Nil
    report.rowGroups.flatMap(_.columns).flatMap(_.pages) shouldBe Nil
  }

  "--index" should "list every page with offset, first row and typed min/max" in {
    val pi =
      chunk(repo.readDetailedMetadata(file, MetadataOptions(index = true)).get, "id").pageIndex.get
    pi.pages.size should be > 1
    pi.boundaryOrder shouldBe Some("ASCENDING")
    pi.pages.head.firstRowIndex shouldBe Some(0L)
    pi.pages.head.minValue shouldBe Some("0")
    pi.pages.last.maxValue shouldBe Some((RowCount - 1).toString)
    pi.pages.flatMap(_.offset) shouldBe pi.pages.flatMap(_.offset).sorted
    pi.pages.flatMap(_.firstRowIndex) shouldBe pi.pages.flatMap(_.firstRowIndex).sorted
    pi.pages.map(_.nullCount).distinct shouldBe List(Some(0L))
  }

  it should "mark a page with only nulls as a null page without min/max" in {
    val pi = chunk(
      repo.readDetailedMetadata(file, MetadataOptions(index = true)).get,
      "empty"
    ).pageIndex.get
    pi.pages.forall(_.isNullPage.contains(true)) shouldBe true
    pi.pages.forall(p => p.minValue.isEmpty && p.maxValue.isEmpty) shouldBe true
    pi.pages.map(_.nullCount.getOrElse(0L)).sum shouldBe RowCount.toLong
  }

  "--pages" should "list page headers whose value counts add up to the chunk's" in {
    val report = repo.readDetailedMetadata(file, MetadataOptions(pages = true)).get
    val id     = chunk(report, "id")
    val pages  = id.pages.get
    pages.map(_.pageType).distinct shouldBe List("DATA_PAGE")
    pages.flatMap(_.valueCount).sum.toLong shouldBe id.valueCount
    pages.head.offset shouldBe id.dataPageOffset
    pages.sliding(2).foreach {
      case List(a, b) => b.offset shouldBe a.offset + a.headerBytes + a.compressedBytes
      case _          =>
    }
  }

  it should "show the dictionary page of a dictionary-encoded column first" in {
    val pages =
      chunk(repo.readDetailedMetadata(file, MetadataOptions(pages = true)).get, "name").pages.get
    pages.head.pageType shouldBe "DICTIONARY_PAGE"
    pages.head.valueCount shouldBe Some(5)
    pages.tail.map(_.pageType).distinct shouldBe List("DATA_PAGE")
  }

  "index and pages" should "agree on where each data page starts" in {
    val report = repo.readDetailedMetadata(file, MetadataOptions(index = true, pages = true)).get
    val id     = chunk(report, "id")
    id.pageIndex.get.pages.flatMap(_.offset) shouldBe
      id.pages.get.filter(_.pageType == "DATA_PAGE").map(_.offset)
    id.pageIndex.get.pages.flatMap(_.compressedBytes) shouldBe
      id.pages.get.filter(_.pageType == "DATA_PAGE").map(p => p.headerBytes + p.compressedBytes)
  }

  "--columns" should "restrict index and pages to the named columns" in {
    val report = repo
      .readDetailedMetadata(
        file,
        MetadataOptions(index = true, pages = true, columns = Some(List("id")))
      )
      .get
    chunk(report, "id").pageIndex shouldBe defined
    chunk(report, "id").pages shouldBe defined
    chunk(report, "name").pageIndex shouldBe None
    chunk(report, "name").pages shouldBe None
  }
}
