package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models._
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.toParqueteerError
import io.github.yusukensanta.parqueteer.core.models.StorageLocationParser
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.filters.FilterParser
import scala.util.{Try, Using}

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
    if (path == "-")
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
      // readFileInfo: ONE stat + ONE footer download covers both schema and metadata
      schemaAndMeta <- repository
        .readFileInfo(file)
        .toParqueteerError
      (schema, metadata) = schemaAndMeta
      content <- repository
        .readContent(file, readConfig)
        .toParqueteerError
    } yield file.copy(
      content = Some(content),
      schema = Some(schema),
      metadata = Some(metadata)
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
      // readFileInfo: ONE stat + ONE footer download instead of separate readSchema + readMetadata
      schemaAndMeta <- repository
        .readFileInfo(file)
        .toParqueteerError
      (schema, metadata) = schemaAndMeta
    } yield file.copy(schema = Some(schema), metadata = Some(metadata))

  def mergeFiles(
      inputPaths: List[String],
      outputPath: String,
      writeConfig: WriteConfig,
      schemaMode: SchemaMode,
      onProgress: (Int, Int, String) => Unit = (_, _, _) => ()
  ): Either[ParqueteerError, Long] =
    if (inputPaths.size < 2)
      Left(
        ParqueteerError.InvalidFormat(
          "merge",
          "merge requires at least two input files"
        )
      )
    else
      for {
        inputLocations <- parseLocations(inputPaths)
        schemas <- readAllSchemas(inputLocations)
        mergedFields <- mergeSchemas(schemas, inputPaths, schemaMode)
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

  /** Lift a list of paths into a single Either of parsed locations,
    * short-circuiting on the first malformed path.
    */
  private def parseLocations(
      paths: List[String]
  ): Either[ParqueteerError, Vector[StorageLocation]] =
    paths.foldLeft[Either[ParqueteerError, Vector[StorageLocation]]](
      Right(Vector.empty)
    )((acc, p) => acc.flatMap(locs => parseLocation(p).map(locs :+ _)))

  /** Read the field-summary schema for each input file, preserving order. Stops
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

  /** Combine per-file schemas under the chosen strategy:
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
      if (schemas.isEmpty) return Right(Nil)
      val first = schemas.head
      val firstSet = first.map(f => (f.name, f.dataType, f.isOptional)).toSet
      schemas.zipWithIndex
        .collectFirst {
          case (s, i)
              if s
                .map(f => (f.name, f.dataType, f.isOptional))
                .toSet != firstSet =>
            val thisSet = s.map(f => (f.name, f.dataType, f.isOptional)).toSet
            val missing = firstSet -- thisSet
            val extra = thisSet -- firstSet
            val diffMsg = List(
              if (missing.nonEmpty)
                s"missing: ${missing.map(_._1).toList.sorted.mkString(", ")}"
              else "",
              if (extra.nonEmpty)
                s"extra: ${extra.map(_._1).toList.sorted.mkString(", ")}"
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

    case SchemaMode.Union =>
      // (dataType, requiredInAllInputsSoFar)
      val seen =
        scala.collection.mutable.LinkedHashMap.empty[String, (String, Boolean)]
      schemas.zipWithIndex
        .foldLeft[Either[ParqueteerError, Unit]](Right(())) {
          case (acc, (fields, fileIdx)) =>
            acc.flatMap { _ =>
              val fieldMap = fields.view.map(f => f.name -> f).toMap
              val conflicts = fields.collect {
                case f if seen.get(f.name).exists(_._1 != f.dataType) =>
                  s"'${f.name}' (${seen(f.name)._1} vs ${f.dataType})"
              }
              if (conflicts.nonEmpty)
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
            }
        }
        .map(_ =>
          seen.map { case (name, (t, allRequired)) =>
            FieldSummary(name, t, isOptional = !allRequired)
          }.toList
        )
  }

  /** Thrown inside the writeContentStream feed callback to abort streaming on
    * read error. Propagates through writeContentStream's Try wrapper so the
    * partial output can be cleaned up before returning the real error.
    */
  private class MergeStreamException(val error: ParqueteerError)
      extends RuntimeException(error.userMessage, null, true, false)

  /** Stream rows from each input file into a single output writer. On the first
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
    if (nestedFields.nonEmpty)
      return Left(
        ParqueteerError.InvalidFormat(
          "merge",
          s"Cannot merge files containing nested columns: ${nestedFields.map(_.name).mkString(", ")}. " +
            "Flatten STRUCT/MAP/LIST columns before merging."
        )
      )
    val explicitSchema = ParquetSchema(
      columns = mergedFields.map { f =>
        ColumnInfo(
          f.name,
          f.dataType,
          f.isOptional,
          1,
          0,
          writeConfig.compressionType.codecName
        )
      },
      rowGroupCount = 1L,
      totalRowCount = 0L
    )
    val writeResult = repository
      .writeContentStream(outputLocation, explicitSchema, writeConfig) {
        write =>
          inputLocations.zipWithIndex.foreach { case (loc, i) =>
            onProgress(i + 1, inputLocations.size, inputPaths(i))
            repository
              .streamContent(ParquetFile(loc), ReadConfig()) { row =>
                val finalRow = scala.collection.immutable.ListMap.from(
                  mergedFields.map(f =>
                    f.name -> row.getOrElse(f.name, CellValue.Null)
                  )
                )
                write(finalRow)
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

    def deletePartial(): Unit =
      repository.deleteFile(outputLocation) match {
        case scala.util.Failure(delErr) =>
          logger.warn(
            s"Failed to delete partial output at ${outputLocation.path}: ${delErr.getMessage}. Partial file may remain."
          )
        case _ =>
      }

    writeResult match {
      case scala.util.Failure(ex: MergeStreamException) =>
        deletePartial()
        Left(ex.error)
      case scala.util.Failure(ex) =>
        // Generic write failure (e.g., disk full, schema error) — also clean up
        deletePartial()
        scala.util.Failure(ex).toParqueteerError
      case other => other.toParqueteerError
    }
  }

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
      stdin: java.io.InputStream = System.in
  ): Either[ParqueteerError, List[Map[String, CellValue]]] =
    if (path == "-")
      readFromStdin(inputFormat, stdin).toParqueteerError
    else
      inputFormat.toLowerCase match {
        case "json" =>
          readJsonFile(path).toParqueteerError
        case "ndjson" =>
          readNdjsonFile(path).toParqueteerError
        case "csv" =>
          readCsvFile(path).toParqueteerError
        case "ltsv" =>
          readLtsvFile(path).toParqueteerError
        case fmt =>
          Left(
            ParqueteerError.IOError(
              new IllegalArgumentException(s"Unsupported input format: $fmt")
            )
          )
      }

  private[services] def readFromStdin(
      inputFormat: String,
      stdin: java.io.InputStream = System.in
  ): Try[List[Map[String, CellValue]]] = Try {
    val content =
      Using.resource(
        scala.io.Source.fromInputStream(stdin)(using scala.io.Codec.UTF8)
      )(_.mkString)
    inputFormat.toLowerCase match {
      case "json"   => parseJsonContent(content)
      case "ndjson" => parseNdjsonContent(content)
      case "csv"    => parseCsvContent(content)
      case "ltsv"   => parseLtsvContent(content)
      case fmt =>
        throw new IllegalArgumentException(s"Unsupported input format: $fmt")
    }
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
  ): Either[ParqueteerError, SchemaDiff] = {
    for {
      f1 <- getFileInfo(path1)
      f2 <- getFileInfo(path2)
    } yield {
      val cols1 = f1.schema.map(_.columns).getOrElse(List.empty)
      val cols2 = f2.schema.map(_.columns).getOrElse(List.empty)
      val map1 = cols1.map(c => c.name -> c).toMap
      val map2 = cols2.map(c => c.name -> c).toMap

      val added = cols2.filterNot(c => map1.contains(c.name))
      val removed = cols1.filterNot(c => map2.contains(c.name))
      val changed = cols1.flatMap { c1 =>
        map2.get(c1.name).flatMap { c2 =>
          if (c1.dataType != c2.dataType || c1.isOptional != c2.isOptional)
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
              .exists(c2 =>
                c.dataType == c2.dataType && c.isOptional == c2.isOptional
              ) =>
          c.name
      }

      SchemaDiff(added, removed, changed, unchanged)
    }
  }

  private def readJsonFile(
      path: String
  ): Try[List[Map[String, CellValue]]] = Try {
    import better.files._
    parseJsonContent(
      File(path).contentAsString(using java.nio.charset.StandardCharsets.UTF_8)
    )
  }

  private def readNdjsonFile(
      path: String
  ): Try[List[Map[String, CellValue]]] = Try {
    import better.files._
    parseNdjsonLines(
      File(path).lineIterator(using java.nio.charset.StandardCharsets.UTF_8)
    )
  }

  private def readCsvFile(path: String): Try[List[Map[String, CellValue]]] =
    Try {
      import better.files._
      parseCsvContent(
        File(path).contentAsString(using
          java.nio.charset.StandardCharsets.UTF_8
        )
      )
    }

  private def readLtsvFile(path: String): Try[List[Map[String, CellValue]]] =
    Try {
      import better.files._
      io.github.yusukensanta.parqueteer.core.util.LTSVParser
        .parseLines(
          File(path).lineIterator(using java.nio.charset.StandardCharsets.UTF_8)
        )
        .toList
    }

  private[services] def parseLtsvContent(
      content: String
  ): List[Map[String, CellValue]] =
    io.github.yusukensanta.parqueteer.core.util.LTSVParser.parse(content)

  private def coerceJsonValue(
      j: io.circe.Json
  ): CellValue =
    if (j.isString)
      io.github.yusukensanta.parqueteer.core.util.TypeInferrer
        .inferJsonString(j.asString.get)
    else if (j.isNumber) {
      val n = j.asNumber.get
      val raw = j.noSpaces
      // A decimal point in the raw JSON representation signals explicit floating-point (1.0, 1.5).
      // No decimal point means integer or scientific-notation integer (1e10) — try Long first.
      if (raw.contains('.'))
        CellValue.F64(n.toDouble)
      else
        n.toLong
          .map(CellValue.I64.apply)
          .orElse(n.toBigDecimal.map(CellValue.Dec.apply))
          .getOrElse(CellValue.F64(n.toDouble))
    } else if (j.isBoolean) CellValue.Bool(j.asBoolean.get)
    else if (j.isNull) CellValue.Null
    else CellValue.Str(j.toString)

  private[services] def parseJsonContent(
      content: String
  ): List[Map[String, CellValue]] = {
    import io.circe.parser._
    parse(content) match {
      case Left(error) =>
        throw new IllegalArgumentException(
          s"Failed to parse JSON: ${error.getMessage}"
        )
      case Right(json) =>
        json.asArray match {
          case Some(array) =>
            array.toList.map { elem =>
              jsonObjectToRow(
                elem,
                s"Each element of the JSON array must be an object, got: ${elem.noSpaces}"
              )
            }
          case None =>
            throw new IllegalArgumentException(
              "JSON input must be an array of objects"
            )
        }
    }
  }

  private[services] def parseNdjsonContent(
      content: String
  ): List[Map[String, CellValue]] =
    parseNdjsonLines(content.linesIterator)

  // Shared NDJSON line decoder: parse each non-blank line as a JSON object
  // and coerce values to CellValues. Used by both file and string entry points.
  private def parseNdjsonLines(
      lines: Iterator[String]
  ): List[Map[String, CellValue]] = {
    import io.circe.parser._
    lines
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { line =>
        parse(line) match {
          case Left(error) =>
            throw new IllegalArgumentException(
              s"Failed to parse NDJSON line: ${error.getMessage}"
            )
          case Right(json) =>
            jsonObjectToRow(
              json,
              s"Each NDJSON line must be a JSON object, got: ${json.noSpaces}"
            )
        }
      }
      .toList
  }

  // Turn a JSON object into a CellValue row; raise a precise error if the
  // value is not an object. Used by both JSON-array and NDJSON code paths.
  private def jsonObjectToRow(
      json: io.circe.Json,
      notAnObjectMessage: => String
  ): Map[String, CellValue] =
    scala.collection.immutable.ListMap.from(
      json.asObject
        .getOrElse(throw new IllegalArgumentException(notAnObjectMessage))
        .toIterable
        .map { case (k, v) => k -> coerceJsonValue(v) }
    )

  private[services] def parseCsvContent(
      content: String
  ): List[Map[String, CellValue]] =
    io.github.yusukensanta.parqueteer.core.util.CsvParser.parse(content)

}
