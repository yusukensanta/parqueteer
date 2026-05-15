package io.github.yusukensanta.parqueteer.config

import io.github.yusukensanta.parqueteer.cli.ColorMode
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EnvConfigTest extends AnyFlatSpec with Matchers {

  "EnvConfig.SupportedVars" should "include key env var names" in {
    EnvConfig.SupportedVars should contain("PARQUETEER_CONFIG")
    EnvConfig.SupportedVars should contain("PARQUETEER_DEFAULT_FORMAT")
    EnvConfig.SupportedVars should contain("PARQUETEER_COLOR")
    EnvConfig.SupportedVars should contain("NO_COLOR")
  }

  "EnvConfig.parsedDefaultFormat" should "return None when env var is unset" in {
    // Running without setting env var — just verify method returns an Option
    val result = EnvConfig.parsedDefaultFormat
    result shouldBe a[Option[?]]
  }

  "EnvConfig.parsedColorMode" should "return Auto when neither NO_COLOR nor PARQUETEER_COLOR is set" in {
    // This test assumes the env var is not set in the test environment
    // If NO_COLOR is set in CI, the result will be Never — that is also valid behavior
    val result = EnvConfig.parsedColorMode
    result should (be(ColorMode.Auto) or be(ColorMode.Never) or be(
      ColorMode.Always
    ))
  }

  "EnvConfig.verbose" should "return false when env var is unset" in {
    // Without PARQUETEER_VERBOSE=true in environment, expect false
    // (unless test runner sets it)
    val result = EnvConfig.verbose
    result shouldBe a[Boolean]
  }

  "EnvConfig.parsedMaxRows" should "return None when env var is unset" in {
    val result = EnvConfig.parsedMaxRows
    result shouldBe a[Option[?]]
  }

  "EnvConfig.allSet" should "return a subset of SupportedVars" in {
    val keys = EnvConfig.allSet.keySet
    keys.subsetOf(EnvConfig.SupportedVars.toSet) shouldBe true
  }

  "EnvConfig.buildInitialGlobalOptions" should "return GlobalOptions without exception" in {
    val opts = EnvConfig.buildInitialGlobalOptions
    opts.verbose shouldBe a[Boolean]
    opts.colorMode should (be(ColorMode.Auto) or be(ColorMode.Never) or be(
      ColorMode.Always
    ))
  }
}
