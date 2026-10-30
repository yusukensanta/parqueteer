package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.toParqueteerError
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.services.LocationParsing.{
  parseLocation,
  parseLocations
}
import io.github.yusukensanta.parqueteer.core.filters.FilterParser
import io.github.yusukensanta.parqueteer.core.util.{GlobDetector, RowBudget}
import scala.util.Try

class ParquetService(
    repository: ParquetRepository
) {
  private val streamWrite = new StreamWriteSupport(repository)
  private val merges      = new MergeOperations(repository, streamWrite)
  private val rawWrites   = new RawToParquetWriter(repository, streamWrite)

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
    val budget                         = RowBudget(readConfig.maxRows)
    var total                          = 0L
    var error: Option[ParqueteerError] = None
    val it                             = locations.iterator
    while error.isEmpty && it.hasNext && !budget.isExhausted do {
      val perFileConfig = readConfig.copy(maxRows = budget.nextLimit)
      repository
        .streamContent(ParquetFile(it.next()), perFileConfig)(process)
        .toParqueteerError match {
        case Left(e) => error = Some(e)
        case Right(n) =>
          budget.consume(n)
          total += n
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
    merges.mergeFiles(inputPaths, outputPath, writeConfig, schemaMode, onProgress, fileParallelism)

  def checkSchemaCompatibility(
      paths: List[String],
      schemaMode: SchemaMode
  ): Either[ParqueteerError, Unit] =
    merges.checkSchemaCompatibility(paths, schemaMode)

  /**
   * Stream parquet → parquet conversion without loading the entire file into
   * memory; deletes partial output on failure.
   */
  def convertParquetFile(
      inputPath: String,
      outputPath: String,
      conversionConfig: ConversionConfig
  ): Either[ParqueteerError, Long] =
    merges.convertParquetFile(inputPath, outputPath, conversionConfig)

  def getStats(path: String): Either[ParqueteerError, FileStats] =
    for {
      location <- parseLocation(path)
      file = ParquetFile(location)
      stats <- repository
        .readStats(file)
        .toParqueteerError
    } yield stats

  def getDetailedMetadata(
      path: String,
      options: MetadataOptions = MetadataOptions()
  ): Either[ParqueteerError, ParquetMetadataReport] =
    for {
      location <- parseLocation(path)
      report <- repository
        .readDetailedMetadata(ParquetFile(location), options)
        .toParqueteerError
    } yield report

  def writeFile(
      path: String,
      data: List[Map[String, CellValue]],
      writeConfig: WriteConfig = WriteConfig(),
      schema: Option[ParquetSchema] = None
  ): Either[ParqueteerError, Unit] =
    rawWrites.writeFile(path, data, writeConfig, schema)

  def readDataFile(
      path: String,
      inputFormat: InputFormat,
      stdin: java.io.InputStream = System.in,
      maxRows: Option[Long] = None
  ): Either[ParqueteerError, List[Map[String, CellValue]]] =
    rawWrites.readDataFile(path, inputFormat, stdin, maxRows)

  /** Bounded-memory write of a non-parquet data file; see [[RawToParquetWriter]]. */
  def streamWriteDataFile(
      inputPath: String,
      inputFormat: InputFormat,
      outputPath: String,
      writeConfig: WriteConfig = WriteConfig(),
      maxRows: Option[Long] = None,
      explicitFields: Option[List[FieldSummary]] = None
  ): Either[ParqueteerError, Long] =
    rawWrites.streamWriteDataFile(
      inputPath,
      inputFormat,
      outputPath,
      writeConfig,
      maxRows,
      explicitFields
    )

  /** Writes several raw input files into ONE parquet output; see [[RawToParquetWriter]]. */
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
    rawWrites.writeMultiRawToParquet(
      paths,
      inputFormat,
      outputPath,
      writeConfig,
      schemaMode,
      onProgress,
      fileParallelism,
      explicitFields
    )

  /**
   * Reads a schema contract file (the `schema --format json` output). A
   * missing file is FileNotFound; an unreadable one, ParseError.
   */
  def readSchemaContract(contractPath: String): Either[ParqueteerError, List[FieldSummary]] =
    for {
      contractJson <- Try(
        java.nio.file.Files.readString(java.nio.file.Paths.get(contractPath))
      ).toEither.left.map {
        // java.nio reports a missing file as NoSuchFileException, which the
        // generic Try mapping would turn into IOError (exit 1), not FileNotFound.
        case _: java.nio.file.NoSuchFileException => ParqueteerError.FileNotFound(contractPath)
        case e                                    => ParqueteerError.IOError(e)
      }
      fields <- SchemaContract
        .parse(contractJson)
        .left
        .map(msg => ParqueteerError.ParseError("schema contract", s"$contractPath: $msg"))
    } yield fields

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

  /**
   * Compares `path`'s schema against a schema contract file (see
   * SchemaContract): the contract is the "before" side of the diff, the file
   * the "after", so `added` columns are ones the contract doesn't declare.
   * A missing contract file is FileNotFound; an unreadable one, ParseError.
   */
  def checkSchemaContract(path: String, contractPath: String): Either[ParqueteerError, SchemaDiff] =
    for {
      expected <- readSchemaContract(contractPath)
      file     <- getFileInfo(path)
    } yield SchemaReconciler.diff(
      expected.map(f => ColumnInfo(f.name, f.dataType, f.isOptional, 0, 0, "")),
      file.schema.map(_.columns).getOrElse(Nil)
    )

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
