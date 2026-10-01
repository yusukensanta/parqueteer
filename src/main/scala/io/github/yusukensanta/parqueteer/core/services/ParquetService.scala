package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.toParqueteerError
import io.github.yusukensanta.parqueteer.core.models.StorageLocationParser
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.filters.FilterParser
import io.github.yusukensanta.parqueteer.core.util.{
  CredentialRedactor,
  Eithers,
  GlobDetector,
  RowLimiter,
  RowPipeline
}
import scala.util.Try

class ParquetService(
    repository: ParquetRepository
) {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  private def parseLocation(
      path: String
  ): Either[ParqueteerError, StorageLocation] =
    StorageLocationParser
      .parse(path)
      .left
      .map(msg => ParqueteerError.InvalidFormat(path, msg))

  private def requireNotStdin(path: String): Either[ParqueteerError, Unit] =
    if path == "-" then
      Left(
        ParqueteerError.UnsupportedOperation(
          "stdin",
          "Parquet files require random-access I/O and cannot be read from stdin"
        )
      )
    else Right(())

  private def validateFilter(
      filter: Option[String]
  ): Either[ParqueteerError, Unit] =
    filter
      .map(FilterParser.parse(_).map(_ => ()))
      .getOrElse(Right(()))

  private def prepareRead(
      path: String,
      readConfig: ReadConfig
  ): Either[ParqueteerError, ParquetFile] =
    for {
      _        <- requireNotStdin(path)
      _        <- validateFilter(readConfig.filter)
      location <- parseLocation(path)
    } yield ParquetFile(location)

  def readFile(
      path: String,
      readConfig: ReadConfig = ReadConfig()
  ): Either[ParqueteerError, ParquetFile] =
    for {
      file <- prepareRead(path, readConfig)
      schemaAndMeta <- repository
        .readFileInfo(file)
        .toParqueteerError
      (schema, metadata, rowGroups) = schemaAndMeta
      content <- repository
        .readContent(file, readConfig)
        .toParqueteerError
    } yield file.copy(
      content = Some(content),
      schema = Some(schema),
      metadata = Some(metadata),
      rowGroups = rowGroups
    )

  def streamRead(
      path: String,
      readConfig: ReadConfig
  )(process: Map[String, CellValue] => Unit): Either[ParqueteerError, Long] =
    for {
      file <- prepareRead(path, readConfig)
      count <- repository
        .streamContent(file, readConfig)(
          process
        )
        .toParqueteerError
    } yield count

  def streamReadMulti(
      paths: List[String],
      readConfig: ReadConfig,
      schemaMode: SchemaMode
  )(process: Map[String, CellValue] => Unit): Either[ParqueteerError, Long] =
    for {
      _         <- validateFilter(readConfig.filter)
      _         <- checkSchemaCompatibility(paths, schemaMode)
      locations <- parseLocations(paths)
      total     <- streamAllFiles(locations, readConfig)(process)
    } yield total

  /**
   * Streams rows from each location in order into `process`, decrementing a
   * shared row budget (readConfig.maxRows) across files so --limit applies
   * to the concatenated total, not per file.
   */
  private def streamAllFiles(
      locations: Vector[StorageLocation],
      readConfig: ReadConfig
  )(process: Map[String, CellValue] => Unit): Either[ParqueteerError, Long] = {
    var remaining                      = readConfig.maxRows
    var total                          = 0L
    var error: Option[ParqueteerError] = None
    val it                             = locations.iterator
    while error.isEmpty && it.hasNext && !remaining.contains(0L) do {
      val loc           = it.next()
      val perFileConfig = readConfig.copy(maxRows = remaining)
      repository
        .streamContent(ParquetFile(loc), perFileConfig) { row =>
          process(row)
          total += 1
          remaining = remaining.map(_ - 1)
        }
        .toParqueteerError match {
        case Left(e)  => error = Some(e)
        case Right(_) => ()
      }
    }
    error.toLeft(total)
  }

  def getFileInfo(path: String): Either[ParqueteerError, ParquetFile] =
    for {
      location <- parseLocation(path)
      file = ParquetFile(location)
      result <- repository
        .readFileInfo(file)
        .toParqueteerError
      (schema, metadata, rowGroups) = result
    } yield file.copy(
      schema = Some(schema),
      metadata = Some(metadata),
      rowGroups = rowGroups
    )

  def resolveGlob(path: String): Either[ParqueteerError, List[String]] =
    if !GlobDetector.hasGlobChars(path) then Right(List(path))
    else
      for {
        location <- parseLocation(path)
        matches  <- repository.globStatus(location).toParqueteerError
        paths <-
          if matches.isEmpty then Left(ParqueteerError.NoGlobMatch(path))
          else Right(matches.map(_.path).sorted)
      } yield paths

  def mergeFiles(
      inputPaths: List[String],
      outputPath: String,
      writeConfig: WriteConfig,
      schemaMode: SchemaMode,
      onProgress: (Int, Int, String) => Unit = (_, _, _) => (),
      fileParallelism: Int = 1
  ): Either[ParqueteerError, Long] =
    if inputPaths.size < 2 then
      Left(
        ParqueteerError.UnsupportedOperation(
          "merge",
          "merge requires at least two input files"
        )
      )
    else
      for {
        inputLocations <- parseLocations(inputPaths)
        schemas        <- readAllSchemas(inputLocations)
        mergedFields   <- SchemaReconciler.reconcile(schemas, inputPaths, schemaMode)
        outputLocation <- parseLocation(outputPath)
        count <- streamMerge(
          inputLocations,
          inputPaths,
          mergedFields,
          outputLocation,
          writeConfig,
          onProgress,
          fileParallelism
        )
      } yield count

  def checkSchemaCompatibility(
      paths: List[String],
      schemaMode: SchemaMode
  ): Either[ParqueteerError, Unit] =
    for {
      locations <- parseLocations(paths)
      schemas   <- readAllSchemas(locations)
      _         <- SchemaReconciler.reconcile(schemas, paths, schemaMode)
    } yield ()

  /**
   * Lift a list of paths into a single Either of parsed locations,
   * short-circuiting on the first malformed path.
   */
  private def parseLocations(
      paths: List[String]
  ): Either[ParqueteerError, Vector[StorageLocation]] =
    Eithers.traverse(paths)(parseLocation)

  /**
   * Read the field-summary schema for each input file, preserving order. Stops
   * on the first read failure.
   */
  private def readAllSchemas(
      locations: Vector[StorageLocation]
  ): Either[ParqueteerError, Vector[List[FieldSummary]]] =
    Eithers.traverse(locations)(loc =>
      repository.readSchemaFields(ParquetFile(loc)).toParqueteerError
    )

  /**
   * Thrown inside the writeContentStream feed callback to abort streaming on
   * read error. Propagates through writeContentStream's Try wrapper so the
   * partial output can be cleaned up before returning the real error.
   */
  private class MergeStreamException(val error: ParqueteerError)
      extends RuntimeException(error.userMessage, null, true, false)

  private def abortOnReadError(result: scala.util.Try[?]): Unit =
    result match {
      case scala.util.Failure(err) =>
        throw new MergeStreamException(
          scala.util
            .Failure(err)
            .toParqueteerError
            .fold(identity, _ => ParqueteerError.IOError(err))
        )
      case _ =>
    }

  private def deletePartialOutput(outputLocation: StorageLocation): Unit =
    repository.deleteFile(outputLocation) match {
      case scala.util.Failure(delErr) =>
        logger.warn(
          s"Failed to delete partial output at ${outputLocation.path}: ${CredentialRedactor
              .redact(delErr.getMessage)}. Partial file may remain."
        )
      case _ =>
    }

  // True when the writer never created the output file (pre-existence check),
  // so we must NOT delete a file this operation didn't write.
  private def isOutputAlreadyExistsError(ex: Throwable): Boolean =
    ex match {
      case _: org.apache.hadoop.fs.FileAlreadyExistsException => true
      case _: java.nio.file.FileAlreadyExistsException        => true
      case _ =>
        Option(ex.getMessage).exists(m =>
          m.contains("already exists") || m.contains("File already exists")
        )
    }

  private def handleStreamWriteResult(
      outputLocation: StorageLocation,
      writeResult: scala.util.Try[Long]
  ): Either[ParqueteerError, Long] =
    writeResult match {
      case scala.util.Failure(ex: MergeStreamException) =>
        deletePartialOutput(outputLocation)
        Left(ex.error)
      case scala.util.Failure(ex) if isOutputAlreadyExistsError(ex) =>
        Left(ParqueteerError.OutputExists(outputLocation.path))
      case scala.util.Failure(ex) =>
        deletePartialOutput(outputLocation)
        scala.util.Failure(ex).toParqueteerError
      case other => other.toParqueteerError
    }

  /**
   * Builds an explicit ParquetSchema from merged/read field summaries — shared
   * by every stream-write path (merge, parquet→parquet convert, multi-raw→parquet)
   * that writes with a schema known up front rather than inferred per-row.
   */
  private def buildExplicitSchema(
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
  private def projectRow(
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

  /**
   * Stream rows from each input file into a single output writer. On the first
   * read failure throws a sentinel exception through the writer so the partial
   * output is deleted before returning the real error. Returns the count of
   * rows written on success.
   */
  private def streamMerge(
      inputLocations: Vector[StorageLocation],
      inputPaths: List[String],
      mergedFields: List[FieldSummary],
      outputLocation: StorageLocation,
      writeConfig: WriteConfig,
      onProgress: (Int, Int, String) => Unit,
      fileParallelism: Int
  ): Either[ParqueteerError, Long] = {
    val nestedFields = mergedFields.filter(f =>
      f.dataType.startsWith("STRUCT") || f.dataType
        .startsWith("MAP") || f.dataType.startsWith("LIST")
    )
    if nestedFields.nonEmpty then
      Left(
        ParqueteerError.UnsupportedOperation(
          "merge",
          s"Cannot merge files containing nested columns: ${nestedFields.map(_.name).mkString(", ")}. " +
            "Flatten STRUCT/MAP/LIST columns before merging."
        )
      )
    else {
      // compression controlled by WriteConfig, not ColumnInfo
      val explicitSchema                = buildExplicitSchema(mergedFields, compressionCodec = "")
      val fieldNames                    = mergedFields.map(_.name).toArray
      val nameToIndex: Map[String, Int] = fieldNames.zipWithIndex.toMap
      val writeResult = repository
        .writeContentStream(outputLocation, explicitSchema, writeConfig) { write =>
          // Up to `fileParallelism` input files are fetched concurrently
          // ahead of `write`, each buffered through a bounded queue — see
          // RowPipeline. Output row order still matches inputLocations order
          // regardless of which file's cloud fetch happens to finish first.
          val pipelineResult = RowPipeline.run(
            inputLocations.zipWithIndex.toList,
            fileParallelism
          ) { case ((loc, i), sink) =>
            onProgress(i + 1, inputLocations.size, inputPaths(i))
            repository
              .streamContent(ParquetFile(loc), ReadConfig())(row =>
                sink(projectRow(row, fieldNames, nameToIndex))
              )
              .toEither
          }(write)
          abortOnReadError(pipelineResult.toTry)
        }

      handleStreamWriteResult(outputLocation, writeResult)
    }
  }

  /**
   * Stream parquet → parquet conversion without loading the entire file into
   * memory. Reads the source schema, opens a streaming writer for the output,
   * and feeds rows one at a time. Deletes partial output on failure.
   */
  def convertParquetFile(
      inputPath: String,
      outputPath: String,
      conversionConfig: ConversionConfig
  ): Either[ParqueteerError, Long] =
    for {
      inputLocation  <- parseLocation(inputPath)
      outputLocation <- parseLocation(outputPath)
      schemaFields <- repository
        .readSchemaFields(ParquetFile(inputLocation))
        .toParqueteerError
      explicitSchema = buildExplicitSchema(
        schemaFields,
        conversionConfig.writeConfig.compressionType.codecName
      )
      writeResult = repository.writeContentStream(
        outputLocation,
        explicitSchema,
        conversionConfig.writeConfig
      ) { write =>
        val readResult = repository
          .streamContent(
            ParquetFile(inputLocation),
            ReadConfig(maxRows = conversionConfig.maxRows)
          )(write)
        abortOnReadError(readResult)
      }
      count <- handleStreamWriteResult(outputLocation, writeResult)
    } yield count

  def getStats(path: String): Either[ParqueteerError, FileStats] =
    for {
      location <- parseLocation(path)
      file = ParquetFile(location)
      stats <- repository
        .readStats(file)
        .toParqueteerError
    } yield stats

  def writeFile(
      path: String,
      data: List[Map[String, CellValue]],
      writeConfig: WriteConfig = WriteConfig()
  ): Either[ParqueteerError, Unit] =
    for {
      location <- parseLocation(path)
      _ <- repository
        .writeContent(location, data, None, writeConfig)
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
      maxRows: Option[Long] = None
  ): Either[ParqueteerError, Long] =
    if inputPath != "-" && inputFormat.isStreamable then
      streamOneFormat(inputPath, outputPath, inputFormat, writeConfig, maxRows)
    else bufferedWriteDataFile(inputPath, inputFormat, outputPath, writeConfig, maxRows)

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
      maxRows: Option[Long]
  ): Either[ParqueteerError, Long] = {
    def withRows[A](f: Iterator[Map[String, CellValue]] => A): Try[A] =
      DataFileReader.withRows(inputFormat, inputPath, maxRows)(f)
    for {
      outputLocation <- parseLocation(outputPath)
      schema         <- withRows(repository.inferSchemaFromRows).flatten.toParqueteerError
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
      maxRows: Option[Long]
  ): Either[ParqueteerError, Long] =
    for {
      data <- readDataFile(inputPath, inputFormat, maxRows = maxRows)
      _    <- writeFile(outputPath, data, writeConfig)
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
      fileParallelism: Int = 1
  ): Either[ParqueteerError, Long] =
    if inputFormat.isStreamable then
      streamMultiRawToParquet(
        paths,
        inputFormat,
        outputPath,
        writeConfig,
        schemaMode,
        onProgress,
        fileParallelism
      )
    else bufferedMultiRawToParquet(paths, outputPath, writeConfig, schemaMode, onProgress)

  private def bufferedMultiRawToParquet(
      paths: List[String],
      outputPath: String,
      writeConfig: WriteConfig,
      schemaMode: SchemaMode,
      onProgress: (Int, Int, String) => Unit
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
      perFileSchemas <- Eithers.traverse(rowsPerFile)(inferOne)
      _              <- SchemaReconciler.reconcile(perFileSchemas, paths, schemaMode)
      allRows = rowsPerFile.toList.flatten
      rowCount <- writeFile(outputPath, allRows, writeConfig).map(_ => allRows.size.toLong)
    } yield rowCount
  }

  private def streamMultiRawToParquet(
      paths: List[String],
      inputFormat: InputFormat,
      outputPath: String,
      writeConfig: WriteConfig,
      schemaMode: SchemaMode,
      onProgress: (Int, Int, String) => Unit,
      fileParallelism: Int
  ): Either[ParqueteerError, Long] = {
    def withRows[A](path: String)(f: Iterator[Map[String, CellValue]] => Try[A]): Try[A] =
      DataFileReader.withRows(inputFormat, path, None)(f).flatten

    def inferOne(path: String): Either[ParqueteerError, List[FieldSummary]] =
      withRows(path)(repository.inferSchemaFromRows).toParqueteerError.map(
        _.columns.map(c => FieldSummary(c.name, c.dataType, c.isOptional))
      )

    for {
      outputLocation <- parseLocation(outputPath)
      perFileSchemas <- Eithers.traverse(paths)(inferOne)
      mergedFields   <- SchemaReconciler.reconcile(perFileSchemas, paths, schemaMode)
      explicitSchema = buildExplicitSchema(mergedFields, writeConfig.compressionType.codecName)
      fieldNames     = mergedFields.map(_.name).toArray
      nameToIndex    = fieldNames.zipWithIndex.toMap
      writeResult = repository.writeContentStream(outputLocation, explicitSchema, writeConfig) {
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
                    sink(projectRow(row, fieldNames, nameToIndex))
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

  def validateFile(
      path: String,
      deep: Boolean = false
  ): Either[ParqueteerError, ValidationResult] =
    for {
      location <- parseLocation(path)
      file = ParquetFile(location)
      issues <- repository
        .validateFile(file, deep)
        .toParqueteerError
    } yield ValidationResult(isValid = issues.isEmpty, issues = issues)

  def diffSchemas(
      path1: String,
      path2: String
  ): Either[ParqueteerError, SchemaDiff] =
    for {
      f1 <- getFileInfo(path1)
      f2 <- getFileInfo(path2)
    } yield SchemaReconciler.diff(
      f1.schema.map(_.columns).getOrElse(Nil),
      f2.schema.map(_.columns).getOrElse(Nil)
    )

}
