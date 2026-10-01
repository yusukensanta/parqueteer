package io.github.yusukensanta.parqueteer.core.repositories

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe bounded LRU cache that counts hits and misses. Backs both the
 * per-repository footer cache and the Hadoop Configuration cache, which
 * previously each hand-rolled the same synchronized access-ordered
 * LinkedHashMap plus a pair of AtomicLong counters.
 */
final private[repositories] class LruCache[K, V](maxSize: Int) {

  private val underlying: java.util.Map[K, V] =
    java.util.Collections.synchronizedMap(
      new java.util.LinkedHashMap[K, V](16, 0.75f, true) {

        override def removeEldestEntry(eldest: java.util.Map.Entry[K, V]): Boolean =
          size() > maxSize
      }
    )

  private val hitCount  = new AtomicLong(0)
  private val missCount = new AtomicLong(0)

  /** Looks up `key`, recording a hit or a miss. */
  def get(key: K): Option[V] = {
    val found = Option(underlying.get(key))
    if found.isDefined then hitCount.incrementAndGet() else missCount.incrementAndGet()
    found
  }

  def put(key: K, value: V): Unit = underlying.put(key, value)

  def remove(key: K): Unit = underlying.remove(key)

  def hits: Long   = hitCount.get()
  def misses: Long = missCount.get()
}
