package io.github.yusukensanta.parqueteer.core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RowLimiterTest extends AnyFlatSpec with Matchers {

  "limitList" should "return all rows when maxRows is None" in {
    val rows = List(1, 2, 3, 4, 5)
    RowLimiter.limitList(rows, None) shouldBe rows
  }

  it should "return all rows when limit exceeds list size" in {
    val rows = List(1, 2, 3)
    RowLimiter.limitList(rows, Some(10)) shouldBe rows
  }

  it should "truncate rows to limit" in {
    val rows = List(1, 2, 3, 4, 5)
    RowLimiter.limitList(rows, Some(3)) shouldBe List(1, 2, 3)
  }

  it should "return empty list for limit 0" in {
    val rows = List(1, 2, 3)
    RowLimiter.limitList(rows, Some(0)) shouldBe List.empty
  }

  it should "return exact list when limit equals size" in {
    val rows = List(1, 2, 3)
    RowLimiter.limitList(rows, Some(3)) shouldBe rows
  }

  "limitIterator" should "return all elements when maxRows is None" in {
    val iter = RowLimiter.limitIterator(List(1, 2, 3), None)
    iter.toList shouldBe List(1, 2, 3)
  }

  it should "truncate iterator to limit" in {
    val iter = RowLimiter.limitIterator(List(1, 2, 3, 4, 5), Some(2))
    iter.toList shouldBe List(1, 2)
  }

  it should "return all elements when limit exceeds size" in {
    val iter = RowLimiter.limitIterator(List(1, 2), Some(10))
    iter.toList shouldBe List(1, 2)
  }

  it should "return empty for limit 0" in {
    val iter = RowLimiter.limitIterator(List(1, 2, 3), Some(0))
    iter.toList shouldBe List.empty
  }
}
