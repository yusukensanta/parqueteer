package io.github.yusukensanta.parqueteer.fuzz

import io.github.yusukensanta.parqueteer.core.filters.FilterParser
import io.github.yusukensanta.parqueteer.core.util.{CsvParser, LTSVParser, SizeParser}
import java.nio.charset.StandardCharsets

/**
 * Jazzer fuzz targets for the parsers that read untrusted text: `--filter`
 * expressions, CSV/LTSV input and size strings. Jazzer finds each target through
 * the public static `fuzzerTestOneInput(byte[])` method that Scala emits for a
 * top-level object. Each parser rejects bad input with a documented error (an
 * `Either.Left` or `IllegalArgumentException`); any other exception escaping
 * is a bug and fails the run.
 */
private def text(data: Array[Byte]): String = new String(data, StandardCharsets.UTF_8)

private def rejectingBadInput(body: => Unit): Unit =
  try body
  catch { case _: IllegalArgumentException => () }

object FilterParserFuzzer {

  def fuzzerTestOneInput(data: Array[Byte]): Unit =
    FilterParser.parse(text(data)): Unit
}

object CsvParserFuzzer {

  def fuzzerTestOneInput(data: Array[Byte]): Unit =
    rejectingBadInput(CsvParser.parse(text(data)): Unit)
}

object LtsvParserFuzzer {

  def fuzzerTestOneInput(data: Array[Byte]): Unit =
    rejectingBadInput(LTSVParser.parse(text(data)): Unit)
}

object SizeParserFuzzer {

  def fuzzerTestOneInput(data: Array[Byte]): Unit =
    rejectingBadInput(SizeParser.parse(text(data)): Unit)
}
