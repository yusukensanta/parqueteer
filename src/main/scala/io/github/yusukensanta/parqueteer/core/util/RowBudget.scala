package io.github.yusukensanta.parqueteer.core.util

/**
 * A running `--limit` shared across several sequential reads, so the limit
 * applies to the concatenated total rather than to each file separately.
 * Callers pass `nextLimit` to each read and `consume` what it returned.
 *
 * Not thread-safe: it must be consumed by one read at a time, in file order,
 * which is also why limited multi-file reads can't prefetch ahead.
 */
final class RowBudget(limit: Option[Long]) {
  private var remaining: Option[Long] = limit

  /** The row limit for the next read: whatever is left, or None if unlimited. */
  def nextLimit: Option[Long] = remaining

  def isExhausted: Boolean = remaining.contains(0L)

  def consume(rows: Long): Unit =
    remaining = remaining.map(r => (r - rows).max(0L))
}
