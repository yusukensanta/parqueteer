package io.github.yusukensanta.parqueteer.core.util

object SizeParser {
  private val units = Map(
    "B"  -> 1L,
    "K"  -> 1024L,
    "KB" -> 1024L,
    "M"  -> 1024L * 1024L,
    "MB" -> 1024L * 1024L,
    "G"  -> 1024L * 1024L * 1024L,
    "GB" -> 1024L * 1024L * 1024L
  )
  private val pattern = """(\d+(?:\.\d+)?)\s*(G(?:B)?|M(?:B)?|K(?:B)?|B)""".r

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
          s"Invalid size format: $sizeStr. Expected format: <number><unit> (e.g., 128MB, 128M, 1.5GB)"
        )
    }
}
