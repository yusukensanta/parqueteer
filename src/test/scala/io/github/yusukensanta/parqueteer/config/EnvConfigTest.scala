package io.github.yusukensanta.parqueteer.config

import io.github.yusukensanta.parqueteer.core.models.ColorMode
import io.github.yusukensanta.parqueteer.core.models.OutputFormat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EnvConfigTest extends AnyFlatSpec with Matchers {

  private def env(pairs: (String, String)*): EnvConfig.EnvLookup =
    pairs.toMap.get

  "EnvConfig.SupportedVars" should "include key env var names" in {
    EnvConfig.SupportedVars should contain("PARQUETEER_CONFIG")
    EnvConfig.SupportedVars should contain("PARQUETEER_DEFAULT_FORMAT")
    EnvConfig.SupportedVars should contain("PARQUETEER_COLOR")
    EnvConfig.SupportedVars should contain("NO_COLOR")
  }

  "parsedDefaultFormat" should "return None when env var is unset" in {
    EnvConfig.parsedDefaultFormat(env()) shouldBe None
  }

  it should "parse table format" in {
    EnvConfig.parsedDefaultFormat(env("PARQUETEER_DEFAULT_FORMAT" -> "table")) shouldBe Some(
      OutputFormat.Table
    )
  }

  it should "parse json format (case-insensitive)" in {
    EnvConfig.parsedDefaultFormat(env("PARQUETEER_DEFAULT_FORMAT" -> "JSON")) shouldBe Some(
      OutputFormat.JSON
    )
  }

  it should "parse csv format" in {
    EnvConfig.parsedDefaultFormat(env("PARQUETEER_DEFAULT_FORMAT" -> "csv")) shouldBe Some(
      OutputFormat.CSV
    )
  }

  it should "parse pretty format" in {
    EnvConfig.parsedDefaultFormat(env("PARQUETEER_DEFAULT_FORMAT" -> "pretty")) shouldBe Some(
      OutputFormat.Pretty
    )
  }

  it should "parse markdown format" in {
    EnvConfig.parsedDefaultFormat(env("PARQUETEER_DEFAULT_FORMAT" -> "markdown")) shouldBe Some(
      OutputFormat.Markdown
    )
  }

  it should "parse ndjson format" in {
    EnvConfig.parsedDefaultFormat(env("PARQUETEER_DEFAULT_FORMAT" -> "ndjson")) shouldBe Some(
      OutputFormat.NDJSON
    )
  }

  it should "parse ltsv format" in {
    EnvConfig.parsedDefaultFormat(env("PARQUETEER_DEFAULT_FORMAT" -> "ltsv")) shouldBe Some(
      OutputFormat.LTSV
    )
  }

  it should "return None for unrecognized format" in {
    EnvConfig.parsedDefaultFormat(env("PARQUETEER_DEFAULT_FORMAT" -> "xml")) shouldBe None
  }

  "parsedColorMode" should "return Auto when no env vars set" in {
    EnvConfig.parsedColorMode(env()) shouldBe ColorMode.Auto
  }

  it should "return Never when NO_COLOR is set" in {
    EnvConfig.parsedColorMode(env("NO_COLOR" -> "1")) shouldBe ColorMode.Never
  }

  it should "ignore empty NO_COLOR" in {
    EnvConfig.parsedColorMode(env("NO_COLOR" -> "")) shouldBe ColorMode.Auto
  }

  it should "return Always when PARQUETEER_COLOR=always" in {
    EnvConfig.parsedColorMode(env("PARQUETEER_COLOR" -> "always")) shouldBe ColorMode.Always
  }

  it should "return Never when PARQUETEER_COLOR=never" in {
    EnvConfig.parsedColorMode(env("PARQUETEER_COLOR" -> "never")) shouldBe ColorMode.Never
  }

  it should "return Auto for unrecognized PARQUETEER_COLOR" in {
    EnvConfig.parsedColorMode(env("PARQUETEER_COLOR" -> "rainbow")) shouldBe ColorMode.Auto
  }

  it should "prefer NO_COLOR over PARQUETEER_COLOR" in {
    EnvConfig.parsedColorMode(
      env("NO_COLOR" -> "1", "PARQUETEER_COLOR" -> "always")
    ) shouldBe ColorMode.Never
  }

  "verbose" should "return false when unset" in {
    EnvConfig.verbose(env()) shouldBe false
  }

  it should "return true for 'true'" in {
    EnvConfig.verbose(env("PARQUETEER_VERBOSE" -> "true")) shouldBe true
  }

  it should "return true for '1'" in {
    EnvConfig.verbose(env("PARQUETEER_VERBOSE" -> "1")) shouldBe true
  }

  it should "return true for 'yes'" in {
    EnvConfig.verbose(env("PARQUETEER_VERBOSE" -> "yes")) shouldBe true
  }

  it should "return true for 'on'" in {
    EnvConfig.verbose(env("PARQUETEER_VERBOSE" -> "on")) shouldBe true
  }

  it should "return false for 'false'" in {
    EnvConfig.verbose(env("PARQUETEER_VERBOSE" -> "false")) shouldBe false
  }

  it should "be case-insensitive" in {
    EnvConfig.verbose(env("PARQUETEER_VERBOSE" -> "TRUE")) shouldBe true
  }

  "parsedMaxRows" should "return None when unset" in {
    EnvConfig.parsedMaxRows(env()) shouldBe None
  }

  it should "parse valid positive integer" in {
    EnvConfig.parsedMaxRows(env("PARQUETEER_MAX_ROWS" -> "100")) shouldBe Some(100L)
  }

  it should "return None for zero" in {
    EnvConfig.parsedMaxRows(env("PARQUETEER_MAX_ROWS" -> "0")) shouldBe None
  }

  it should "return None for negative" in {
    EnvConfig.parsedMaxRows(env("PARQUETEER_MAX_ROWS" -> "-5")) shouldBe None
  }

  it should "return None for non-numeric" in {
    EnvConfig.parsedMaxRows(env("PARQUETEER_MAX_ROWS" -> "abc")) shouldBe None
  }

  "allSet" should "return a subset of SupportedVars" in {
    val keys = EnvConfig.allSet.keySet
    keys.subsetOf(EnvConfig.SupportedVars.toSet) shouldBe true
  }

  "buildInitialGlobalOptions" should "return GlobalOptions without exception" in {
    val opts = EnvConfig.buildInitialGlobalOptions
    opts.verbose shouldBe a[Boolean]
    opts.colorMode should (be(ColorMode.Auto) or be(ColorMode.Never) or be(ColorMode.Always))
  }
}
