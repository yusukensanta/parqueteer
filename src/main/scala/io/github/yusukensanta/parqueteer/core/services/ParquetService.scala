package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models._
import io.github.yusukensanta.parqueteer.core.models.StorageLocationParser
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.filters.FilterParser
import scala.util.{Try, Success, Failure}
import io.circe.{Encoder, Json}

object ParquetServiceEncoders {
  given anyEncoder: Encoder[Any] =
    Encoder.instance(
      io.github.yusukensanta.parqueteer.core.util.JsonEncoder.encodeAny
    )

  given mapStringAnyEncoder: Encoder[Map[String, Any]] =
    Encoder.instance { map =>
      Json.obj(map.map { case (k, v) =>
        k -> anyEncoder.apply(v)
      }.toSeq*)
    }

  given listMapEncoder: Encoder[List[Map[String, Any]]] =
    Encoder.instance(list =>
      Json.fromValues(list.map(mapStringAnyEncoder.apply))
    )
}

private final class ParqueteerCarrierException(val error: ParqueteerError)
    extends RuntimeException(error.userMessage)

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
        .toEither
        .left
        .map(ParqueteerError.IOError.apply)
      schema <- repository
        .readSchema(file)
        .toEither
        .left
        .map(ParqueteerError.IOError.apply)
      metadata <- repository
        .readMetadata(file)
        .toEither
        .left
        .map(ParqueteerError.IOError.apply)
    } yield file.copy(
      content = Some(content),
      schema = Some(schema),
      metadata = Some(metadata)
    )

  def streamRead(
      path: String,
      readConfig: ReadConfig
  )(process: Map[String, Any] => Unit): Either[ParqueteerError, Long] =
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
        .toEither
        .left
        .map(ParqueteerError.IOError.apply)
    } yield count

  def getFileInfo(path: String): Either[ParqueteerError, ParquetFile] =
    for {
      location <- parseLocation(path)
      file = ParquetFile(location)
      schema <- repository
        .readSchema(file)
        .toEither
        .left
        .map(ParqueteerError.IOError.apply)
      metadata <- repository
        .readMetadata(file)
        .toEither
        .left
        .map(ParqueteerError.IOError.apply)
    } yield file.copy(schema = Some(schema), metadata = Some(metadata))

  def mergeFiles(
      inputPaths: List[String],
      outputPath: String,
      writeConfig: WriteConfig,
      schemaMode: SchemaMode,
      onProgress: (Int, Int, String) => Unit = (_, _, _) => ()
  ): Either[ParqueteerError, Long] =
    mergeFilesInternal(
      inputPaths,
      outputPath,
      writeConfig,
      schemaMode,
      onProgress
    ).toEither.left.map(ParqueteerError.IOError.apply)

  private def mergeFilesInternal(
      inputPaths: List[String],
      outputPath: String,
      writeConfig: WriteConfig,
      schemaMode: SchemaMode,
      onProgress: (Int, Int, String) => Unit
  ): Try[Long] = {

    if (inputPaths.size < 2)
      return Failure(
        new IllegalArgumentException("merge requires at least two input files")
      )

    for {
      inputLocations <- Try {
        inputPaths.map { p =>
          StorageLocationParser
            .parse(p)
            .fold(
              err =>
                throw new IllegalArgumentException(s"Invalid path '$p': $err"),
              identity
            )
        }
      }
      schemas <- Try {
        inputLocations.map { loc =>
          repository
            .readSchemaFields(ParquetFile(loc))
            .fold(
              err =>
                throw new RuntimeException(
                  s"Cannot read schema: ${err.getMessage}"
                ),
              identity
            )
        }
      }
      mergedFields <- Try {
        schemaMode match {
          case SchemaMode.Strict =>
            val first = schemas.head
            schemas.zipWithIndex.foreach { case (s, i) =>
              if (s != first)
                throw new IllegalArgumentException(
                  s"Schema mismatch at file '${inputPaths(i)}'. Use --schema-mode union to allow schema differences."
                )
            }
            first
          case SchemaMode.Union =>
            val seen =
              scala.collection.mutable.LinkedHashMap.empty[String, String]
            schemas.foreach { fields =>
              val conflicts = fields.collect {
                case (name, t, _) if seen.get(name).exists(_ != t) =>
                  s"'$name' (${seen(name)} vs $t)"
              }
              if (conflicts.nonEmpty)
                throw new IllegalArgumentException(
                  s"Type conflicts in union merge: ${conflicts.mkString(", ")}. " +
                    "Cannot union-merge columns with incompatible types."
                )
              fields.foreach { case (name, t, _) =>
                seen.getOrElseUpdate(name, t)
              }
            }
            seen.map { case (name, t) => (name, t, true) }.toList
        }
      }
      allColumnNames = mergedFields.map(_._1).toSet
      outputLocation <- parseLocation(outputPath)
        .fold(
          err => Failure(new IllegalArgumentException(err.userMessage)),
          Success.apply
        )
      explicitSchema = ParquetSchema(
        columns = mergedFields.map { case (name, dataType, optional) =>
          ColumnInfo(name, dataType, optional, 1, 0, "SNAPPY")
        },
        rowGroupCount = 1L,
        totalRowCount = 0L
      )
      count <- repository.writeContentStream(
        outputLocation,
        explicitSchema,
        writeConfig
      ) { write =>
        inputLocations.zipWithIndex.foreach { case (loc, i) =>
          onProgress(i + 1, inputLocations.size, inputPaths(i))
          repository
            .streamContent(ParquetFile(loc), ReadConfig()) { row =>
              val missing = allColumnNames -- row.keySet
              val finalRow =
                if (missing.isEmpty) row
                else row ++ missing.map(_ -> null)
              write(finalRow)
            }
            .fold(err => throw new RuntimeException(err.getMessage), _ => ())
        }
      }
    } yield count
  }

  def getStats(path: String): Either[ParqueteerError, FileStats] =
    for {
      location <- parseLocation(path)
      file = ParquetFile(location)
      stats <- repository
        .readStats(file)
        .toEither
        .left
        .map(ParqueteerError.IOError.apply)
    } yield stats

  private def readFileAsTry(path: String): Try[ParquetFile] =
    readFile(path).fold(
      err => Failure(new ParqueteerCarrierException(err)),
      Success.apply
    )

  def writeFile(
      path: String,
      data: List[Map[String, Any]],
      writeConfig: WriteConfig = WriteConfig()
  ): Either[ParqueteerError, Unit] =
    for {
      location <- parseLocation(path)
      _ <- repository
        .writeContent(location, data, None, writeConfig)
        .toEither
        .left
        .map(ParqueteerError.IOError.apply)
    } yield ()

  def readDataFile(
      path: String,
      inputFormat: String,
      stdin: java.io.InputStream = System.in
  ): Either[ParqueteerError, List[Map[String, Any]]] =
    if (path == "-")
      readFromStdin(inputFormat, stdin).toEither.left
        .map(ParqueteerError.IOError.apply)
    else
      inputFormat.toLowerCase match {
        case "json" =>
          readJsonFile(path).toEither.left.map(ParqueteerError.IOError.apply)
        case "csv" =>
          readCsvFile(path).toEither.left.map(ParqueteerError.IOError.apply)
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
  ): Try[List[Map[String, Any]]] = Try {
    val content = scala.io.Source.fromInputStream(stdin).mkString
    inputFormat.toLowerCase match {
      case "json" => parseJsonContent(content)
      case "csv"  => parseCsvContent(content)
      case fmt =>
        throw new IllegalArgumentException(s"Unsupported input format: $fmt")
    }
  }

  def validateFile(path: String): Either[ParqueteerError, ValidationResult] =
    for {
      location <- parseLocation(path)
      file = ParquetFile(location)
      issues <- repository
        .validateFile(file)
        .toEither
        .left
        .map(ParqueteerError.IOError.apply)
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

  def convertFile(
      inputPath: String,
      outputPath: String,
      conversionConfig: ConversionConfig = ConversionConfig()
  ): Either[ParqueteerError, Unit] =
    convertFileInternal(inputPath, outputPath, conversionConfig).toEither.left
      .map {
        case e: ParqueteerCarrierException => e.error
        case e                             => ParqueteerError.IOError(e)
      }

  private def convertFileInternal(
      inputPath: String,
      outputPath: String,
      conversionConfig: ConversionConfig
  ): Try[Unit] = {
    val inputExt = getFileExtension(inputPath)
    val outputExt = getFileExtension(outputPath)

    (inputExt, outputExt) match {
      // Parquet → Parquet (cloud/local copy with optional compression change)
      case ("parquet", "parquet") =>
        for {
          inputFile <- readFileAsTry(inputPath)
          data = inputFile.content.map(_.rows).getOrElse(List.empty)
          _ <- writeFile(outputPath, data, conversionConfig.writeConfig)
            .fold(
              e => Failure(new RuntimeException(e.userMessage)),
              Success.apply
            )
        } yield ()

      // Parquet → JSON
      case ("parquet", "json") =>
        for {
          inputFile <- readFileAsTry(inputPath)
          content = inputFile.content.getOrElse(
            FileContent(List.empty, 0, false)
          )
          jsonOutput = formatContentAsJSON(content)
          _ <- writeTextFile(outputPath, jsonOutput)
        } yield ()

      // Parquet → CSV
      case ("parquet", "csv") =>
        for {
          inputFile <- readFileAsTry(inputPath)
          content = inputFile.content.getOrElse(
            FileContent(List.empty, 0, false)
          )
          csvOutput = formatContentAsCSV(content)
          _ <- writeTextFile(outputPath, csvOutput)
        } yield ()

      // JSON/CSV → Parquet
      case ("json" | "csv", "parquet") =>
        readDataFile(inputPath, inputExt)
          .fold(
            err => Failure(new ParqueteerCarrierException(err)),
            data =>
              writeFile(outputPath, data, conversionConfig.writeConfig)
                .fold(
                  e => Failure(new ParqueteerCarrierException(e)),
                  Success.apply
                )
          )

      case _ =>
        Failure(
          new IllegalArgumentException(
            s"Unsupported conversion: $inputExt → $outputExt. Supported: parquet→parquet, parquet→json, parquet→csv"
          )
        )
    }
  }

  private def getFileExtension(path: String): String = {
    val fileName = path.split("/").last.split("\\?").head // Remove query params
    if (fileName.contains(".")) {
      fileName.split("\\.").last.toLowerCase
    } else {
      "unknown"
    }
  }

  private def formatContentAsJSON(content: FileContent): String = {
    import io.github.yusukensanta.parqueteer.core.formatters.JSONFormatter
    new JSONFormatter().formatContent(content, None)
  }

  private def formatContentAsCSV(content: FileContent): String = {
    import io.github.yusukensanta.parqueteer.core.formatters.CSVFormatter
    new CSVFormatter().formatContent(content, None)
  }

  private def writeTextFile(path: String, content: String): Try[Unit] = {
    Try {
      import better.files._
      val file = File(path)
      file.createIfNotExists()
      file.write(content)
    }
  }

  private def readJsonFile(path: String): Try[List[Map[String, Any]]] = Try {
    import better.files._
    parseJsonContent(File(path).contentAsString)
  }

  private def readCsvFile(path: String): Try[List[Map[String, Any]]] = Try {
    import better.files._
    parseCsvContent(File(path).contentAsString)
  }

  private[services] def parseJsonContent(
      content: String
  ): List[Map[String, Any]] = {
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
                .mapValues {
                  case j if j.isString =>
                    io.github.yusukensanta.parqueteer.core.util.TypeInferrer
                      .inferJsonString(j.asString.get)
                  case j if j.isNumber =>
                    val n = j.asNumber.get
                    n.toLong.fold[Any](n.toDouble)(identity)
                  case j if j.isBoolean => j.asBoolean.get
                  case j if j.isNull    => null
                  case j                => j.toString
                }
                .toMap
            }
          case None =>
            throw new IllegalArgumentException(
              "JSON input must be an array of objects"
            )
        }
    }
  }

  private[services] def parseCsvContent(
      content: String
  ): List[Map[String, Any]] =
    io.github.yusukensanta.parqueteer.core.util.CsvParser.parse(content)

}
