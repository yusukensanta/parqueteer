package io.github.yusukensanta.parqueteer.core.util

import io.github.yusukensanta.parqueteer.core.models.CellValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen
import java.time.LocalDate

class TypeInferrerTest
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks {

  "TypeInferrer.inferCsvValue" should "infer Boolean true" in {
    TypeInferrer.inferCsvValue("true") shouldBe CellValue.Bool(true)
    TypeInferrer.inferCsvValue("True") shouldBe CellValue.Bool(true)
    TypeInferrer.inferCsvValue("TRUE") shouldBe CellValue.Bool(true)
  }

  it should "infer Boolean false" in {
    TypeInferrer.inferCsvValue("false") shouldBe CellValue.Bool(false)
    TypeInferrer.inferCsvValue("FALSE") shouldBe CellValue.Bool(false)
  }

  it should "infer ISO date as LocalDate" in {
    TypeInferrer.inferCsvValue(
      "2024-01-15"
    ) shouldBe CellValue.Date(LocalDate.of(2024, 1, 15))
  }

  it should "infer ISO timestamp with T separator as Instant" in {
    val result = TypeInferrer.inferCsvValue("2024-01-15T12:30:00Z")
    result shouldBe a[CellValue.Ts]
    result.toString should include("2024-01-15")
  }

  it should "infer ISO timestamp with space separator as Instant" in {
    val result = TypeInferrer.inferCsvValue("2024-01-15 12:30:00")
    result shouldBe a[CellValue.Ts]
  }

  it should "treat space-delimited datetime as UTC" in {
    TypeInferrer.inferCsvValue("2024-01-15 12:30:00") shouldBe
      CellValue.Ts(java.time.Instant.parse("2024-01-15T12:30:00Z"))
  }

  it should "infer decimal string as Dec (exact precision, not F64)" in {
    TypeInferrer.inferCsvValue("3.14") shouldBe CellValue.Dec(
      scala.math.BigDecimal("3.14")
    )
    TypeInferrer.inferCsvValue("-2.5") shouldBe CellValue.Dec(
      scala.math.BigDecimal("-2.5")
    )
    TypeInferrer.inferCsvValue("1.0000000000000001") shouldBe CellValue.Dec(
      scala.math.BigDecimal("1.0000000000000001")
    )
  }

  it should "infer integer string as Long" in {
    TypeInferrer.inferCsvValue("42") shouldBe CellValue.I64(42L)
    TypeInferrer.inferCsvValue("-100") shouldBe CellValue.I64(-100L)
  }

  it should "preserve leading zeros as CellValue.Str (IDs, ZIP codes)" in {
    TypeInferrer.inferCsvValue("007") shouldBe CellValue.Str("007")
    TypeInferrer.inferCsvValue("01234") shouldBe CellValue.Str("01234")
  }

  it should "keep plain strings as CellValue.Str" in {
    TypeInferrer.inferCsvValue("hello") shouldBe CellValue.Str("hello")
    TypeInferrer.inferCsvValue("New York") shouldBe CellValue.Str("New York")
  }

  it should "return CellValue.Null for empty or blank string" in {
    TypeInferrer.inferCsvValue("") shouldBe CellValue.Null
    TypeInferrer.inferCsvValue("   ") shouldBe CellValue.Null
  }

  it should "not confuse a year-like integer with a date" in {
    TypeInferrer.inferCsvValue("2024") shouldBe CellValue.I64(2024L)
  }

  "TypeInferrer.inferJsonString" should "infer ISO date as LocalDate" in {
    TypeInferrer
      .inferJsonString("2024-03-20") shouldBe CellValue.Date(
      LocalDate.of(2024, 3, 20)
    )
  }

  it should "infer ISO timestamp as Instant" in {
    val result = TypeInferrer.inferJsonString("2024-03-20T09:00:00Z")
    result shouldBe a[CellValue.Ts]
  }

  it should "keep plain strings unchanged" in {
    TypeInferrer.inferJsonString("hello") shouldBe CellValue.Str("hello")
    TypeInferrer.inferJsonString("42") shouldBe CellValue.Str("42")
    TypeInferrer.inferJsonString("3.14") shouldBe CellValue.Str("3.14")
  }

  it should "not infer boolean from string in JSON context" in {
    TypeInferrer.inferJsonString("true") shouldBe CellValue.Str("true")
    TypeInferrer.inferJsonString("false") shouldBe CellValue.Str("false")
  }

  // ── Property-based: leading-zero invariant ────────────────────────────────
  // Any digit string starting with '0' and length > 1 must remain a CellValue.Str.

  "TypeInferrer.inferCsvValue (property)" should "never coerce leading-zero strings to Long" in {
    val leadingZero: Gen[String] = for {
      rest <- Gen.nonEmptyListOf(Gen.numChar)
    } yield "0" + rest.mkString // length >= 2, always starts with 0
    forAll(leadingZero) { s =>
      TypeInferrer.inferCsvValue(s) shouldBe a[CellValue.Str]
    }
  }

  it should "always produce a CellValue.I64 for canonical positive integer strings" in {
    // Cap at 18 digits: 18 digits starting with 1-9 always fit within Long.MaxValue
    val canonical: Gen[String] = for {
      first <- Gen.choose('1', '9')
      len <- Gen.choose(0, 17)
      rest <- Gen.listOfN(len, Gen.numChar)
    } yield first.toString + rest.mkString
    forAll(canonical) { s =>
      TypeInferrer.inferCsvValue(s) shouldBe a[CellValue.I64]
    }
  }
}
