package io.github.yusukensanta.parqueteer.core.repositories

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LruCacheTest extends AnyFlatSpec with Matchers {

  "LruCache" should "count hits and misses" in {
    val cache = new LruCache[String, Int](2)
    cache.get("a") shouldBe None
    cache.put("a", 1)
    cache.get("a") shouldBe Some(1)
    (cache.hits, cache.misses) shouldBe (1L, 1L)
  }

  it should "evict the least-recently-used entry beyond maxSize" in {
    val cache = new LruCache[String, Int](2)
    cache.put("a", 1)
    cache.put("b", 2)
    cache.get("a") // touch a, so b becomes eldest
    cache.put("c", 3)
    cache.get("b") shouldBe None
    cache.get("a") shouldBe Some(1)
    cache.get("c") shouldBe Some(3)
  }

  it should "forget removed keys" in {
    val cache = new LruCache[String, Int](2)
    cache.put("a", 1)
    cache.remove("a")
    cache.get("a") shouldBe None
  }
}
