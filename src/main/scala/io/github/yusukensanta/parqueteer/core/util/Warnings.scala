package io.github.yusukensanta.parqueteer.core.util

private[parqueteer] object Warnings {
  private val logger = org.slf4j.LoggerFactory.getLogger("io.github.yusukensanta.parqueteer")

  def emit(msg: String): Unit =
    logger.warn(msg)
}
