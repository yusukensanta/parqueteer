package io.github.yusukensanta.parqueteer.cli

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import DryRunReport.{detail, field}

class DryRunReportTest extends AnyFlatSpec with Matchers {

  "DryRunReport.render" should "align field values on the longest label" in {
    DryRunReport(
      "write out.parquet",
      List(
        field("Input", "in.json (json)"),
        field("Columns", "a, b"),
        field("Compression", "snappy")
      )
    ).render shouldBe
      """Dry run: would write out.parquet
        |  Input:       in.json (json)
        |  Columns:     a, b
        |  Compression: snappy""".stripMargin
  }

  it should "keep detail lines in place and unaligned" in {
    DryRunReport(
      "write out.parquet",
      List(
        field("Inputs", "2 files matched"),
        detail("  - a.csv"),
        detail("  - b.csv"),
        field("Schema mode", "Strict")
      )
    ).render shouldBe
      """Dry run: would write out.parquet
        |  Inputs:      2 files matched
        |    - a.csv
        |    - b.csv
        |  Schema mode: Strict""".stripMargin
  }

  it should "render just the title when there are no lines" in {
    DryRunReport(
      "merge 0 files → o.parquet",
      Nil
    ).render shouldBe "Dry run: would merge 0 files → o.parquet"
  }
}
