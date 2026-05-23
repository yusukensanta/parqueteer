package io.github.yusukensanta.parqueteer.core.util

import io.github.yusukensanta.parqueteer.core.models.CellValue

object LTSVParser {

  private val LabelPattern = "^[0-9A-Za-z_.\\-]+$".r

  def parse(content: String): List[Map[String, CellValue]] =
    content
      .split("\r?\n", -1)
      .toList
      .filter(_.nonEmpty)
      .zipWithIndex
      .map { case (line, lineIdx) =>
        val fields = line.split("\t", -1)
        fields.map { field =>
          val colon = field.indexOf(':')
          if (colon < 0)
            throw new IllegalArgumentException(
              s"LTSV line ${lineIdx + 1}: field '$field' has no ':' separator"
            )
          val label = field.substring(0, colon)
          val value = field.substring(colon + 1)
          if (!LabelPattern.matches(label))
            throw new IllegalArgumentException(
              s"LTSV line ${lineIdx + 1}: invalid label '$label' — allowed chars: [0-9A-Za-z_.-]"
            )
          label -> TypeInferrer.inferCsvValue(value)
        }.toMap
      }
}
