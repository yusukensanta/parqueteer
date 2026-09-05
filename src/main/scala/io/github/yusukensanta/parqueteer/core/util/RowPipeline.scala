package io.github.yusukensanta.parqueteer.core.util

import io.github.yusukensanta.parqueteer.core.models.CellValue
import java.util.concurrent.{ArrayBlockingQueue, Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feeds `consume` every row of every item in `items`, strictly in item
 * order, while fetching up to `parallelism` items concurrently ahead of the
 * consumer. Each item's rows pass through a queue capped at
 * `queueCapacity`, so total in-flight memory is bounded by
 * `parallelism * queueCapacity` rows regardless of how many items there are
 * or how large any one of them is — a slow cloud fetch for item N+1
 * overlaps with the consumer still draining item N's rows instead of
 * waiting on it, and a producer that gets ahead of the consumer blocks on
 * a full queue rather than buffering unboundedly.
 *
 * Falls back to a plain sequential loop (no thread pool) when there's only
 * one item or parallelism is 1, so the common case pays no pool setup cost
 * and stays exactly as deterministic as a plain `foreach`.
 *
 * One item failing aborts the whole run (its error is returned, `consume`
 * stops being called) — merges and conversions produce a single output, so
 * a partial result from a missing file would be silently wrong data, not a
 * best-effort report.
 */
object RowPipeline {

  sealed private trait Event[+E]
  private case class RowEvent(row: Map[String, CellValue]) extends Event[Nothing]
  private case class DoneEvent(count: Long)                extends Event[Nothing]
  private case class ErrorEvent[E](err: E)                 extends Event[E]

  def run[A, E](
      items: List[A],
      parallelism: Int,
      queueCapacity: Int = 1024
  )(
      fetchOne: (A, Map[String, CellValue] => Unit) => Either[E, Long]
  )(consume: Map[String, CellValue] => Unit): Either[E, Long] =
    if items.size <= 1 || parallelism <= 1 then
      items.foldLeft[Either[E, Long]](Right(0L)) { (acc, item) =>
        acc.flatMap(total => fetchOne(item, consume).map(total + _))
      }
    else runPipelined(items, parallelism, queueCapacity)(fetchOne)(consume)

  private def runPipelined[A, E](
      items: List[A],
      parallelism: Int,
      queueCapacity: Int
  )(
      fetchOne: (A, Map[String, CellValue] => Unit) => Either[E, Long]
  )(consume: Map[String, CellValue] => Unit): Either[E, Long] = {
    val pool = Executors.newFixedThreadPool(
      parallelism.min(items.size),
      new ThreadFactory {
        private val counter = new AtomicInteger(0)
        override def newThread(r: Runnable): Thread = {
          val t = new Thread(r, s"parqueteer-file-fetch-ahead-${counter.getAndIncrement()}")
          t.setDaemon(true)
          t
        }
      }
    )
    val queues: Vector[ArrayBlockingQueue[Event[E]]] =
      Vector.fill(items.size)(new ArrayBlockingQueue[Event[E]](queueCapacity))
    try {
      items.zipWithIndex.foreach { case (item, idx) =>
        pool.execute { () =>
          fetchOne(item, row => queues(idx).put(RowEvent(row))) match {
            case Right(n)  => queues(idx).put(DoneEvent(n))
            case Left(err) => queues(idx).put(ErrorEvent(err))
          }
        }
      }
      var total              = 0L
      var idx                = 0
      var failure: Option[E] = None
      while idx < items.size && failure.isEmpty do
        queues(idx).take() match {
          case RowEvent(row)   => consume(row)
          case DoneEvent(n)    => total += n; idx += 1
          case ErrorEvent(err) => failure = Some(err)
        }
      failure.toLeft(total)
    } finally pool.shutdownNow()
  }
}
