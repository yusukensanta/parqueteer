package io.github.yusukensanta.parqueteer.core.util

object SizeParser {
  private val units = Map(
    "B" -> 1L,
    "KB" -> 1024L,
    "MB" -> 1024L * 1024L,
    "GB" -> 1024L * 1024L * 1024L
  )
  private val pattern = """(\d+(?:\.\d+)?)\s*(B|KB|MB|GB)""".r

  def parse(sizeStr: String): Long =
    sizeStr.toUpperCase match {
      case pattern(size, unit) =>
        val bytes = BigDecimal(size) * units(unit)
        if (bytes > Long.MaxValue || bytes < 0)
          throw new IllegalArgumentException(
            s"Size too large (exceeds Long.MaxValue): $sizeStr"
          )
        bytes.toLong
      case _ =>
        throw new IllegalArgumentException(
          s"Invalid size format: $sizeStr. Expected format: <number><unit> (e.g., 128MB, 1.5GB)"
        )
    }
}
