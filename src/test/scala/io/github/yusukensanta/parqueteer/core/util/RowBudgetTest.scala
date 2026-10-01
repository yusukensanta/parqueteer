package io.github.yusukensanta.parqueteer.core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RowBudgetTest extends AnyFlatSpec with Matchers {

  "RowBudget" should "never be exhausted without a limit" in {
    val budget = RowBudget(None)
    budget.consume(1_000_000L)
    budget.isExhausted shouldBe false
    budget.nextLimit shouldBe None
  }

  it should "carry the remaining limit across reads" in {
    val budget = RowBudget(Some(10L))
    budget.nextLimit shouldBe Some(10L)
    budget.consume(4L)
    budget.nextLimit shouldBe Some(6L)
    budget.consume(6L)
    budget.isExhausted shouldBe true
    budget.nextLimit shouldBe Some(0L)
  }

  it should "clamp at zero rather than going negative" in {
    val budget = RowBudget(Some(3L))
    budget.consume(5L)
    budget.nextLimit shouldBe Some(0L)
    budget.isExhausted shouldBe true
  }
}
