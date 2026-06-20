package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.toParqueteerError
import io.github.yusukensanta.parqueteer.core.models.StorageLocationParser
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.filters.FilterParser

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
        ParqueteerError.InvalidFormat(
          "-",
          "Parquet files require random-access I/O and cannot be read from stdin"
        )
      )
    else Right(())

  def readFile(
      path: String,
      readConfig: ReadConfig = ReadConfig()
  ): Either[ParqueteerError, ParquetFile] =
    for {
      _ <- requireNotStdin(path)
      _ <- readConfig.filter
        .map(FilterParser.parse(_).map(_ => ()))
        .getOrElse(Right(()))
      location <- parseLocation(path)
      file = ParquetFile(location)
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
      _ <- requireNotStdin(path)
      _ <- readConfig.filter
        .map(FilterParser.parse(_).map(_ => ()))
        .getOrElse(Right(()))
      location <- parseLocation(path)
      file = ParquetFile(location)
      count <- repository
        .streamContent(file, readConfig)(
          process
        )
        .toParqueteerError
    } yield count

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

  def mergeFiles(
      inputPaths: List[String],
      outputPath: String,
      writeConfig: WriteConfig,
      schemaMode: SchemaMode,
      onProgress: (Int, Int, String) => Unit = (_, _, _) => ()
  ): Either[ParqueteerError, Long] =
    if inputPaths.size < 2 then
      Left(
        ParqueteerError.InvalidFormat(
          "merge",
          "merge requires at least two input files"
        )
      )
    else
      for {
        inputLocations <- parseLocations(inputPaths)
        schemas        <- readAllSchemas(inputLocations)
        mergedFields   <- mergeSchemas(schemas, inputPaths, schemaMode)
        outputLocation <- parseLocation(outputPath)
        count <- streamMerge(
          inputLocations,
          inputPaths,
          mergedFields,
          outputLocation,
          writeConfig,
          onProgress
        )
      } yield count

  /**
   * Lift a list of paths into a single Either of parsed locations,
   * short-circuiting on the first malformed path.
   */
  private def parseLocations(
      paths: List[String]
  ): Either[ParqueteerError, Vector[StorageLocation]] =
    paths.foldLeft[Either[ParqueteerError, Vector[StorageLocation]]](
      Right(Vector.empty)
    )((acc, p) => acc.flatMap(locs => parseLocation(p).map(locs :+ _)))

  /**
   * Read the field-summary schema for each input file, preserving order. Stops
   * on the first read failure.
   */
  private def readAllSchemas(
      locations: Vector[StorageLocation]
  ): Either[ParqueteerError, Vector[List[FieldSummary]]] =
    locations.foldLeft[Either[ParqueteerError, Vector[List[FieldSummary]]]](
      Right(Vector.empty)
    )((acc, loc) =>
      acc.flatMap(vec =>
        repository
          .readSchemaFields(ParquetFile(loc))
          .toParqueteerError
          .map(vec :+ _)
      )
    )

  /**
   * Combine per-file schemas under the chosen strategy:
   *   - Strict: every input must match the first file's schema exactly.
   *   - Union: collect the union of fields, surfacing per-column type
   *     conflicts as a single InvalidFormat error. Union-merged fields are
   *     marked optional because not every file is guaranteed to supply them.
   */
  private def mergeSchemas(
      schemas: Vector[List[FieldSummary]],
      inputPaths: List[String],
      schemaMode: SchemaMode
  ): Either[ParqueteerError, List[FieldSummary]] = schemaMode match {
    case SchemaMode.Strict =>
      if schemas.isEmpty then Right(Nil)
      else {
        val first    = schemas.head
        val firstSet = first.map(f => (f.name, f.dataType, f.isOptional)).toSet
        schemas.zipWithIndex
          .collectFirst {
            case (s, i)
                if s
                  .map(f => (f.name, f.dataType, f.isOptional))
                  .toSet != firstSet =>
              val thisSet          = s.map(f => (f.name, f.dataType, f.isOptional)).toSet
              val missing          = firstSet -- thisSet
              val extra            = thisSet -- firstSet
              val missingNames     = missing.map(_._1)
              val extraNames       = extra.map(_._1)
              val changedNames     = missingNames.intersect(extraNames)
              val onlyMissingNames = missingNames -- changedNames
              val onlyExtraNames   = extraNames -- changedNames
              val fmt              = (t: String, o: Boolean) => if o then s"$t?" else t
              val changedDetails = changedNames.toList.sorted.flatMap { name =>
                for {
                  (_, ft, fo) <- missing.find(_._1 == name)
                  (_, tt, to) <- extra.find(_._1 == name)
                } yield s"$name (${fmt(ft, fo)} → ${fmt(tt, to)})"
              }
              val diffMsg = List(
                if changedNames.nonEmpty then
                  s"type/nullability changed: ${changedDetails.mkString(", ")}"
                else "",
                if onlyMissingNames.nonEmpty then
                  s"missing: ${onlyMissingNames.toList.sorted.mkString(", ")}"
                else "",
                if onlyExtraNames.nonEmpty then
                  s"extra: ${onlyExtraNames.toList.sorted.mkString(", ")}"
                else ""
              ).filter(_.nonEmpty).mkString("; ")
              Left(
                ParqueteerError.InvalidFormat(
                  inputPaths(i),
                  s"Schema mismatch at file '${inputPaths(i)}' ($diffMsg). Use --schema-mode union to allow schema differences."
                )
              )
          }
          .getOrElse(Right(first))
      }

    case SchemaMode.Union =>
      // (dataType, requiredInAllInputsSoFar)
      val seen =
        scala.collection.mutable.LinkedHashMap.empty[String, (String, Boolean)]
      schemas.zipWithIndex
        .foldLeft[Either[ParqueteerError, Unit]](Right(())) { case (acc, (fields, fileIdx)) =>
          acc.flatMap { _ =>
            val duplicates = fields
              .groupBy(_.name)
              .collect { case (n, fs) if fs.size > 1 => n }
              .toList
              .sorted
            if duplicates.nonEmpty then
              Left(
                ParqueteerError.InvalidFormat(
                  "merge",
                  s"File at index $fileIdx has duplicate column names: ${duplicates
                      .mkString(", ")}. " +
                    "Parquet files with duplicate column names cannot be merged."
                )
              )
            else {
              val fieldMap = fields.view.map(f => f.name -> f).toMap
              val conflicts = fields.collect {
                case f if seen.get(f.name).exists(_._1 != f.dataType) =>
                  s"'${f.name}' (${seen(f.name)._1} vs ${f.dataType})"
              }
              if conflicts.nonEmpty then
                Left(
                  ParqueteerError.InvalidFormat(
                    "merge",
                    s"Type conflicts in union merge: ${conflicts.mkString(", ")}. " +
                      "Cannot union-merge columns with incompatible types."
                  )
                )
              else {
                // Fields already seen but absent from this schema → must become optional
                seen.keys.filterNot(fieldMap.contains).foreach { name =>
                  seen(name) = (seen(name)._1, false)
                }
                fields.foreach { f =>
                  seen.get(f.name) match {
                    case Some((dt, wasRequired)) =>
                      seen(f.name) = (dt, wasRequired && !f.isOptional)
                    case None =>
                      // New field: required only if it appears in the first file AND is marked required.
                      // A field first seen in a later file was absent from earlier files, so it must be optional.
                      val isRequired = fileIdx == 0 && !f.isOptional
                      seen(f.name) = (f.dataType, isRequired)
                  }
                }
                Right(())
              }
            } // end else (no duplicate names)
          }
        }
        .map(_ =>
          seen.map { case (name, (t, allRequired)) =>
            FieldSummary(name, t, isOptional = !allRequired)
          }.toList
        )
  }

  /**
   * Thrown inside the writeContentStream feed callback to abort streaming on
   * read error. Propagates through writeContentStream's Try wrapper so the
   * partial output can be cleaned up before returning the real error.
   */
  private class MergeStreamException(val error: ParqueteerError)
      extends RuntimeException(error.userMessage, null, true, false)

  private def deletePartialOutput(outputLocation: StorageLocation): Unit =
    repository.deleteFile(outputLocation) match {
      case scala.util.Failure(delErr) =>
        logger.warn(
          s"Failed to delete partial output at ${outputLocation.path}: ${delErr.getMessage}. Partial file may remain."
        )
      case _ =>
    }

  // True when the writer never created the output file (pre-existence check),
  // so we must NOT delete a file this operation didn't write.
  private def isOutputAlreadyExistsError(ex: Throwable): Boolean =
    ex.isInstanceOf[org.apache.hadoop.fs.FileAlreadyExistsException] ||
      ex.isInstanceOf[java.nio.file.FileAlreadyExistsException] ||
      Option(ex.getMessage).exists(m =>
        m.contains("already exists") || m.contains("File already exists")
      )

  private def handleStreamWriteResult(
      outputLocation: StorageLocation,
      writeResult: scala.util.Try[Long]
  ): Either[ParqueteerError, Long] =
    writeResult match {
      case scala.util.Failure(ex: MergeStreamException) =>
        deletePartialOutput(outputLocation)
        Left(ex.error)
      case scala.util.Failure(ex) if isOutputAlreadyExistsError(ex) =>
        Left(
          ParqueteerError.InvalidFormat(
            outputLocation.path,
            s"Output file already exists: ${outputLocation.path}. Remove it first or choose a different output path."
          )
        )
      case scala.util.Failure(ex) =>
        deletePartialOutput(outputLocation)
        scala.util.Failure(ex).toParqueteerError
      case other => other.toParqueteerError
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
      onProgress: (Int, Int, String) => Unit
  ): Either[ParqueteerError, Long] = {
    val nestedFields = mergedFields.filter(f =>
      f.dataType.startsWith("STRUCT") || f.dataType
        .startsWith("MAP") || f.dataType.startsWith("LIST")
    )
    if nestedFields.nonEmpty then
      Left(
        ParqueteerError.InvalidFormat(
          "merge",
          s"Cannot merge files containing nested columns: ${nestedFields.map(_.name).mkString(", ")}. " +
            "Flatten STRUCT/MAP/LIST columns before merging."
        )
      )
    else {
      val explicitSchema = ParquetSchema(
        columns = mergedFields.map { f =>
          ColumnInfo(
            f.name,
            f.dataType,
            f.isOptional,
            if f.isOptional then 1 else 0,
            0,
            "" // compression controlled by WriteConfig, not ColumnInfo
          )
        },
        rowGroupCount = 1L,
        totalRowCount = 0L
      )
      val fieldNames = mergedFields.map(_.name).toArray
      val writeResult = repository
        .writeContentStream(outputLocation, explicitSchema, writeConfig) { write =>
          inputLocations.zipWithIndex.foreach { case (loc, i) =>
            onProgress(i + 1, inputLocations.size, inputPaths(i))
            repository
              .streamContent(ParquetFile(loc), ReadConfig()) { row =>
                val builder = scala.collection.immutable.ListMap
                  .newBuilder[String, CellValue]
                var j = 0
                while j < fieldNames.length do {
                  val name = fieldNames(j)
                  builder += name -> row.getOrElse(name, CellValue.Null)
                  j += 1
                }
                write(builder.result())
              } match {
              case scala.util.Failure(err) =>
                throw new MergeStreamException(
                  scala.util
                    .Failure(err)
                    .toParqueteerError
                    .fold(identity, _ => ParqueteerError.IOError(err))
                )
              case _ =>
            }
          }
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
      explicitSchema = ParquetSchema(
        columns = schemaFields.map { f =>
          ColumnInfo(
            f.name,
            f.dataType,
            f.isOptional,
            if f.isOptional then 1 else 0,
            0,
            conversionConfig.writeConfig.compressionType.codecName
          )
        },
        rowGroupCount = 1L,
        totalRowCount = 0L
      )
      writeResult = repository.writeContentStream(
        outputLocation,
        explicitSchema,
        conversionConfig.writeConfig
      ) { write =>
        repository
          .streamContent(
            ParquetFile(inputLocation),
            ReadConfig(maxRows = conversionConfig.maxRows)
          )(write) match {
          case scala.util.Failure(err) =>
            throw new MergeStreamException(
              scala.util
                .Failure(err)
                .toParqueteerError
                .fold(identity, _ => ParqueteerError.IOError(err))
            )
          case _ =>
        }
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
      inputFormat: String,
      stdin: java.io.InputStream = System.in,
      maxRows: Option[Long] = None
  ): Either[ParqueteerError, List[Map[String, CellValue]]] =
    if path == "-" then
      DataFileReader
        .readFromStdin(inputFormat, stdin)
        .map(applyMaxRowsLimit(_, maxRows))
        .toParqueteerError
    else
      inputFormat.toLowerCase match {
        case "json" =>
          DataFileReader
            .readJsonFile(path)
            .map(applyMaxRowsLimit(_, maxRows))
            .toParqueteerError
        case "ndjson" =>
          DataFileReader.readNdjsonFile(path, maxRows).toParqueteerError
        case "csv" =>
          DataFileReader
            .readCsvFile(path)
            .map(applyMaxRowsLimit(_, maxRows))
            .toParqueteerError
        case "ltsv" =>
          DataFileReader.readLtsvFile(path, maxRows).toParqueteerError
        case fmt =>
          Left(
            ParqueteerError.InvalidFormat(
              path,
              s"Unsupported input format: $fmt. Supported: json, ndjson, csv, ltsv"
            )
          )
      }

  private def applyMaxRowsLimit(
      rows: List[Map[String, CellValue]],
      maxRows: Option[Long]
  ): List[Map[String, CellValue]] =
    maxRows.fold(rows) { limit =>
      // rows.length is Int-bounded; if limit >= length we keep all rows, otherwise limit.toInt is safe.
      if limit >= rows.length.toLong then rows else rows.take(limit.toInt)
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
    } yield {
      val cols1 = f1.schema.map(_.columns).getOrElse(List.empty)
      val cols2 = f2.schema.map(_.columns).getOrElse(List.empty)
      val map1  = cols1.map(c => c.name -> c).toMap
      val map2  = cols2.map(c => c.name -> c).toMap

      val added   = cols2.filterNot(c => map1.contains(c.name))
      val removed = cols1.filterNot(c => map2.contains(c.name))
      val changed = cols1.flatMap { c1 =>
        map2.get(c1.name).flatMap { c2 =>
          if c1.dataType != c2.dataType || c1.isOptional != c2.isOptional then
            Some(
              ColumnChange(
                c1.name,
                c1.dataType,
                c2.dataType,
                c1.isOptional,
                c2.isOptional
              )
            )
          else None
        }
      }
      val unchanged = cols1.collect {
        case c
            if map2
              .get(c.name)
              .exists(c2 => c.dataType == c2.dataType && c.isOptional == c2.isOptional) =>
          c.name
      }

      SchemaDiff(added, removed, changed, unchanged)
    }

}
