package io.github.yusukensanta.parqueteer.core.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Try
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.toParqueteerError

class ParqueteerErrorTest extends AnyFlatSpec with Matchers {

  // ── toParqueteerError case ordering ────────────────────────────────────────
  // RowSchemaMismatchException extends IllegalArgumentException.
  // The match arm for RowSchemaMismatchException MUST precede the
  // IllegalArgumentException arm, otherwise it silently becomes ParseError("data")
  // instead of ParseError("input").

  "toParqueteerError" should "map RowSchemaMismatchException to ParseError(input)" in {
    val ex     = new ParqueteerError.RowSchemaMismatchException("field 'x' not in schema")
    val result = Try[Unit](throw ex).toParqueteerError
    result shouldBe Left(ParqueteerError.ParseError("input", "field 'x' not in schema"))
  }

  it should "map plain IllegalArgumentException to ParseError(data)" in {
    val ex     = new IllegalArgumentException("bad value")
    val result = Try[Unit](throw ex).toParqueteerError
    result shouldBe Left(ParqueteerError.ParseError("data", "bad value"))
  }

  it should "map FilterParseException to FilterParseError" in {
    val ex     = new ParqueteerError.FilterParseException("col > 5", "unknown column")
    val result = Try[Unit](throw ex).toParqueteerError
    result shouldBe Left(ParqueteerError.FilterParseError("col > 5", "unknown column"))
  }

  it should "map CloudAuthException to CloudAuthError" in {
    val ex     = new ParqueteerError.CloudAuthException("S3", "credentials expired")
    val result = Try[Unit](throw ex).toParqueteerError
    result shouldBe Left(ParqueteerError.CloudAuthError("S3", "credentials expired"))
  }

  it should "map FileNotFoundException to FileNotFound with path extraction" in {
    val ex = new java.io.FileNotFoundException("/tmp/missing.parquet (No such file or directory)")
    val result = Try[Unit](throw ex).toParqueteerError
    result shouldBe Left(ParqueteerError.FileNotFound("/tmp/missing.parquet"))
  }

  it should "map FileNotFoundException without parenthetical suffix" in {
    val ex     = new java.io.FileNotFoundException("/tmp/missing.parquet")
    val result = Try[Unit](throw ex).toParqueteerError
    result shouldBe Left(ParqueteerError.FileNotFound("/tmp/missing.parquet"))
  }

  it should "map unknown throwable to IOError" in {
    val ex     = new RuntimeException("something broke")
    val result = Try[Unit](throw ex).toParqueteerError
    result shouldBe Left(ParqueteerError.IOError(ex))
  }

  it should "pass through Success unchanged" in {
    val result = Try(42).toParqueteerError
    result shouldBe Right(42)
  }

  // ── Exit codes ─────────────────────────────────────────────────────────────

  "ParqueteerError exit codes" should "be distinct per variant" in {
    val errors: List[ParqueteerError] = List(
      ParqueteerError.IOError(new RuntimeException("x")),
      ParqueteerError.ParseError("f", "m"),
      ParqueteerError.FileNotFound("p"),
      ParqueteerError.SchemaMismatch("a", "b"),
      ParqueteerError.CloudAuthError("S3", "m"),
      ParqueteerError.InvalidFormat("f", "m"),
      ParqueteerError.FilterParseError("e", "m")
    )
    val codes = errors.map(_.exitCode)
    codes.distinct.size shouldBe codes.size
  }

  "ParqueteerError exit codes" should "all be positive" in {
    val errors: List[ParqueteerError] = List(
      ParqueteerError.IOError(new RuntimeException("x")),
      ParqueteerError.ParseError("f", "m"),
      ParqueteerError.FileNotFound("p"),
      ParqueteerError.SchemaMismatch("a", "b"),
      ParqueteerError.CloudAuthError("S3", "m"),
      ParqueteerError.InvalidFormat("f", "m"),
      ParqueteerError.FilterParseError("e", "m")
    )
    errors.foreach(e => e.exitCode should be > 0)
  }
}
