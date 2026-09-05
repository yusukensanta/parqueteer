package io.github.yusukensanta.parqueteer.core.util

import io.github.yusukensanta.parqueteer.core.models.CellValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.concurrent.atomic.AtomicInteger

class RowPipelineTest extends AnyFlatSpec with Matchers {

  private def row(id: Int): Map[String, CellValue] = Map("id" -> CellValue.I64(id.toLong))

  "RowPipeline.run" should "feed every row of every item to consume, in item order, sequentially" in {
    val consumed = scala.collection.mutable.ArrayBuffer.empty[Int]
    val order    = scala.collection.mutable.ArrayBuffer.empty[Int]
    val result = RowPipeline.run(List(1, 2, 3), parallelism = 1) { (item, sink) =>
      order += item
      sink(row(item))
      Right(1L)
    }(r => consumed += r("id").asInstanceOf[CellValue.I64].l.toInt)
    result shouldBe Right(3L)
    consumed.toList shouldBe List(1, 2, 3)
    order.toList shouldBe List(1, 2, 3)
  }

  it should "never fetch more than `parallelism` items concurrently" in {
    val inFlight    = new AtomicInteger(0)
    val maxObserved = new AtomicInteger(0)
    val items       = (1 to 12).toList
    val result = RowPipeline.run(items, parallelism = 3) { (item, sink) =>
      val current = inFlight.incrementAndGet()
      maxObserved.updateAndGet(prev => prev.max(current))
      Thread.sleep(10)
      sink(row(item))
      inFlight.decrementAndGet()
      Right(1L)
    }(_ => ())
    result shouldBe Right(12L)
    maxObserved.get should be <= 3
  }

  it should "keep consume order matching item order even when a later item finishes fetching first" in {
    val consumed = scala.collection.mutable.ArrayBuffer.empty[Int]
    val delayMs  = Map(1 -> 30L, 2 -> 0L, 3 -> 10L)
    RowPipeline.run(List(1, 2, 3), parallelism = 3) { (item, sink) =>
      if delayMs(item) > 0 then Thread.sleep(delayMs(item))
      sink(row(item))
      Right(1L)
    }(r => consumed.synchronized(consumed += r("id").asInstanceOf[CellValue.I64].l.toInt))
    consumed.toList shouldBe List(1, 2, 3)
  }

  it should "abort and return the error when one item fails, under concurrent fetch" in {
    val consumed = scala.collection.mutable.ArrayBuffer.empty[Int]
    val result = RowPipeline.run(List(1, 2, 3), parallelism = 3) { (item, sink) =>
      if item == 2 then Left("boom")
      else { sink(row(item)); Right(1L) }
    }(r => consumed.synchronized(consumed += r("id").asInstanceOf[CellValue.I64].l.toInt))
    result shouldBe Left("boom")
  }

  it should "fall back to a plain sequential loop for a single item regardless of parallelism" in {
    var ran = 0
    val result = RowPipeline.run(List("only"), parallelism = 8) { (_, sink) =>
      ran += 1
      sink(row(1))
      Right(1L)
    }(_ => ())
    result shouldBe Right(1L)
    ran shouldBe 1
  }

  it should "stay correct when a small queue capacity forces producers to backpressure" in {
    val consumed = scala.collection.mutable.ArrayBuffer.empty[Int]
    val items    = (1 to 20).toList
    val result = RowPipeline.run(items, parallelism = 4, queueCapacity = 2) { (item, sink) =>
      (1 to 50).foreach(_ => sink(row(item)))
      Right(50L)
    }(r => consumed.synchronized(consumed += r("id").asInstanceOf[CellValue.I64].l.toInt))
    result shouldBe Right(1000L)
    // 50 copies of each item's id, items strictly in order, nothing lost or reordered.
    consumed.toList shouldBe items.flatMap(i => List.fill(50)(i))
  }
}
