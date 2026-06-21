package io.github.yusukensanta.parqueteer.core.util

object RowLimiter {

  def limitList[A](rows: List[A], maxRows: Option[Long]): List[A] =
    maxRows.fold(rows) { limit =>
      if limit >= rows.length.toLong then rows else rows.take(limit.toInt)
    }

  def limitIterator[A](source: IterableOnce[A], maxRows: Option[Long]): Iterator[A] =
    maxRows.fold(source.iterator) { n =>
      val base  = source.iterator
      var taken = 0L
      new Iterator[A] {
        def hasNext: Boolean = taken < n && base.hasNext
        def next(): A        = { val v = base.next(); taken += 1; v }
      }
    }
}
