package io.github.yusukensanta.parqueteer.core.util

object RowLimiter {

  def limitList[A](rows: List[A], maxRows: Option[Long]): List[A] =
    maxRows.fold(rows) { limit =>
      // A List's length is Int-bounded, so any limit >= Int.MaxValue can
      // never be reached — clamp before comparing/narrowing so a huge Long
      // limit can't wrap into a bogus negative Int.
      val limitInt = if limit >= Int.MaxValue.toLong then Int.MaxValue else limit.toInt
      // lengthCompare short-circuits at `limitInt` elements instead of
      // walking the whole list just to compare against its length.
      if limit < 0 || rows.lengthCompare(limitInt) <= 0 then rows
      else rows.take(limitInt)
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
