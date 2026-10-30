package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.{ColumnStats, FileStats}
import io.github.yusukensanta.parqueteer.core.services.StatsAssertion.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StatsAssertionTest extends AnyFlatSpec with Matchers {

  private val stats = FileStats(
    columns = List(
      ColumnStats("id", "INT64", 0L, Some("1"), Some("100")),
      ColumnStats("amount", "DECIMAL(10,2)", 3L, Some("-5.50"), Some("999.99")),
      ColumnStats("day", "DATE", 0L, Some("2026-01-01"), Some("2026-10-03")),
      ColumnStats("address.city", "STRING", 7L, Some("Berlin"), Some("Tokyo")),
      ColumnStats("blob", "BINARY", -1L, None, None)
    ),
    totalRows = 1000L,
    rowGroupCount = 2L
  )

  private def check(source: String): Result =
    StatsAssertion.evaluate(StatsAssertion.parse(source).fold(e => fail(e), identity), stats)

  // ── parse ───────────────────────────────────────────────────────────────

  "StatsAssertion.parse" should "read every subject and operator" in {
    StatsAssertion.parse("rows > 0").map(a => (a.subject, a.op)) shouldBe Right(
      (Subject.Rows, Op.Gt)
    )
    StatsAssertion.parse("row_groups<=4").map(_.subject) shouldBe Right(Subject.RowGroups)
    StatsAssertion.parse("id.nulls == 0").map(_.subject) shouldBe Right(Subject.Nulls("id"))
    StatsAssertion.parse(" amount.min >= -10 ").map(_.op) shouldBe Right(Op.Ge)
    StatsAssertion.parse("day.max != \"2026-01-01\"").map(_.expected) shouldBe
      Right(Value.Text("2026-01-01"))
  }

  it should "split the statistic off the last dot so nested column names work" in {
    StatsAssertion.parse("address.city.nulls < 10").map(_.subject) shouldBe
      Right(Subject.Nulls("address.city"))
  }

  it should "reject malformed checks with a helpful message" in {
    StatsAssertion.parse("rows").left.toOption.get should include("<subject> <op> <value>")
    StatsAssertion.parse("id.avg > 1").left.toOption.get should include(".nulls, .min or .max")
    StatsAssertion.parse("count > 1").left.toOption.get should include("unknown subject")
    StatsAssertion.parse("id.min > abc").left.toOption.get should include(
      "number or a double-quoted"
    )
    StatsAssertion.parse("rows > \"5\"").left.toOption.get should include(
      "compare it with a number"
    )
  }

  // ── evaluate ────────────────────────────────────────────────────────────

  "StatsAssertion.evaluate" should "check row and row-group counts" in {
    check("rows == 1000").ok shouldBe true
    check("rows < 10").ok shouldBe false
    check("row_groups >= 2").ok shouldBe true
  }

  it should "check null counts and report the actual value" in {
    check("id.nulls == 0").ok shouldBe true
    val r = check("amount.nulls == 0")
    r.ok shouldBe false
    r.detail shouldBe "actual 3"
  }

  it should "compare numeric min/max numerically, including decimals and negatives" in {
    check("amount.min >= -10").ok shouldBe true
    check("amount.min >= 0").ok shouldBe false
    check("id.max <= 100").ok shouldBe true
    check("id.max < 99.5").ok shouldBe false
  }

  it should "compare quoted values as text, which orders ISO dates correctly" in {
    check("day.max <= \"2026-12-31\"").ok shouldBe true
    check("day.min > \"2026-06-01\"").ok shouldBe false
    check("address.city.max == \"Tokyo\"").ok shouldBe true
  }

  it should "fail, not pass, when the evidence is missing" in {
    check("blob.nulls == 0").detail should include("no null-count statistics")
    check("blob.min > 0").detail should include("no min statistic")
    check("missing.nulls == 0").detail should include("no column 'missing'")
    List("blob.nulls == 0", "blob.min > 0", "missing.nulls == 0").foreach(c =>
      check(c).ok shouldBe false
    )
  }

  it should "explain a numeric comparison against a non-numeric statistic" in {
    val r = check("day.max > 5")
    r.ok shouldBe false
    r.detail should include("quote the expected value")
  }
}
