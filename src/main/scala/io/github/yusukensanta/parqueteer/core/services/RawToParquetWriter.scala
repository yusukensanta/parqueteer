package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.toParqueteerError
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.services.LocationParsing.parseLocation
import io.github.yusukensanta.parqueteer.core.util.{Eithers, RowLimiter, RowPipeline}
import scala.util.Try

/**
 * Writes non-parquet input (JSON, NDJSON, CSV, LTSV) to parquet, either fully
 * buffered or with bounded memory via a two-pass infer-then-stream approach.
 */
private[services] class RawToParquetWriter(
    repository: ParquetRepository,
    streamWrite: StreamWriteSupport
) {
  import streamWrite.{abortOnReadError, buildExplicitSchema, handleStreamWriteResult, projectRow}

  def writeFile(
      path: String,
      data: List[Map[String, CellValue]],
      writeConfig: WriteConfig = WriteConfig(),
      schema: Option[ParquetSchema] = None
  ): Either[ParqueteerError, Unit] =
    for {
      location <- parseLocation(path)
      _        <- repository
        .writeContent(location, data, schema, writeConfig)
        .toParqueteerError
    } yield ()

  def readDataFile(
      path: String,
      inputFormat: InputFormat,
      stdin: java.io.InputStream = System.in,
      maxRows: Option[Long] = None
  ): Either[ParqueteerError, List[Map[String, CellValue]]] =
    if path == "-" then
      DataFileReader
        .readFromStdin(inputFormat, stdin)
        .map(RowLimiter.limitList(_, maxRows))
        .toParqueteerError
    else
      inputFormat match {
        case InputFormat.Json =>
          DataFileReader
            .readJsonFile(path)
            .map(RowLimiter.limitList(_, maxRows))
            .toParqueteerError
        case InputFormat.NDJson =>
          DataFileReader.readNdjsonFile(path, maxRows).toParqueteerError
        case InputFormat.Csv =>
          DataFileReader.readCsvFile(path, maxRows).toParqueteerError
        case InputFormat.Ltsv =>
          DataFileReader.readLtsvFile(path, maxRows).toParqueteerError
      }

  /**
   * Writes a (non-parquet) data file to parquet with bounded memory for NDJSON,
   * LTSV, and CSV inputs: a first pass folds over the source to infer a schema
   * without collecting rows into a List, then a second pass re-opens the same
   * source and streams rows straight into the writer. JSON-array input (a
   * whole-tree parse) and stdin (single-pass, can't be reopened) fall back to
   * the fully-buffered readDataFile + writeFile path unchanged.
   */
  def streamWriteDataFile(
      inputPath: String,
      inputFormat: InputFormat,
      outputPath: String,
      writeConfig: WriteConfig = WriteConfig(),
      maxRows: Option[Long] = None,
      explicitFields: Option[List[FieldSummary]] = None
  ): Either[ParqueteerError, Long] =
    explicitWriteSchema(explicitFields, writeConfig).flatMap { schema =>
      if inputPath != "-" && inputFormat.isStreamable then
        streamOneFormat(inputPath, outputPath, inputFormat, writeConfig, maxRows, schema)
      else bufferedWriteDataFile(inputPath, inputFormat, outputPath, writeConfig, maxRows, schema)
    }

  /**
   * Turns `--schema` fields into the ParquetSchema every write path uses, and
   * checks up front that each declared type is writable — so a typo in the
   * contract fails before any input is read, not after a full inference pass.
   */
  private def explicitWriteSchema(
      fields: Option[List[FieldSummary]],
      writeConfig: WriteConfig
  ): Either[ParqueteerError, Option[ParquetSchema]] =
    fields match {
      case None     => Right(None)
      case Some(fs) =>
        val schema = buildExplicitSchema(fs, writeConfig.compressionType.codecName)
        repository
          .validateWriteSchema(schema)
          .toEither
          .left
          .map(e => ParqueteerError.ParseError("schema contract", e.getMessage))
          .map(_ => Some(schema))
    }

  /**
   * Two-pass bounded-memory write for a single ndjson/ltsv/csv input: infer a
   * schema from one pass over `withRows`, then stream rows from a second pass
   * straight into the writer. `withRows` reopens `inputPath` fresh on each
   * call — see DataFileReader.withRows.
   */
  private def streamOneFormat(
      inputPath: String,
      outputPath: String,
      inputFormat: InputFormat,
      writeConfig: WriteConfig,
      maxRows: Option[Long],
      explicitSchema: Option[ParquetSchema]
  ): Either[ParqueteerError, Long] = {
    def withRows[A](f: Iterator[Map[String, CellValue]] => A): Try[A] =
      DataFileReader.withRows(inputFormat, inputPath, maxRows)(f)
    for {
      outputLocation <- parseLocation(outputPath)
      // With --schema the inference pass is skipped: one read of the input.
      schema <- explicitSchema
        .map(Right(_))
        .getOrElse(withRows(repository.inferSchemaFromRows).flatten.toParqueteerError)
      writeResult = withRows { rows =>
        repository.writeContentStream(outputLocation, schema, writeConfig)(feed =>
          rows.foreach(feed)
        )
      }.flatten
      count <- handleStreamWriteResult(outputLocation, writeResult)
    } yield count
  }

  private def bufferedWriteDataFile(
      inputPath: String,
      inputFormat: InputFormat,
      outputPath: String,
      writeConfig: WriteConfig,
      maxRows: Option[Long],
      explicitSchema: Option[ParquetSchema]
  ): Either[ParqueteerError, Long] =
    for {
      data <- readDataFile(inputPath, inputFormat, maxRows = maxRows)
      _    <- writeFile(outputPath, data, writeConfig, explicitSchema)
    } yield data.size.toLong

  /**
   * Writes multiple raw (non-parquet) input files into ONE parquet output,
   * concatenating their rows. JSON inputs are fully buffered (readDataFile
   * per file); NDJSON/CSV/LTSV inputs use the same bounded-memory two-pass
   * (infer schema, then stream rows) approach as streamWriteDataFile, with
   * the merged schema across all matched files computed the same way
   * mergeFiles does for merging existing parquet files.
   */
  def writeMultiRawToParquet(
      paths: List[String],
      inputFormat: InputFormat,
      outputPath: String,
      writeConfig: WriteConfig,
      schemaMode: SchemaMode,
      onProgress: (Int, Int, String) => Unit = (_, _, _) => (),
      fileParallelism: Int = 1,
      explicitFields: Option[List[FieldSummary]] = None
  ): Either[ParqueteerError, Long] =
    explicitWriteSchema(explicitFields, writeConfig).flatMap { schema =>
      if inputFormat.isStreamable then
        streamMultiRawToParquet(
          paths,
          inputFormat,
          outputPath,
          writeConfig,
          schemaMode,
          onProgress,
          fileParallelism,
          schema
        )
      else bufferedMultiRawToParquet(paths, outputPath, writeConfig, schemaMode, onProgress, schema)
    }

  private def bufferedMultiRawToParquet(
      paths: List[String],
      outputPath: String,
      writeConfig: WriteConfig,
      schemaMode: SchemaMode,
      onProgress: (Int, Int, String) => Unit,
      explicitSchema: Option[ParquetSchema]
  ): Either[ParqueteerError, Long] = {
    def inferOne(rows: List[Map[String, CellValue]]): Either[ParqueteerError, List[FieldSummary]] =
      repository
        .inferSchemaFromRows(rows.iterator)
        .toParqueteerError
        .map(_.columns.map(c => FieldSummary(c.name, c.dataType, c.isOptional)))

    for {
      rowsPerFile <- Eithers.traverse(paths.zipWithIndex) { (path, idx) =>
        onProgress(idx + 1, paths.size, path)
        readDataFile(path, InputFormat.Json)
      }
      // With --schema, each row is checked against the declared schema as it
      // is written instead, so per-file inference and reconciliation are moot.
      _ <-
        if explicitSchema.isDefined then Right(())
        else
          Eithers
            .traverse(rowsPerFile)(inferOne)
            .flatMap(SchemaReconciler.reconcile(_, paths, schemaMode))
            .map(_ => ())
      allRows = rowsPerFile.toList.flatten
      rowCount <- writeFile(outputPath, allRows, writeConfig, explicitSchema)
        .map(_ => allRows.size.toLong)
    } yield rowCount
  }

  private def streamMultiRawToParquet(
      paths: List[String],
      inputFormat: InputFormat,
      outputPath: String,
      writeConfig: WriteConfig,
      schemaMode: SchemaMode,
      onProgress: (Int, Int, String) => Unit,
      fileParallelism: Int,
      explicitSchema: Option[ParquetSchema]
  ): Either[ParqueteerError, Long] = {
    def withRows[A](path: String)(f: Iterator[Map[String, CellValue]] => Try[A]): Try[A] =
      DataFileReader.withRows(inputFormat, path, None)(f).flatten

    def inferOne(path: String): Either[ParqueteerError, List[FieldSummary]] =
      withRows(path)(repository.inferSchemaFromRows).toParqueteerError.map(
        _.columns.map(c => FieldSummary(c.name, c.dataType, c.isOptional))
      )

    for {
      outputLocation <- parseLocation(outputPath)
      // With --schema there is no inference pass: rows are written as-is and
      // checked against the declared schema (unknown column / missing
      // required column / uncoercible value fail the write).
      writeSchema <- explicitSchema.map(Right(_)).getOrElse {
        Eithers
          .traverse(paths)(inferOne)
          .flatMap(SchemaReconciler.reconcile(_, paths, schemaMode))
          .map(buildExplicitSchema(_, writeConfig.compressionType.codecName))
      }
      fieldNames  = writeSchema.columns.map(_.name).toArray
      nameToIndex = fieldNames.zipWithIndex.toMap
      writeResult = repository.writeContentStream(outputLocation, writeSchema, writeConfig) {
        write =>
          // See RowPipeline: up to fileParallelism input files are fetched
          // concurrently ahead of write, bounded per-file, without reordering
          // rows relative to paths.
          val pipelineResult = RowPipeline.run(paths.zipWithIndex.toList, fileParallelism) {
            case ((path, idx), sink) =>
              onProgress(idx + 1, paths.size, path)
              withRows(path) { rows =>
                Try {
                  var n = 0L
                  rows.foreach { row =>
                    sink(
                      if explicitSchema.isDefined then row
                      else projectRow(row, fieldNames, nameToIndex)
                    )
                    n += 1
                  }
                  n
                }
              }.toEither
          }(write)
          abortOnReadError(pipelineResult.toTry)
      }
      count <- handleStreamWriteResult(outputLocation, writeResult)
    } yield count
  }
}
