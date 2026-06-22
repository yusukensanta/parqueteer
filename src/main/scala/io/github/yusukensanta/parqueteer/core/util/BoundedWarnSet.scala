package io.github.yusukensanta.parqueteer.core.util

private[parqueteer] class BoundedWarnSet(maxSize: Int = 10_000) {
  private val set = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  def add(key: String): Boolean =
    if set.size() >= maxSize then false
    else set.add(key)
}
