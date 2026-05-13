package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models._
import io.github.yusukensanta.parqueteer.core.models.StorageLocationParser
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import scala.util.{Try, Success, Failure}
import io.circe.{Encoder, Json}

object ParquetServiceEncoders {
  implicit val anyEncoder: Encoder[Any] = Encoder.instance {
    case null      => Json.Null
    case s: String => Json.fromString(s)
    case i: Int    => Json.fromInt(i)
    case l: Long   => Json.fromLong(l)
    case d: Double =>
      if (d.isNaN || d.isInfinity) Json.Null
      else Json.fromDoubleOrNull(d)
    case f: Float =>
      if (f.isNaN || f.isInfinity) Json.Null
      else Json.fromFloatOrNull(f)
    case b: Boolean     => Json.fromBoolean(b)
    case bd: BigDecimal => Json.fromBigDecimal(bd)
    case bi: BigInt     => Json.fromBigInt(bi)
    case bytes: Array[Byte] =>
      Json.fromString(java.util.Base64.getEncoder.encodeToString(bytes))
    case list: List[_] => Json.arr(list.map(anyEncoder.apply)*)
    case arr: Array[_] => Json.arr(arr.map(anyEncoder.apply)*)
    case map: Map[_, _] =>
      Json.obj(map.map { case (k, v) =>
        k.toString -> anyEncoder.apply(v)
      }.toSeq*)
    case Some(value) => anyEncoder.apply(value)
    case None        => Json.Null
    case other       => Json.fromString(other.toString)
  }

  implicit val mapStringAnyEncoder: Encoder[Map[String, Any]] =
    Encoder.instance { map =>
      val typedMap = map.asInstanceOf[Map[Any, Any]]
      Json.obj(typedMap.map { case (k, v) =>
        k.toString -> anyEncoder.apply(v)
      }.toSeq*)
    }

  implicit val listMapEncoder: Encoder[List[Map[String, Any]]] =
    Encoder.instance(list =>
      Json.fromValues(list.map(mapStringAnyEncoder.apply))
    )
}

class ParquetService(
    repository: ParquetRepository
) {
  def readFile(
      path: String,
      readConfig: ReadConfig = ReadConfig()
  ): Try[ParquetFile] = {
    for {
      location <- StorageLocationParser
        .parse(path)
        .fold(
          error => Failure(new IllegalArgumentException(error)),
          Success.apply
        )
      file = ParquetFile(location)
      content <- repository.readContent(file, readConfig)
      schema <- repository.readSchema(file)
      metadata <- repository.readMetadata(file)
    } yield {
      file.copy(
        content = Some(content),
        schema = Some(schema),
        metadata = Some(metadata)
      )
    }
  }

  def getFileInfo(path: String): Try[ParquetFile] = {
    for {
      location <- StorageLocationParser
        .parse(path)
        .fold(
          error => Failure(new IllegalArgumentException(error)),
          Success.apply
        )
      file = ParquetFile(location)
      schema <- repository.readSchema(file)
      metadata <- repository.readMetadata(file)
    } yield {
      file.copy(
        schema = Some(schema),
        metadata = Some(metadata)
      )
    }
  }

  def writeFile(
      path: String,
      data: List[Map[String, Any]],
      writeConfig: WriteConfig = WriteConfig()
  ): Try[Unit] = {
    for {
      location <- StorageLocationParser
        .parse(path)
        .fold(
          error => Failure(new IllegalArgumentException(error)),
          Success.apply
        )
      _ <- repository.writeContent(location, data, None, writeConfig)
    } yield ()
  }

  def validateFile(path: String): Try[ValidationResult] = {
    for {
      location <- StorageLocationParser
        .parse(path)
        .fold(
          error => Failure(new IllegalArgumentException(error)),
          Success.apply
        )
      file = ParquetFile(location)
      issues <- repository.validateFile(file)
    } yield {
      ValidationResult(
        isValid = issues.isEmpty,
        issues = issues
      )
    }
  }

  def convertFile(
      inputPath: String,
      outputPath: String,
      conversionConfig: ConversionConfig = ConversionConfig()
  ): Try[Unit] = {
    val inputExt = getFileExtension(inputPath)
    val outputExt = getFileExtension(outputPath)

    (inputExt, outputExt) match {
      // Parquet → Parquet (cloud/local copy with optional compression change)
      case ("parquet", "parquet") =>
        for {
          inputFile <- readFile(inputPath)
          data = inputFile.content.map(_.rows).getOrElse(List.empty)
          _ <- writeFile(outputPath, data, conversionConfig.writeConfig)
        } yield ()

      // Parquet → JSON
      case ("parquet", "json") =>
        for {
          inputFile <- readFile(inputPath)
          content = inputFile.content.getOrElse(
            FileContent(List.empty, 0, false)
          )
          jsonOutput = formatContentAsJSON(content)
          _ <- writeTextFile(outputPath, jsonOutput)
        } yield ()

      // Parquet → CSV
      case ("parquet", "csv") =>
        for {
          inputFile <- readFile(inputPath)
          content = inputFile.content.getOrElse(
            FileContent(List.empty, 0, false)
          )
          csvOutput = formatContentAsCSV(content)
          _ <- writeTextFile(outputPath, csvOutput)
        } yield ()

      // JSON/CSV → Parquet
      case ("json" | "csv", "parquet") =>
        val readResult =
          if (inputExt == "json") readJsonFile(inputPath)
          else readCsvFile(inputPath)
        readResult.flatMap(data =>
          writeFile(outputPath, data, conversionConfig.writeConfig)
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
    import io.circe.parser._
    val content = File(path).contentAsString
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
                  case j if j.isString  => j.asString.get
                  case j if j.isNumber  => j.asNumber.get.toDouble
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

  private def readCsvFile(path: String): Try[List[Map[String, Any]]] = Try {
    import better.files._
    val content =
      File(path).contentAsString.replace("\r\n", "\n").replace("\r", "\n")
    val lines = content.split("\n").filter(_.nonEmpty).toList
    if (lines.isEmpty) List.empty[Map[String, Any]]
    else {
      val headers = parseCsvLine(lines.head)
      lines.tail.zipWithIndex.map { case (line, idx) =>
        val values = parseCsvLine(line)
        if (values.length != headers.length)
          throw new IllegalArgumentException(
            s"Row ${idx + 2} has ${values.length} fields, expected ${headers.length}"
          )
        headers.zip(values).toMap.asInstanceOf[Map[String, Any]]
      }
    }
  }

  private def parseCsvLine(line: String): Array[String] = {
    val fields = scala.collection.mutable.ArrayBuffer.empty[String]
    val current = new StringBuilder
    var inQuote = false
    var i = 0
    while (i < line.length) {
      line(i) match {
        case '"' if !inQuote => inQuote = true
        case '"' if inQuote && i + 1 < line.length && line(i + 1) == '"' =>
          current.append('"'); i += 1
        case '"' if inQuote  => inQuote = false
        case ',' if !inQuote => fields += current.toString.trim; current.clear()
        case c               => current.append(c)
      }
      i += 1
    }
    fields += current.toString.trim
    fields.toArray
  }

  def formatContent(file: ParquetFile, format: OutputFormat): String = {
    import io.github.yusukensanta.parqueteer.core.formatters.OutputFormatter

    file.content match {
      case Some(content) =>
        val formatter = OutputFormatter(format)
        formatter.formatContent(content, file.schema)
      case None => "No content available"
    }
  }

  def formatSchema(file: ParquetFile): String = {
    import io.github.yusukensanta.parqueteer.core.formatters.OutputFormatter

    file.schema match {
      case Some(schema) =>
        // Use TableFormatter for schema display
        val formatter = OutputFormatter(OutputFormat.Table)
        formatter.formatSchema(schema)
      case None => "No schema information available"
    }
  }

  def formatMetadata(file: ParquetFile): String = {
    import io.github.yusukensanta.parqueteer.core.formatters.OutputFormatter

    file.metadata match {
      case Some(metadata) =>
        // Use TableFormatter for metadata display
        val formatter = OutputFormatter(OutputFormat.Table)
        formatter.formatMetadata(metadata)
      case None => "No metadata information available"
    }
  }
}

case class ValidationResult(
    isValid: Boolean,
    issues: List[String]
)

case class ConversionConfig(
    writeConfig: WriteConfig = WriteConfig(),
    transformations: List[DataTransformation] = List.empty
)

sealed trait DataTransformation
case class ColumnRename(from: String, to: String) extends DataTransformation
case class ColumnFilter(columns: List[String]) extends DataTransformation
case class RowFilter(expression: String) extends DataTransformation
