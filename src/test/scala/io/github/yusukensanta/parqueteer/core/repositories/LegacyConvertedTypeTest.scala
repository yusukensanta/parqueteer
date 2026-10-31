package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.*
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.format.Util
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.io.LocalInputFile
import org.apache.parquet.schema.MessageTypeParser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Paths}
import java.nio.{ByteBuffer, ByteOrder}
import scala.jdk.CollectionConverters.*

/**
 * Files from older writers carry only the deprecated ConvertedType, no
 * LogicalType. The report must describe them the same way, including the
 * `isAdjustedToUTC=true` the spec implies for TIMESTAMP_MILLIS/MICROS.
 */
class LegacyConvertedTypeTest extends AnyFlatSpec with Matchers {

  private val message = MessageTypeParser.parseMessageType(
    """message m {
      |  required int64 ts (TIMESTAMP_MILLIS);
      |  optional int64 ts_micros (TIMESTAMP_MICROS);
      |  optional int32 day (DATE);
      |  optional int32 small (UINT_16);
      |  optional binary name (UTF8);
      |  optional int32 price (DECIMAL(9,2));
      |}""".stripMargin
  )

  private def readFooter(
      bytes: Array[Byte]
  ): (Array[Byte], org.apache.parquet.format.FileMetaData) = {
    val footerLen =
      ByteBuffer.wrap(bytes, bytes.length - 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt
    val dataEnd = bytes.length - 8 - footerLen
    val footer  = Util.readFileMetaData(
      new java.io.ByteArrayInputStream(bytes, dataEnd, footerLen)
    )
    (bytes.take(dataEnd), footer)
  }

  /** Writes a file, then rewrites its footer with every LogicalType removed. */
  private def legacyOnlyFile(): ParquetFile = {
    val f = Files.createTempFile("parqueteer_legacy_", ".parquet").toFile
    f.delete()
    f.deleteOnExit()
    val writer =
      ExampleParquetWriter.builder(new HadoopPath(f.getAbsolutePath)).withType(message).build()
    try writer.write(new SimpleGroupFactory(message).newGroup().append("ts", 1L))
    finally writer.close()

    val (data, footer) = readFooter(Files.readAllBytes(f.toPath))
    footer.getSchema.asScala.foreach(_.unsetLogicalType())
    footer.getSchema.asScala.count(_.isSetLogicalType) shouldBe 0

    val newFooter = new ByteArrayOutputStream()
    Util.writeFileMetaData(footer, newFooter)
    val len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(newFooter.size()).array()
    Files.write(
      f.toPath,
      data ++ newFooter.toByteArray ++ len ++ "PAR1".getBytes("US-ASCII")
    )
    ParquetFile(LocalPath(f.getAbsolutePath))
  }

  private val repo = new HadoopParquetRepository()

  "a ConvertedType-only file" should "really have no LogicalType in its footer" in {
    val file = legacyOnlyFile()
    val len  = new java.io.File(file.location.path).length()
    val raw  = FooterReader.readRawFileMetaData(
      new LocalInputFile(Paths.get(file.location.path)).newStream(),
      len
    )
    raw.getSchema.asScala.exists(_.isSetLogicalType) shouldBe false
    raw.getSchema.asScala.exists(_.isSetConverted_type) shouldBe true
  }

  it should "report TIMESTAMP_MILLIS/MICROS as adjusted to UTC" in {
    val (schema, _, _) = repo.readFileInfo(legacyOnlyFile()).get
    val byName         = schema.columns.map(c => c.name -> c).toMap
    byName("ts").logicalType.map(_.render) shouldBe Some(
      "TIMESTAMP(unit=MILLIS, isAdjustedToUTC=true)"
    )
    byName("ts_micros").logicalType.map(_.render) shouldBe Some(
      "TIMESTAMP(unit=MICROS, isAdjustedToUTC=true)"
    )
  }

  it should "map the other converted types to their logical types" in {
    val (schema, _, _) = repo.readFileInfo(legacyOnlyFile()).get
    val byName         = schema.columns.map(c => c.name -> c).toMap
    byName("day").logicalType.map(_.name) shouldBe Some("DATE")
    byName("small").logicalType.map(_.render) shouldBe Some("INTEGER(bitWidth=16, isSigned=false)")
    byName("name").logicalType.map(_.name) shouldBe Some("STRING")
    byName("price").logicalType.map(_.render) shouldBe Some("DECIMAL(precision=9, scale=2)")
  }

  it should "keep the compact dataType and expose the converted type" in {
    val (schema, _, _) = repo.readFileInfo(legacyOnlyFile()).get
    val byName         = schema.columns.map(c => c.name -> c).toMap
    byName("ts").dataType shouldBe "TIMESTAMP_MILLIS"
    byName("price").dataType shouldBe "DECIMAL(9,2)"
    byName("ts").convertedType shouldBe Some("TIMESTAMP_MILLIS")
    byName("small").convertedType shouldBe Some("UINT_16")
  }
}
