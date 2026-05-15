package io.github.yusukensanta.parqueteer.core.util

object SizeParser {
  private val units = Map(
    "B" -> 1L,
    "KB" -> 1024L,
    "MB" -> 1024L * 1024L,
    "GB" -> 1024L * 1024L * 1024L
  )
  private val pattern = """(\d+)\s*(B|KB|MB|GB)""".r

  def parse(sizeStr: String): Long =
    sizeStr.toUpperCase match {
      case pattern(size, unit) => size.toLong * units(unit)
      case _ =>
        throw new IllegalArgumentException(s"Invalid size format: $sizeStr")
    }
}
