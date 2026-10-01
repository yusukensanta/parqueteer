package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SchemaReconcilerTest extends AnyFlatSpec with Matchers {

  private def f(name: String, t: String, optional: Boolean = false) =
    FieldSummary(name, t, optional)

  private val paths = List("/a.parquet", "/b.parquet", "/c.parquet")

  // ── strict ──────────────────────────────────────────────────────────────

  "SchemaReconciler.reconcile (strict)" should "return the first schema when all match" in {
    val s = List(f("id", "INT64"), f("name", "STRING", optional = true))
    SchemaReconciler.reconcile(Vector(s, s), paths, SchemaMode.Strict) shouldBe Right(s)
  }

  it should "return Nil for no inputs" in {
    SchemaReconciler.reconcile(Vector.empty, Nil, SchemaMode.Strict) shouldBe Right(Nil)
  }

  it should "name the first mismatching file" in {
    val a = List(f("id", "INT64"))
    val result =
      SchemaReconciler.reconcile(Vector(a, a, List(f("id", "INT32"))), paths, SchemaMode.Strict)
    result.left.toOption.get shouldBe ParqueteerError.SchemaMismatch(
      "/c.parquet",
      "type/nullability changed: id (INT64 → INT32). Use --schema-mode union to allow schema differences."
    )
  }

  // ── union ───────────────────────────────────────────────────────────────

  "SchemaReconciler.reconcile (union)" should "keep first-seen column order and mark partial columns optional" in {
    val a = List(f("id", "INT64"), f("x", "STRING"))
    val b = List(f("y", "DOUBLE"), f("id", "INT64"))
    SchemaReconciler.reconcile(Vector(a, b), paths, SchemaMode.Union) shouldBe Right(
      List(f("id", "INT64"), f("x", "STRING", optional = true), f("y", "DOUBLE", optional = true))
    )
  }

  it should "mark a column optional if any input has it optional" in {
    val a = List(f("id", "INT64"))
    val b = List(f("id", "INT64", optional = true))
    SchemaReconciler.reconcile(Vector(a, b), paths, SchemaMode.Union) shouldBe Right(
      List(f("id", "INT64", optional = true))
    )
  }

  it should "reject type conflicts, naming the conflicting file" in {
    val result = SchemaReconciler.reconcile(
      Vector(List(f("id", "INT64")), List(f("id", "STRING"))),
      paths,
      SchemaMode.Union
    )
    val err = result.left.toOption.get
    err shouldBe a[ParqueteerError.SchemaMismatch]
    err.userMessage should include("/b.parquet")
    err.userMessage should include("'id' (INT64 vs STRING)")
  }

  it should "reject duplicate column names within one file" in {
    val result = SchemaReconciler.reconcile(
      Vector(List(f("id", "INT64"), f("id", "INT64"))),
      paths,
      SchemaMode.Union
    )
    result.left.toOption.get.userMessage should include("duplicate column names: id")
  }

  // ── diff ────────────────────────────────────────────────────────────────

  "SchemaReconciler.diff" should "classify added, removed, changed and unchanged columns" in {
    def col(n: String, t: String, opt: Boolean = false) = ColumnInfo(n, t, opt, 0, 0, "")
    val d = SchemaReconciler.diff(
      List(col("a", "INT32"), col("b", "STRING"), col("c", "INT64")),
      List(col("a", "INT32"), col("c", "INT64", opt = true), col("d", "DOUBLE"))
    )
    d.added.map(_.name) shouldBe List("d")
    d.removed.map(_.name) shouldBe List("b")
    d.changed shouldBe List(ColumnChange("c", "INT64", "INT64", false, true))
    d.unchanged shouldBe List("a")
    d.identical shouldBe false
  }

  // ── SchemaReconciler.describeMismatch ──────────────────────────────────────────────
  "SchemaReconciler.describeMismatch" should "report type change" in {
    val expected = Set(FieldSummary("age", "INT32", false))
    val actual   = Set(FieldSummary("age", "INT64", false))
    val result   = SchemaReconciler.describeMismatch(expected, actual)
    result should include("type/nullability changed")
    result should include("age")
    result should include("INT32")
    result should include("INT64")
  }

  it should "report missing fields" in {
    val expected = Set(FieldSummary("a", "INT32", false), FieldSummary("b", "INT32", false))
    val actual   = Set(FieldSummary("a", "INT32", false))
    val result   = SchemaReconciler.describeMismatch(expected, actual)
    result should include("missing")
    result should include("b")
  }

  it should "report extra fields" in {
    val expected = Set(FieldSummary("a", "INT32", false))
    val actual   = Set(FieldSummary("a", "INT32", false), FieldSummary("c", "INT64", true))
    val result   = SchemaReconciler.describeMismatch(expected, actual)
    result should include("extra")
    result should include("c")
  }

  it should "report nullability change with ? suffix" in {
    val expected = Set(FieldSummary("x", "INT32", false))
    val actual   = Set(FieldSummary("x", "INT32", true))
    val result   = SchemaReconciler.describeMismatch(expected, actual)
    result should include("INT32?")
  }
}
