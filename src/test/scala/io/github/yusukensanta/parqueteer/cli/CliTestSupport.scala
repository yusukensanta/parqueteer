package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.services.ParquetService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.{ByteArrayOutputStream, PrintStream}
import java.time.Instant
import scala.util.{Success, Try}

/**
 * Shared fixtures for the cli handler tests: a configurable fake repository,
 * default file content/schema/metadata/stats, GlobalOptions presets and a
 * stderr capture helper.
 */
trait CliTestSupport extends AnyFlatSpec with Matchers {

  protected class FakeParquetRepository(
      contentResult: Try[FileContent] = Success(defaultContent),
      schemaResult: Try[ParquetSchema] = Success(defaultSchema),
      metadataResult: Try[FileMetadata] = Success(defaultMetadata),
      validateResult: Try[List[String]] = Success(List.empty),
      writeResult: Try[Unit] = Success(()),
      statsResult: Try[FileStats] = Success(defaultStats),
      schemaFieldsResult: Try[List[FieldSummary]] = Success(List.empty),
      deleteResult: Try[Unit] = Success(()),
      inferSchemaResult: Try[ParquetSchema] = Success(defaultSchema)
  ) extends ParquetRepository {

    override def readContent(file: ParquetFile, config: ReadConfig): Try[FileContent] =
      contentResult
    override def readSchema(file: ParquetFile): Try[ParquetSchema]  = schemaResult
    override def readMetadata(file: ParquetFile): Try[FileMetadata] = metadataResult

    override def readFileInfo(
        file: ParquetFile
    ): Try[(ParquetSchema, FileMetadata, List[RowGroupInfo])] =
      for {
        s <- schemaResult
        m <- metadataResult
      } yield (s, m, Nil)
    override def validateFile(file: ParquetFile, deep: Boolean): Try[List[String]] = validateResult

    override def writeContent(
        location: StorageLocation,
        data: List[Map[String, CellValue]],
        schema: Option[ParquetSchema],
        config: WriteConfig
    ): Try[Unit] = writeResult

    override def streamContent(file: ParquetFile, config: ReadConfig)(
        process: Map[String, CellValue] => Unit
    ): Try[Long] =
      contentResult.map { fc =>
        fc.rows.foreach(process); fc.rows.length.toLong
      }

    override def writeContentStream(
        location: StorageLocation,
        schema: ParquetSchema,
        config: WriteConfig
    )(feed: (Map[String, CellValue] => Unit) => Unit): Try[Long] =
      writeResult.map { _ =>
        var c = 0L; feed(_ => c += 1); c
      }
    override def readStats(file: ParquetFile): Try[FileStats]                 = statsResult
    override def readSchemaFields(file: ParquetFile): Try[List[FieldSummary]] = schemaFieldsResult
    override def deleteFile(location: StorageLocation): Try[Unit]             = deleteResult

    override def inferSchemaFromRows(
        rows: Iterator[Map[String, CellValue]]
    ): Try[ParquetSchema] = { val _ = rows; inferSchemaResult }
  }

  protected val defaultContent = FileContent(
    rows = List(Map("id" -> CellValue.I64(1L), "name" -> CellValue.Str("Alice"))),
    totalRows = 1L,
    isPartial = false
  )

  protected val defaultSchema = ParquetSchema(
    columns = List(ColumnInfo("id", "INT64", isOptional = false, 1, 0, "SNAPPY")),
    rowGroupCount = 1L,
    totalRowCount = 1L
  )

  protected val defaultMetadata = FileMetadata(
    fileSize = 512L,
    createdAt = Some(Instant.parse("2024-01-01T00:00:00Z")),
    modifiedAt = None,
    compressionRatio = None,
    version = "2.0",
    createdBy = Some("test")
  )

  protected val defaultStats = FileStats(
    columns = List(ColumnStats("id", "INT64", 0L, Some("1"), Some("100"))),
    totalRows = 1L,
    rowGroupCount = 1L
  )

  protected val quietOpts   = GlobalOptions(quiet = true)
  protected val defaultOpts = GlobalOptions(quiet = false)

  protected def newService(repo: FakeParquetRepository = new FakeParquetRepository()) =
    new ParquetService(repo)

  protected def captureStderr[A](block: => A): (A, String) = {
    val baos = new ByteArrayOutputStream()
    val ps   = new PrintStream(baos)
    val old  = System.err
    System.setErr(ps)
    try {
      val result = block
      ps.flush()
      (result, baos.toString("UTF-8"))
    } finally System.setErr(old)
  }
}
