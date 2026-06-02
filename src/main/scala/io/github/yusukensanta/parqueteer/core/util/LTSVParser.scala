package io.github.yusukensanta.parqueteer.core.util

import io.github.yusukensanta.parqueteer.core.models.CellValue

object LTSVParser {

  // Valid LTSV label chars: 0x21–0x7E excluding ':' and '='
  private def isValidLabelChar(c: Char): Boolean =
    c > 0x20 && c <= 0x7e && c != ':' && c != '='

  private def isValidLabel(label: String): Boolean =
    label.nonEmpty && label.forall(isValidLabelChar)

  def parse(content: String): List[Map[String, CellValue]] =
    parseLines(content.linesIterator).toList

  def parseLines(lines: Iterator[String]): Iterator[Map[String, CellValue]] =
    lines.zipWithIndex
      .filter { case (line, _) => line.nonEmpty }
      .map { case (line, lineIdx) =>
        val pairs = line
          .stripSuffix("\r")
          .split("\t", -1)
          .map { field =>
            val colon = field.indexOf(':')
            if (colon < 0)
              throw new IllegalArgumentException(
                s"LTSV line ${lineIdx + 1}: field '$field' has no ':' separator"
              )
            val label = field.substring(0, colon)
            val value = field.substring(colon + 1)
            if (!isValidLabel(label))
              throw new IllegalArgumentException(
                s"LTSV line ${lineIdx + 1}: invalid label '$label' — allowed chars: [0x21-0x7E] except ':' and '='"
              )
            label -> TypeInferrer.inferCsvValue(value)
          }
        val seen = scala.collection.mutable.Set.empty[String]
        pairs.foreach { case (label, _) =>
          if (!seen.add(label))
            Console.err.println(
              s"[parqueteer] warning: LTSV line ${lineIdx + 1}: duplicate label '$label' — last value wins"
            )
        }
        scala.collection.immutable.ListMap(pairs*)
      }
}
