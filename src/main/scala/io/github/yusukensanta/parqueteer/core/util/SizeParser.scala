package io.github.yusukensanta.parqueteer.core.util

object SizeParser {
  private val units = Map(
    "B" -> 1L,
    "K" -> 1024L,
    "KB" -> 1024L,
    "M" -> 1024L * 1024L,
    "MB" -> 1024L * 1024L,
    "G" -> 1024L * 1024L * 1024L,
    "GB" -> 1024L * 1024L * 1024L,
    "T" -> 1024L * 1024L * 1024L * 1024L,
    "TB" -> 1024L * 1024L * 1024L * 1024L
  )
  // Unit is optional: bare integers (e.g. 134217728) are treated as bytes.
  // Longest alternative first prevents partial matches (e.g. GB matched as G).
  private val pattern =
    """(\d+(?:\.\d+)?)\s*(T(?:B)?|G(?:B)?|M(?:B)?|K(?:B)?|B)?""".r

  def parse(sizeStr: String): Long =
    sizeStr.trim.toUpperCase match {
      case pattern(size, unit) =>
        val multiplier = units.getOrElse(Option(unit).getOrElse("B"), 1L)
        val bytes = BigDecimal(size) * multiplier
        if (bytes > Long.MaxValue || bytes < 0)
          throw new IllegalArgumentException(
            s"Size too large (exceeds Long.MaxValue): $sizeStr"
          )
        if (multiplier == 1L && !bytes.isWhole)
          throw new IllegalArgumentException(
            s"Fractional byte count '$sizeStr' is not valid — use a unit (e.g. 1.5KB) or a whole number of bytes"
          )
        val result = bytes.toLong
        if (result == 0L && BigDecimal(size) > 0)
          throw new IllegalArgumentException(
            s"Size '$sizeStr' rounds to zero bytes — use a larger value or a coarser unit"
          )
        result
      case _ =>
        throw new IllegalArgumentException(
          s"Invalid size format: $sizeStr. Expected format: <number>[unit] (e.g., 128MB, 128M, 1.5GB, 134217728)"
        )
    }
}
