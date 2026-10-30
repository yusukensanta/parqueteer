package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files

class MetadataReaderTest extends AnyFlatSpec with Matchers {

  private val repo = new HadoopParquetRepository()

  private def tempLocation(): LocalPath = {
    val f = Files.createTempFile("parqueteer_meta_", ".parquet").toFile
    f.delete() // Hadoop refuses to overwrite existing files; free the path
    f.deleteOnExit()
    LocalPath(f.getAbsolutePath)
  }

  private val rows: List[Map[String, CellValue]] = List(
    Map("id" -> CellValue.I64(1L), "name" -> CellValue.Str("Alice")),
    Map("id" -> CellValue.I64(2L), "name" -> CellValue.Null),
    Map("id" -> CellValue.I64(3L), "name" -> CellValue.Str("Carol"))
  )

  private def written(compression: CompressionType = CompressionType.Snappy) = {
    val loc = tempLocation()
    repo.writeContent(loc, rows, None, WriteConfig(compressionType = compression)).get
    repo.readDetailedMetadata(ParquetFile(loc)).get -> loc
  }

  "readDetailedMetadata" should "report file-level counts and size" in {
    val (report, loc) = written()
    report.file.rowCount shouldBe 3L
    report.file.rowGroupCount shouldBe 1
    report.file.columnCount shouldBe 2
    report.file.fileSize shouldBe new java.io.File(loc.path).length()
    report.file.modifiedAt shouldBe defined
    report.file.version should (be("1.0") or be("2.0"))
  }

  it should "include the message schema text" in {
    val (report, _) = written()
    report.messageSchema should include("id")
    report.messageSchema should include("name")
  }

  it should "include the structured schema tree" in {
    val (report, _) = written()
    report.schemaTree.map(_.children.map(_.name)) shouldBe Some(List("id", "name"))
    report.schemaTree.get.children.head.physicalType shouldBe Some("INT64")
    report.columns.forall(_.groups.isEmpty) shouldBe true
  }

  it should "describe each leaf column's schema" in {
    val (report, _) = written()
    report.columns.map(_.path) shouldBe List("id", "name")
    val id = report.columns.find(_.path == "id").get
    id.physicalType shouldBe "INT64"
    id.maxRepetitionLevel shouldBe 0
    val name = report.columns.find(_.path == "name").get
    name.physicalType shouldBe "BINARY"
    name.repetition shouldBe "OPTIONAL"
    name.maxDefinitionLevel shouldBe 1
    name.typeLength shouldBe None
  }

  it should "report per-chunk codec, sizes, counts and statistics" in {
    val (report, _) = written(CompressionType.Gzip)
    val rg          = report.rowGroups.head
    rg.rowCount shouldBe 3L
    rg.index shouldBe 0
    rg.startOffset should be > 0L
    rg.compressedBytes should be > 0L
    rg.uncompressedBytes should be > 0L

    val id = rg.columns.find(_.path == "id").get
    id.codec shouldBe "GZIP"
    id.valueCount shouldBe 3L
    id.encodings should not be empty
    id.nullCount shouldBe Some(0L)
    id.minValue shouldBe Some("1")
    id.maxValue shouldBe Some("3")

    val name = rg.columns.find(_.path == "name").get
    name.nullCount shouldBe Some(1L)
    name.hasDictionary shouldBe name.dictionaryPageOffset.isDefined
  }

  it should "expose key-value metadata as a sorted list (empty when the writer set none)" in {
    val (report, _) = written()
    report.keyValueMetadata.map(_._1) shouldBe report.keyValueMetadata.map(_._1).sorted
  }

  it should "fail for a file that does not exist" in {
    repo
      .readDetailedMetadata(ParquetFile(LocalPath("/no/such/file.parquet")))
      .isFailure shouldBe true
  }

  it should "fail for a file that is not parquet" in {
    val f = Files.createTempFile("parqueteer_notparquet_", ".parquet")
    Files.writeString(f, "this is definitely not a parquet file")
    f.toFile.deleteOnExit()
    repo.readDetailedMetadata(ParquetFile(LocalPath(f.toString))).isFailure shouldBe true
  }
}
