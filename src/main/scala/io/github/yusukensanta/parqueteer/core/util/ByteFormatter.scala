package io.github.yusukensanta.parqueteer.core.util

object ByteFormatter {
  private val units = List("B", "KB", "MB", "GB", "TB")

  def format(bytes: Long): String = {
    @annotation.tailrec
    def loop(size: Double, idx: Int): String =
      if size < 1024 || idx >= units.length - 1 then
        if size == size.toLong then s"${size.toLong} ${units(idx)}"
        else String.format(java.util.Locale.ROOT, "%.1f %s", size, units(idx))
      else loop(size / 1024, idx + 1)
    loop(bytes.toDouble, 0)
  }
}
