package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.*
import scala.util.{Failure, Success, Try}

/**
 * Public interface for Parquet file I/O. Implementations may choose to use
 * Hadoop, in-memory fakes for testing, etc.
 */
trait ParquetRepository {
  def readContent(file: ParquetFile, config: ReadConfig): Try[FileContent]

  def streamContent(
      file: ParquetFile,
      config: ReadConfig
  )(process: Map[String, CellValue] => Unit): Try[Long]

  def readFileInfo(
      file: ParquetFile
  ): Try[(ParquetSchema, FileMetadata, List[RowGroupInfo])]

  def writeContent(
      location: StorageLocation,
      data: List[Map[String, CellValue]],
      schema: Option[ParquetSchema],
      config: WriteConfig = WriteConfig()
  ): Try[Unit]

  def writeContentStream(
      location: StorageLocation,
      schema: ParquetSchema,
      config: WriteConfig
  )(feed: (Map[String, CellValue] => Unit) => Unit): Try[Long]

  // Infers a ParquetSchema from a single pass over rows, without ever materializing
  // them into a List — the schema-inference half of a two-pass streaming write
  // (infer, then writeContentStream). See HadoopParquetRepository for the bridge
  // through the internal MessageType-based inference used by writeContent.
  def inferSchemaFromRows(
      rows: Iterator[Map[String, CellValue]]
  ): Try[ParquetSchema]

  /**
   * Fails when a declared schema can't be written (unknown or unsupported
   * dataType), without touching storage. Defaults to accepting everything so
   * test doubles needn't implement it.
   */
  def validateWriteSchema(@scala.annotation.unused schema: ParquetSchema): Try[Unit] =
    scala.util.Success(())

  def validateFile(file: ParquetFile, deep: Boolean = false): Try[List[String]]
  def readSchemaFields(file: ParquetFile): Try[List[FieldSummary]]
  def deleteFile(location: StorageLocation): Try[Unit]
  def readStats(file: ParquetFile): Try[FileStats]

  /**
   * Full footer metadata (file, key-value, per-row-group and per-column-chunk
   * detail). Defaults to failing so fake repositories used in unit tests don't
   * have to implement it to keep compiling. `options` opts into the costlier
   * parts: rarely needed footer fields (`extended`, one more footer read) and
   * the column/offset indexes and page headers (`index`/`pages`, which read
   * the file beyond its footer).
   */
  def readDetailedMetadata(
      file: ParquetFile,
      options: MetadataOptions = MetadataOptions()
  ): Try[ParquetMetadataReport] = {
    val _ = (file, options)
    Failure(new UnsupportedOperationException("readDetailedMetadata is not implemented"))
  }

  /**
   * Expand a glob pattern (containing *, ?, [, {) into the concrete files it
   * matches, sorted by path. A location with no glob metacharacters is
   * expected to already have been filtered out by the caller — this default
   * passthrough exists only so fake repositories used in unit tests don't
   * need to implement glob matching to keep compiling.
   */
  def globStatus(location: StorageLocation): Try[List[StorageLocation]] =
    Success(List(location))

  def cacheStats(): ParquetRepository.CacheStats =
    ParquetRepository.CacheStats(0, 0, 0, 0)
}

object ParquetRepository {

  final case class CacheStats(
      footerHits: Long,
      footerMisses: Long,
      configHits: Long,
      configMisses: Long
  )
}
