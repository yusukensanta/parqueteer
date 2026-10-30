package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.toParqueteerError
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.util.CredentialRedactor
import scala.util.{Failure, Try}

/**
 * Helpers shared by every stream-write path (merge, parquet→parquet convert,
 * multi-raw→parquet): abort-on-read-error signalling, partial-output cleanup,
 * explicit schema construction and row projection.
 */
private[services] class StreamWriteSupport(repository: ParquetRepository) {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  /**
   * Thrown inside the writeContentStream feed callback to abort streaming on
   * read error. Propagates through writeContentStream's Try wrapper so the
   * partial output can be cleaned up before returning the real error.
   */
  private class StreamAbortException(val error: ParqueteerError)
      extends RuntimeException(error.userMessage, null, true, false)

  def abortOnReadError(result: Try[?]): Unit =
    result match {
      case Failure(err) =>
        throw new StreamAbortException(
          scala.util
            .Failure(err)
            .toParqueteerError
            .fold(identity, _ => ParqueteerError.IOError(err))
        )
      case _ =>
    }

  private def deletePartialOutput(outputLocation: StorageLocation): Unit =
    repository.deleteFile(outputLocation) match {
      case Failure(delErr) =>
        logger.warn(
          s"Failed to delete partial output at ${outputLocation.path}: ${CredentialRedactor
              .redact(delErr.getMessage)}. Partial file may remain."
        )
      case _ =>
    }

  // True when the writer never created the output file (pre-existence check),
  // so we must NOT delete a file this operation didn't write. The message
  // fallback covers writers that surface the refusal as a plain IOException
  // (e.g. some Hadoop FileSystem implementations) rather than one of the
  // typed FileAlreadyExistsExceptions; keep it in sync with those messages.
  private def isOutputAlreadyExistsError(ex: Throwable): Boolean =
    ex match {
      case _: org.apache.hadoop.fs.FileAlreadyExistsException => true
      case _: java.nio.file.FileAlreadyExistsException        => true
      case _ =>
        Option(ex.getMessage).exists(m =>
          m.contains("already exists") || m.contains("File already exists")
        )
    }

  def handleStreamWriteResult(
      outputLocation: StorageLocation,
      writeResult: Try[Long]
  ): Either[ParqueteerError, Long] =
    writeResult match {
      case Failure(ex: StreamAbortException) =>
        deletePartialOutput(outputLocation)
        Left(ex.error)
      case Failure(ex) if isOutputAlreadyExistsError(ex) =>
        Left(ParqueteerError.OutputExists(outputLocation.path))
      case Failure(ex) =>
        deletePartialOutput(outputLocation)
        Failure(ex).toParqueteerError
      case other => other.toParqueteerError
    }

  /**
   * Builds an explicit ParquetSchema from merged/read field summaries — shared
   * by every stream-write path (merge, parquet→parquet convert, multi-raw→parquet)
   * that writes with a schema known up front rather than inferred per-row.
   */
  def buildExplicitSchema(
      fields: List[FieldSummary],
      compressionCodec: String
  ): ParquetSchema =
    ParquetSchema(
      columns = fields.map { f =>
        ColumnInfo(
          f.name,
          f.dataType,
          f.isOptional,
          if f.isOptional then 1 else 0,
          0,
          compressionCodec
        )
      },
      rowGroupCount = 1L,
      totalRowCount = 0L
    )

  /**
   * Projects a row onto a fixed field order in one pass instead of
   * `fieldNames.length` separate `row.getOrElse` ListMap lookups (each
   * O(row.size), so O(fieldNames.length * row.size) total) — iterating the
   * row once and resolving each key to an output slot via `nameToIndex` is
   * O(row.size + fieldNames.length) instead. Missing fields become
   * `CellValue.Null`. Shared by merge and multi-raw-input-to-parquet, both of
   * which reconcile per-file rows onto one merged column order.
   */
  def projectRow(
      row: Map[String, CellValue],
      fieldNames: Array[String],
      nameToIndex: Map[String, Int]
  ): Map[String, CellValue] = {
    val values: Array[CellValue] = Array.fill(fieldNames.length)(CellValue.Null)
    row.foreach { case (k, v) => nameToIndex.get(k).foreach(idx => values(idx) = v) }
    val builder = scala.collection.immutable.ListMap.newBuilder[String, CellValue]
    var j       = 0
    while j < fieldNames.length do { builder += fieldNames(j) -> values(j); j += 1 }
    builder.result()
  }
}
