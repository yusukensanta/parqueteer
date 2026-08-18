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
    withNdjsonRows(path, maxRows)(_.toList)

  def readCsvFile(
      path: String,
      maxRows: Option[Long] = None
  ): Try[List[Map[String, CellValue]]] =
    withCsvRows(path, maxRows)(_.toList)

  def readLtsvFile(
      path: String,
      maxRows: Option[Long]
  ): Try[List[Map[String, CellValue]]] =
    withLtsvRows(path, maxRows)(_.toList)

  // ── Streaming (two-pass write) helpers ──────────────────────────────────
  //
  // Each opens its source fresh and hands the caller a row Iterator scoped to
  // the callback — used twice per two-pass write (once to infer a schema,
  // once to stream rows into the writer) so both passes see identical rows
  // under identical maxRows limiting.

  def withNdjsonRows[A](path: String, maxRows: Option[Long])(
      f: Iterator[Map[String, CellValue]] => A
  ): Try[A] =
    Using(scala.io.Source.fromFile(path, "UTF-8")) { source =>
      val limited = io.github.yusukensanta.parqueteer.core.util.RowLimiter
        .limitIterator(source.getLines(), maxRows)
      f(parseNdjsonLinesIterator(limited))
    }

  def withLtsvRows[A](path: String, maxRows: Option[Long])(
      f: Iterator[Map[String, CellValue]] => A
  ): Try[A] =
    Using(scala.io.Source.fromFile(path, "UTF-8")) { source =>
      val limited = io.github.yusukensanta.parqueteer.core.util.RowLimiter
        .limitIterator(source.getLines(), maxRows)
      f(LTSVParser.parseLines(limited))
    }

  def withCsvRows[A](path: String, maxRows: Option[Long])(
      f: Iterator[Map[String, CellValue]] => A
  ): Try[A] =
    Using(scala.io.Source.fromFile(path, "UTF-8")) { source =>
      val records = CsvParser.parseRecordsIncremental(source.getLines())
      val rows    = CsvParser.rowsToMaps(records)
      val limited = io.github.yusukensanta.parqueteer.core.util.RowLimiter
        .limitIterator(rows, maxRows)
      f(limited)
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
    parseNdjsonLinesIterator(content.linesIterator).toList

  private def parseNdjsonLinesIterator(
      lines: Iterator[String]
  ): Iterator[Map[String, CellValue]] = {
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

}
