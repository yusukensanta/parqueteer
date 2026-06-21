package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.CellValue
import io.github.yusukensanta.parqueteer.core.util.{CsvParser, LTSVParser, TypeInferrer}
import scala.util.{Try, Using}

private[services] object DataFileReader {

  def readJsonFile(path: String): Try[List[Map[String, CellValue]]] = Try {
    import better.files.*
    parseJsonContent(
      File(path).contentAsString(using java.nio.charset.StandardCharsets.UTF_8)
    )
  }

  def readNdjsonFile(
      path: String,
      maxRows: Option[Long]
  ): Try[List[Map[String, CellValue]]] =
    Using(scala.io.Source.fromFile(path, "UTF-8")) { source =>
      val iter = source.getLines()
      parseNdjsonLines(maxRows.fold(iter)(iterTakeLong(iter, _)))
    }

  def readCsvFile(path: String): Try[List[Map[String, CellValue]]] =
    Try {
      import better.files.*
      parseCsvContent(
        File(path).contentAsString(using
          java.nio.charset.StandardCharsets.UTF_8
        )
      )
    }

  def readLtsvFile(
      path: String,
      maxRows: Option[Long]
  ): Try[List[Map[String, CellValue]]] =
    Using(scala.io.Source.fromFile(path, "UTF-8")) { source =>
      val iter = source.getLines()
      LTSVParser
        .parseLines(maxRows.fold(iter)(iterTakeLong(iter, _)))
        .toList
    }

  def readFromStdin(
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

  def parseLtsvContent(
      content: String
  ): List[Map[String, CellValue]] =
    LTSVParser.parse(content)

  private[services] def coerceJsonValue(
      j: io.circe.Json
  ): CellValue =
    if j.isString then
      TypeInferrer
        .inferJsonString(j.asString.get)
    else if j.isNumber then {
      val n   = j.asNumber.get
      val raw = j.noSpaces
      if raw.contains('.') then
        n.toBigDecimal
          .map { bd =>
            val isWhole        = bd.underlying.stripTrailingZeros.scale <= 0
            val tooLargeForF64 = bd.abs > BigDecimal(9007199254740992L)
            if isWhole && tooLargeForF64 then
              n.toLong
                .map(CellValue.I64.apply)
                .getOrElse(CellValue.Dec(bd.setScale(0)))
            else if !isWhole && tooLargeForF64 then CellValue.Dec(bd)
            else if isWhole && (raw.contains('e') || raw.contains('E')) then {
              val eIdx     = raw.indexWhere(c => c == 'e' || c == 'E')
              val mantissa = raw.take(eIdx)
              val dotIdx   = mantissa.indexOf('.')
              val mantissaIsWhole =
                dotIdx < 0 || mantissa.drop(dotIdx + 1).forall(_ == '0')
              if mantissaIsWhole then
                n.toLong
                  .map(CellValue.I64.apply)
                  .getOrElse(CellValue.F64(n.toDouble))
              else CellValue.F64(n.toDouble)
            } else CellValue.F64(n.toDouble)
          }
          .getOrElse(CellValue.F64(n.toDouble))
      else
        n.toLong
          .map(CellValue.I64.apply)
          .orElse(n.toBigDecimal.map(CellValue.Dec.apply))
          .getOrElse(CellValue.F64(n.toDouble))
    } else if j.isBoolean then CellValue.Bool(j.asBoolean.get)
    else if j.isNull then CellValue.Null
    else CellValue.Str(j.toString)

  def parseJsonContent(
      content: String
  ): List[Map[String, CellValue]] = {
    import io.circe.parser.*
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
                s"Each element of the JSON array must be an object, got: ${truncate(elem.noSpaces)}"
              )
            }
          case None =>
            throw new IllegalArgumentException(
              "JSON input must be an array of objects"
            )
        }
    }
  }

  def parseNdjsonContent(
      content: String
  ): List[Map[String, CellValue]] =
    parseNdjsonLines(content.linesIterator)

  private def parseNdjsonLines(
      lines: Iterator[String]
  ): List[Map[String, CellValue]] = {
    import io.circe.parser.*
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
              s"Each NDJSON line must be a JSON object, got: ${truncate(json.noSpaces)}"
            )
        }
      }
      .toList
  }

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

  def parseCsvContent(
      content: String
  ): List[Map[String, CellValue]] =
    CsvParser.parse(content)

  private val ErrorPreviewMaxLen = 80

  private def truncate(s: String): String =
    if s.length <= ErrorPreviewMaxLen then s
    else s.take(ErrorPreviewMaxLen) + "…"

  private[services] def iterTakeLong[A](iter: Iterator[A], n: Long): Iterator[A] =
    new Iterator[A] {
      private var remaining = n
      def hasNext: Boolean  = remaining > 0 && iter.hasNext

      def next(): A = {
        if !hasNext then throw new NoSuchElementException("next on empty iterator")
        remaining -= 1
        iter.next()
      }
    }
}
