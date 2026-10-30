package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.*
import org.apache.parquet.format.{AesGcmV1, EncryptionAlgorithm, SortingColumn}
import org.apache.parquet.io.LocalInputFile
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Paths}

class ExtendedMetadataTest extends AnyFlatSpec with Matchers {

  private val repo = new HadoopParquetRepository()

  private val rows: List[Map[String, CellValue]] = List(
    Map("id" -> CellValue.I64(1L), "name" -> CellValue.Str("Alice")),
    Map("id" -> CellValue.I64(2L), "name" -> CellValue.Null)
  )

  private def written(): LocalPath = {
    val f = Files.createTempFile("parqueteer_ext_", ".parquet").toFile
    f.delete()
    f.deleteOnExit()
    val loc = LocalPath(f.getAbsolutePath)
    repo.writeContent(loc, rows, None).get
    loc
  }

  "readDetailedMetadata" should "omit every extended section by default" in {
    val report = repo.readDetailedMetadata(ParquetFile(written())).get
    report.extended shouldBe None
    report.rowGroups.flatMap(_.extended) shouldBe Nil
    report.rowGroups.flatMap(_.columns).flatMap(_.extended) shouldBe Nil
  }

  it should "add file, row group and chunk detail when extended" in {
    val report = repo.readDetailedMetadata(ParquetFile(written()), extended = true).get
    report.extended.get.columnOrders shouldBe List("TYPE_DEFINED_ORDER", "TYPE_DEFINED_ORDER")
    report.extended.get.encryptionAlgorithm shouldBe None
    val rg = report.rowGroups.head.extended.get
    rg.ordinal shouldBe Some(0)
    rg.rowIndexOffset shouldBe Some(0L)
    rg.sortingColumns shouldBe Nil

    val id = report.rowGroups.head.columns.find(_.path == "id").get.extended.get
    id.dataPages.map(_.encoding) should contain("PLAIN")
    id.dataPages.map(_.pages).sum should be > 0
    id.usesV2Pages shouldBe Some(false)
    id.isEncrypted shouldBe false
    id.columnIndex.map(_.length.exists(_ > 0)) shouldBe Some(true)
    id.offsetIndex shouldBe defined
    id.bloomFilter shouldBe None
  }

  "MetadataReader.build with a raw footer" should "surface sorting columns, distinct count, exactness and encryption" in {
    val loc = written()
    val len = new java.io.File(loc.path).length()
    val footer =
      FooterReader.readFooterInfo(new LocalInputFile(Paths.get(loc.path)).newStream(), len)
    val raw =
      FooterReader.readRawFileMetaData(new LocalInputFile(Paths.get(loc.path)).newStream(), len)

    val rg0 = raw.getRow_groups.get(0)
    rg0.addToSorting_columns(new SortingColumn(1, true, true))
    val stats = rg0.getColumns.get(0).getMeta_data.getStatistics
    stats.setDistinct_count(2L)
    stats.setIs_min_value_exact(true)
    stats.setIs_max_value_exact(false)
    raw.setEncryption_algorithm(EncryptionAlgorithm.AES_GCM_V1(new AesGcmV1()))
    raw.setFooter_signing_key_metadata(Array[Byte](1))

    val report = MetadataReader.build(footer, len, None, Some(raw))
    report.extended.get.encryptionAlgorithm shouldBe Some("AES_GCM_V1")
    report.extended.get.hasFooterSigningKey shouldBe true
    report.rowGroups.head.extended.get.sortingColumns shouldBe
      List(SortingColumnDetail("name", descending = true, nullsFirst = true))
    val chunk = report.rowGroups.head.columns.head.extended.get
    chunk.distinctCount shouldBe Some(2L)
    chunk.isMinExact shouldBe Some(true)
    chunk.isMaxExact shouldBe Some(false)
  }
}
