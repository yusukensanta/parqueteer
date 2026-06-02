package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{CellValue, OutputFormat}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.{ByteArrayOutputStream, PrintStream}

class RowStreamWriterTest extends AnyFlatSpec with Matchers {

  private def capture(f: PrintStream => Unit): String = {
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos, true, "UTF-8")
    f(ps)
    ps.flush()
    baos.toString("UTF-8")
  }

  private def run(
      format: OutputFormat,
      rows: List[Map[String, CellValue]]
  ): String = capture { ps =>
    val w = RowStreamWriter(format, ps)
    w.begin()
    rows.foreach(w.writeRow)
    w.end()
  }

  private val row1 =
    Map("a" -> CellValue.I64(1L), "b" -> CellValue.Str("hello"))
  private val row2 =
    Map("a" -> CellValue.I64(2L), "b" -> CellValue.Str("world"))

  // ── NDJSON ──────────────────────────────────────────────────────────────────

  "RowStreamWriter NDJSON" should "emit one JSON object per line" in {
    val out = run(OutputFormat.NDJSON, List(row1, row2))
    val lines = out.trim.split("\n")
    lines should have length 2
    lines(0) should (include("\"a\":1") and include("\"b\":\"hello\""))
    lines(1) should (include("\"a\":2") and include("\"b\":\"world\""))
  }

  it should "emit nothing for empty input" in {
    run(OutputFormat.NDJSON, List.empty) shouldBe ""
  }

  it should "produce no output when begin and end are never called" in {
    val out = capture { ps =>
      val _ = RowStreamWriter(OutputFormat.NDJSON, ps)
      // do nothing — writer created but never opened
    }
    out shouldBe ""
  }

  // ── JSON ─────────────────────────────────────────────────────────────────────

  "RowStreamWriter JSON" should "emit a valid JSON array" in {
    val out = run(OutputFormat.JSON, List(row1, row2))
    out should startWith("[")
    out.trim should endWith("]")
    out should include("\"a\":1")
    out should include("\"a\":2")
  }

  it should "emit no comma before the first element" in {
    val out = run(OutputFormat.JSON, List(row1))
    out should not include ",{"
  }

  it should "separate elements with commas" in {
    val out = run(OutputFormat.JSON, List(row1, row2))
    out should include("},{")
  }

  it should "emit [] for empty input" in {
    run(OutputFormat.JSON, List.empty).trim shouldBe "[]"
  }

  "RowStreamWriter JSON" should "produce no output when begin and end are never called" in {
    val out = capture { ps =>
      val _ = RowStreamWriter(OutputFormat.JSON, ps)
      // do nothing — writer created but never opened
    }
    out shouldBe ""
  }

  it should "produce no output when end is called without begin" in {
    val out = capture { ps =>
      val w = RowStreamWriter(OutputFormat.JSON, ps)
      w.end() // begin() never called — must not emit ']'
    }
    out shouldBe ""
  }

  it should "emit [] when begin and end are called with no rows" in {
    val out = capture { ps =>
      val w = RowStreamWriter(OutputFormat.JSON, ps)
      w.begin()
      w.end()
    }
    out.trim shouldBe "[]"
  }

  // ── CSV ──────────────────────────────────────────────────────────────────────

  "RowStreamWriter CSV" should "emit header then data rows" in {
    val out = run(OutputFormat.CSV, List(row1))
    val lines = out.trim.split("\r?\n")
    lines should have length 2
    lines(0) should (include("a") and include("b"))
    lines(1) should (include("1") and include("hello"))
  }

  it should "quote values containing commas" in {
    val r = Map("x" -> CellValue.Str("a,b"))
    val out = run(OutputFormat.CSV, List(r))
    out should include("\"a,b\"")
  }

  it should "quote values containing double quotes" in {
    val r = Map("x" -> CellValue.Str("say \"hi\""))
    val out = run(OutputFormat.CSV, List(r))
    out should include("\"say \"\"hi\"\"\"")
  }

  it should "emit nothing for empty input" in {
    run(OutputFormat.CSV, List.empty) shouldBe ""
  }

  "RowStreamWriter CSV" should "produce no output when begin and end are never called" in {
    val out = capture { ps =>
      val _ = RowStreamWriter(OutputFormat.CSV, ps)
      // no calls at all
    }
    out shouldBe ""
  }

  // ── Table ─────────────────────────────────────────────────────────────────────

  "RowStreamWriter Table" should "emit table borders and header" in {
    val out = run(OutputFormat.Table, List(row1, row2))
    out should include("┌")
    out should include("┐")
    out should include("└")
    out should include("┘")
    out should include("a")
    out should include("b")
  }

  it should "emit data values in the table" in {
    val out = run(OutputFormat.Table, List(row1))
    out should include("1")
    out should include("hello")
  }

  it should "emit nothing for empty input" in {
    val out = run(OutputFormat.Table, List.empty)
    out.trim shouldBe ""
  }

  // ── Markdown ─────────────────────────────────────────────────────────────────

  "RowStreamWriter Markdown" should "emit header and separator rows" in {
    val out = run(OutputFormat.Markdown, List(row1, row2))
    val lines = out.trim.split("\n")
    lines(0) should startWith("|")
    lines(1) should include("---")
    lines should have length 4
  }

  it should "escape pipe characters in values" in {
    val r = Map("x" -> CellValue.Str("a|b"))
    val out = run(OutputFormat.Markdown, List(r))
    out should include("a\\|b")
  }

  it should "emit nothing for empty input" in {
    val out = run(OutputFormat.Markdown, List.empty)
    out.trim shouldBe ""
  }

  // ── LTSV ─────────────────────────────────────────────────────────────────────

  "RowStreamWriter LTSV" should "emit one LTSV line per row" in {
    val out = run(OutputFormat.LTSV, List(row1, row2))
    val lines = out.trim.split("\n")
    lines should have length 2
    lines(0) should (include("a:1") or include("b:hello"))
    lines(1) should (include("a:2") or include("b:world"))
  }

  it should "emit nothing for empty input" in {
    run(OutputFormat.LTSV, List.empty) shouldBe ""
  }

  // ── Schema drift ─────────────────────────────────────────────────────────────
  // CSV freezes columns on the first row; extra keys in later rows are dropped.
  // Table/Markdown use a sample window (50 rows) and include all keys seen during
  // sampling; the unseen-key warning only fires for rows arriving after the flush.

  "RowStreamWriter CSV" should "drop unseen keys introduced after the header row" in {
    val r1 = Map("a" -> CellValue.I64(1L))
    val r2 = Map("a" -> CellValue.I64(2L), "z" -> CellValue.Str("extra"))
    val out = run(OutputFormat.CSV, List(r1, r2))
    val lines = out.trim.split("\r?\n")
    lines(0) shouldBe "a"
    lines(1) shouldBe "1"
    lines(2) shouldBe "2"
    out should not include "extra"
  }

  "RowStreamWriter Table" should "include extra keys that appear within the sample window" in {
    val r1 = Map("a" -> CellValue.I64(1L))
    val r2 = Map("a" -> CellValue.I64(2L), "z" -> CellValue.Str("extra"))
    val out = run(OutputFormat.Table, List(r1, r2))
    out should include("a")
    out should include("z")
    out should include("1")
    out should include("extra")
  }

  "RowStreamWriter Markdown" should "include extra keys that appear within the sample window" in {
    val r1 = Map("a" -> CellValue.I64(1L))
    val r2 = Map("a" -> CellValue.I64(2L), "z" -> CellValue.Str("extra"))
    val out = run(OutputFormat.Markdown, List(r1, r2))
    out should include("a")
    out should include("z")
    out should include("extra")
  }

  // ── Pretty (delegates to NDJSON) ─────────────────────────────────────────────

  "RowStreamWriter Pretty" should "emit one JSON object per line (same as NDJSON)" in {
    val out = run(OutputFormat.Pretty, List(row1))
    out.trim should (include("\"a\":1") and include("\"b\":\"hello\""))
  }

  // ── Column ordering (insertion order preserved) ───────────────────────────────

  "RowStreamWriter column ordering" should "preserve insertion order (not sort alphabetically) in CSV" in {
    val orderedRow = scala.collection.immutable.ListMap(
      "z_col" -> CellValue.I64(3L),
      "a_col" -> CellValue.I64(1L),
      "m_col" -> CellValue.I64(2L)
    )
    val out = run(OutputFormat.CSV, List(orderedRow))
    val header = out.split("\r?\n").head
    header shouldBe "z_col,a_col,m_col"
  }

  it should "preserve insertion order in Table format" in {
    val orderedRow = scala.collection.immutable.ListMap(
      "z_col" -> CellValue.I64(3L),
      "a_col" -> CellValue.I64(1L),
      "m_col" -> CellValue.I64(2L)
    )
    val out = run(OutputFormat.Table, List(orderedRow))
    out should include("z_col")
    val headerLine = out
      .split("\n")
      .find(_.contains("z_col"))
      .getOrElse(fail("No header line containing z_col found in output"))
    headerLine.indexOf("z_col") should be < headerLine.indexOf("a_col")
    headerLine.indexOf("a_col") should be < headerLine.indexOf("m_col")
  }

  it should "preserve insertion order in Markdown format" in {
    val orderedRow = scala.collection.immutable.ListMap(
      "z_col" -> CellValue.I64(3L),
      "a_col" -> CellValue.I64(1L),
      "m_col" -> CellValue.I64(2L)
    )
    val out = run(OutputFormat.Markdown, List(orderedRow))
    out should include("z_col")
    val headerLine = out
      .split("\n")
      .find(_.contains("z_col"))
      .getOrElse(fail("No header line containing z_col found in output"))
    headerLine.indexOf("z_col") should be < headerLine.indexOf("a_col")
    headerLine.indexOf("a_col") should be < headerLine.indexOf("m_col")
  }

  it should "escape CSV header columns that contain commas" in {
    val row = scala.collection.immutable.ListMap(
      "a,b" -> CellValue.Str("val1"),
      "normal" -> CellValue.Str("val2")
    )
    val out = run(OutputFormat.CSV, List(row))
    val header = out.split("\r?\n").head
    header shouldBe "\"a,b\",normal"
  }
}
