package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models._
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.{Success, Try}

class SchemaDiffTest extends AnyFlatSpec with Matchers {

  private class TwoSchemaRepository(
      schema1: ParquetSchema,
      schema2: ParquetSchema,
      metadata: FileMetadata
  ) extends ParquetRepository {
    override def readSchema(file: ParquetFile): Try[ParquetSchema] =
      if (file.location.path.contains("file1")) Success(schema1)
      else Success(schema2)
    override def readMetadata(file: ParquetFile): Try[FileMetadata] =
      Success(metadata)
    override def readContent(
        file: ParquetFile,
        config: ReadConfig
    ): Try[FileContent] =
      Success(FileContent(List.empty, 0, false))
    override def validateFile(file: ParquetFile): Try[List[String]] =
      Success(List.empty)
    override def writeContent(
        location: StorageLocation,
        data: List[Map[String, Any]],
        schema: Option[ParquetSchema],
        config: WriteConfig
    ): Try[Unit] = Success(())
  }

  // ── Tests ─────────────────────────────────────────────────────────────────

  private def col(
      name: String,
      dtype: String,
      optional: Boolean = false
  ): ColumnInfo =
    ColumnInfo(name, dtype, optional, 1, 0, "SNAPPY")

  private def schema(cols: ColumnInfo*): ParquetSchema =
    ParquetSchema(cols.toList, 1L, 10L)

  private val metadata = FileMetadata(512L, None, None, None, "2.0", None)

  "ParquetService.diffSchemas" should "report identical schemas" in {
    val s = schema(col("id", "INT64"), col("name", "BINARY"))
    val repo = new TwoSchemaRepository(s, s, metadata)
    val service = new ParquetService(repo)
    val result = service.diffSchemas("/file1.parquet", "/file2.parquet")
    result.isSuccess shouldBe true
    result.get.identical shouldBe true
    result.get.added shouldBe empty
    result.get.removed shouldBe empty
    result.get.changed shouldBe empty
    result.get.unchanged should contain allOf ("id", "name")
  }

  it should "detect added columns" in {
    val s1 = schema(col("id", "INT64"))
    val s2 = schema(col("id", "INT64"), col("email", "BINARY"))
    val service = new ParquetService(new TwoSchemaRepository(s1, s2, metadata))
    val result = service.diffSchemas("/file1.parquet", "/file2.parquet")
    result.isSuccess shouldBe true
    result.get.added.map(_.name) should contain("email")
    result.get.removed shouldBe empty
    result.get.identical shouldBe false
  }

  it should "detect removed columns" in {
    val s1 = schema(col("id", "INT64"), col("legacy", "BINARY"))
    val s2 = schema(col("id", "INT64"))
    val service = new ParquetService(new TwoSchemaRepository(s1, s2, metadata))
    val result = service.diffSchemas("/file1.parquet", "/file2.parquet")
    result.isSuccess shouldBe true
    result.get.removed.map(_.name) should contain("legacy")
    result.get.added shouldBe empty
  }

  it should "detect type changes" in {
    val s1 = schema(col("id", "INT32"))
    val s2 = schema(col("id", "INT64"))
    val service = new ParquetService(new TwoSchemaRepository(s1, s2, metadata))
    val result = service.diffSchemas("/file1.parquet", "/file2.parquet")
    result.isSuccess shouldBe true
    result.get.changed should have length 1
    result.get.changed.head.name shouldBe "id"
    result.get.changed.head.fromType shouldBe "INT32"
    result.get.changed.head.toType shouldBe "INT64"
  }

  it should "detect optionality changes" in {
    val s1 = schema(col("name", "BINARY", optional = false))
    val s2 = schema(col("name", "BINARY", optional = true))
    val service = new ParquetService(new TwoSchemaRepository(s1, s2, metadata))
    val result = service.diffSchemas("/file1.parquet", "/file2.parquet")
    result.isSuccess shouldBe true
    result.get.changed should have length 1
    result.get.changed.head.fromOptional shouldBe false
    result.get.changed.head.toOptional shouldBe true
  }

  it should "handle all change types simultaneously" in {
    val s1 =
      schema(col("id", "INT64"), col("name", "INT32"), col("old", "BINARY"))
    val s2 = schema(
      col("id", "INT64"),
      col("name", "BINARY"),
      col("new_col", "BOOLEAN")
    )
    val service = new ParquetService(new TwoSchemaRepository(s1, s2, metadata))
    val result = service.diffSchemas("/file1.parquet", "/file2.parquet")
    result.isSuccess shouldBe true
    result.get.unchanged should contain("id")
    result.get.removed.map(_.name) should contain("old")
    result.get.added.map(_.name) should contain("new_col")
    result.get.changed.map(_.name) should contain("name")
  }

  it should "return Failure for invalid file path" in {
    val s = schema(col("id", "INT64"))
    val service = new ParquetService(new TwoSchemaRepository(s, s, metadata))
    val result = service.diffSchemas("ftp://bad", "/file2.parquet")
    result.isFailure shouldBe true
  }
}
