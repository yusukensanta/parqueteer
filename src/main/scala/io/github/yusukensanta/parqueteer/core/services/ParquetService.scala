package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models._
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.toParqueteerError
import io.github.yusukensanta.parqueteer.core.models.StorageLocationParser
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.filters.FilterParser
import scala.util.{Try, Using}
import io.circe.{Encoder, Json}

object ParquetServiceEncoders {
  import io.github.yusukensanta.parqueteer.core.models.CellValue
  import io.github.yusukensanta.parqueteer.core.util.JsonEncoder

  given cellValueEncoder: Encoder[CellValue] =
    Encoder.instance(JsonEncoder.encode)

  given mapStringCellValueEncoder: Encoder[Map[String, CellValue]] =
    Encoder.instance { map =>
      Json.obj(map.map { case (k, v) =>
        k -> cellValueEncoder.apply(v)
      }.toSeq*)
    }

  given listMapEncoder: Encoder[List[Map[String, CellValue]]] =
    Encoder.instance(list =>
      Json.fromValues(list.map(mapStringCellValueEncoder.apply))
    )
}

class ParquetService(
    repository: ParquetRepository
) {
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
      content <- repository
        .readContent(file, readConfig)
        .toParqueteerError
      schema <- repository
        .readSchema(file)
        .toParqueteerError
      metadata <- repository
        .readMetadata(file)
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
      schema <- repository
        .readSchema(file)
        .toParqueteerError
      metadata <- repository
        .readMetadata(file)
        .toParqueteerError
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
        inputLocations <- inputPaths
          .foldLeft[Either[ParqueteerError, List[StorageLocation]]](
            Right(Nil)
          ) { (acc, p) =>
            acc.flatMap(locs => parseLocation(p).map(locs :+ _))
          }
        schemas <- inputLocations.foldLeft[Either[ParqueteerError, List[
          List[FieldSummary]
        ]]](Right(Nil)) { (acc, loc) =>
          acc.flatMap { list =>
            repository
              .readSchemaFields(ParquetFile(loc))
              .toParqueteerError
              .map(list :+ _)
          }
        }
        mergedFields <- schemaMode match {
          case SchemaMode.Strict =>
            val first = schemas.head
            schemas.zipWithIndex
              .collectFirst {
                case (s, i) if s != first =>
                  Left(
                    ParqueteerError.InvalidFormat(
                      inputPaths(i),
                      s"Schema mismatch at file '${inputPaths(i)}'. Use --schema-mode union to allow schema differences."
                    )
                  )
              }
              .getOrElse(Right(first))
          case SchemaMode.Union =>
            val seen =
              scala.collection.mutable.LinkedHashMap.empty[String, String]
            schemas
              .foldLeft[Either[ParqueteerError, Unit]](Right(())) {
                (acc, fields) =>
                  acc.flatMap { _ =>
                    val conflicts = fields.collect {
                      case f if seen.get(f.name).exists(_ != f.dataType) =>
                        s"'${f.name}' (${seen(f.name)} vs ${f.dataType})"
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
                      fields.foreach(f =>
                        seen.getOrElseUpdate(f.name, f.dataType)
                      )
                      Right(())
                    }
                  }
              }
              .map(_ =>
                seen.map { case (name, t) =>
                  FieldSummary(name, t, isOptional = true)
                }.toList
              )
        }
        outputLocation <- parseLocation(outputPath)
        count <- {
          val allColumnNames = mergedFields.map(_.name).toSet
          val explicitSchema = ParquetSchema(
            columns = mergedFields.map { f =>
              ColumnInfo(f.name, f.dataType, f.isOptional, 1, 0, "SNAPPY")
            },
            rowGroupCount = 1L,
            totalRowCount = 0L
          )
          var streamError: Option[Throwable] = None
          repository
            .writeContentStream(outputLocation, explicitSchema, writeConfig) {
              write =>
                inputLocations.zipWithIndex.foreach { case (loc, i) =>
                  if (streamError.isEmpty) {
                    onProgress(i + 1, inputLocations.size, inputPaths(i))
                    repository
                      .streamContent(ParquetFile(loc), ReadConfig()) { row =>
                        val missing = allColumnNames -- row.keySet
                        val finalRow =
                          if (missing.isEmpty) row
                          else row ++ missing.map(_ -> CellValue.Null)
                        write(finalRow)
                      }
                      .fold(err => streamError = Some(err), _ => ())
                  }
                }
            }
            .toParqueteerError
            .flatMap(count =>
              streamError
                .map(e => Left(ParqueteerError.IOError(e)))
                .getOrElse(Right(count))
            )
        }
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
      Using.resource(scala.io.Source.fromInputStream(stdin))(_.mkString)
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
    parseJsonContent(File(path).contentAsString)
  }

  private def readNdjsonFile(
      path: String
  ): Try[List[Map[String, CellValue]]] = Try {
    import better.files._
    parseNdjsonContent(File(path).contentAsString)
  }

  private def readCsvFile(path: String): Try[List[Map[String, CellValue]]] =
    Try {
      import better.files._
      parseCsvContent(File(path).contentAsString)
    }

  private def readLtsvFile(path: String): Try[List[Map[String, CellValue]]] =
    Try {
      import better.files._
      parseLtsvContent(File(path).contentAsString)
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
      if (raw.contains('.') || raw.contains('e') || raw.contains('E'))
        CellValue.F64(n.toDouble)
      else
        n.toLong
          .map(CellValue.I64.apply)
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
              elem.asObject
                .getOrElse(
                  throw new IllegalArgumentException(
                    s"Each element of the JSON array must be an object, got: ${elem.noSpaces}"
                  )
                )
                .toMap
                .view
                .mapValues(coerceJsonValue)
                .toMap
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
  ): List[Map[String, CellValue]] = {
    import io.circe.parser._
    content.linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { line =>
        parse(line) match {
          case Left(error) =>
            throw new IllegalArgumentException(
              s"Failed to parse NDJSON line: ${error.getMessage}"
            )
          case Right(json) =>
            json.asObject
              .getOrElse(
                throw new IllegalArgumentException(
                  s"Each NDJSON line must be a JSON object, got: ${json.noSpaces}"
                )
              )
              .toMap
              .view
              .mapValues(coerceJsonValue)
              .toMap
        }
      }
      .toList
  }

  private[services] def parseCsvContent(
      content: String
  ): List[Map[String, CellValue]] =
    io.github.yusukensanta.parqueteer.core.util.CsvParser.parse(content)

}
