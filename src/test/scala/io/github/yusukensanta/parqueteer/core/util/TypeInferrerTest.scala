package io.github.yusukensanta.parqueteer.core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.{Instant, LocalDate}

class TypeInferrerTest extends AnyFlatSpec with Matchers {

  "TypeInferrer.inferCsvValue" should "infer Boolean true" in {
    TypeInferrer.inferCsvValue("true") shouldBe true
    TypeInferrer.inferCsvValue("True") shouldBe true
    TypeInferrer.inferCsvValue("TRUE") shouldBe true
  }

  it should "infer Boolean false" in {
    TypeInferrer.inferCsvValue("false") shouldBe false
    TypeInferrer.inferCsvValue("FALSE") shouldBe false
  }

  it should "infer ISO date as LocalDate" in {
    TypeInferrer.inferCsvValue("2024-01-15") shouldBe LocalDate.of(2024, 1, 15)
  }

  it should "infer ISO timestamp with T separator as Instant" in {
    val result = TypeInferrer.inferCsvValue("2024-01-15T12:30:00Z")
    result shouldBe a[Instant]
    result.toString should include("2024-01-15")
  }

  it should "infer ISO timestamp with space separator as Instant" in {
    val result = TypeInferrer.inferCsvValue("2024-01-15 12:30:00")
    result shouldBe a[Instant]
  }

  it should "infer decimal string as Double" in {
    TypeInferrer.inferCsvValue("3.14") shouldBe 3.14
    TypeInferrer.inferCsvValue("-2.5") shouldBe -2.5
  }

  it should "infer integer string as Long" in {
    TypeInferrer.inferCsvValue("42") shouldBe 42L
    TypeInferrer.inferCsvValue("-100") shouldBe -100L
  }

  it should "preserve leading zeros as String (IDs, ZIP codes)" in {
    TypeInferrer.inferCsvValue("007") shouldBe "007"
    TypeInferrer.inferCsvValue("01234") shouldBe "01234"
  }

  it should "keep plain strings as String" in {
    TypeInferrer.inferCsvValue("hello") shouldBe "hello"
    TypeInferrer.inferCsvValue("New York") shouldBe "New York"
  }

  it should "return null for empty string" in {
    Option(TypeInferrer.inferCsvValue("")) shouldBe None
    Option(TypeInferrer.inferCsvValue("   ")) shouldBe None
  }

  it should "not confuse a year-like integer with a date" in {
    TypeInferrer.inferCsvValue("2024") shouldBe 2024L
  }

  "TypeInferrer.inferJsonString" should "infer ISO date as LocalDate" in {
    TypeInferrer
      .inferJsonString("2024-03-20") shouldBe LocalDate.of(2024, 3, 20)
  }

  it should "infer ISO timestamp as Instant" in {
    val result = TypeInferrer.inferJsonString("2024-03-20T09:00:00Z")
    result shouldBe a[Instant]
  }

  it should "keep plain strings unchanged" in {
    TypeInferrer.inferJsonString("hello") shouldBe "hello"
    TypeInferrer.inferJsonString("42") shouldBe "42"
    TypeInferrer.inferJsonString("3.14") shouldBe "3.14"
  }

  it should "not infer boolean from string in JSON context" in {
    TypeInferrer.inferJsonString("true") shouldBe "true"
    TypeInferrer.inferJsonString("false") shouldBe "false"
  }
}
