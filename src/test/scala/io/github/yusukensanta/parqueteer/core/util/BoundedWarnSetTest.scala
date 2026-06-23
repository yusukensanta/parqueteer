package io.github.yusukensanta.parqueteer.core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BoundedWarnSetTest extends AnyFlatSpec with Matchers {

  "BoundedWarnSet" should "add new keys and return true" in {
    val set = BoundedWarnSet(100)
    set.add("a") shouldBe true
  }

  it should "return false for duplicate keys" in {
    val set = BoundedWarnSet(100)
    set.add("a") shouldBe true
    set.add("a") shouldBe false
  }

  it should "stop accepting keys when at capacity" in {
    val set = BoundedWarnSet(3)
    set.add("a") shouldBe true
    set.add("b") shouldBe true
    set.add("c") shouldBe true
    set.add("d") shouldBe false
  }

  it should "still reject duplicates at capacity" in {
    val set = BoundedWarnSet(2)
    set.add("a") shouldBe true
    set.add("b") shouldBe true
    set.add("a") shouldBe false
    set.add("c") shouldBe false
  }

  it should "work with default maxSize" in {
    val set = BoundedWarnSet()
    set.add("key") shouldBe true
    set.add("key") shouldBe false
  }
}
