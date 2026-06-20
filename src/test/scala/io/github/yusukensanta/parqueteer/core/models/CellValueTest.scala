package io.github.yusukensanta.parqueteer.core.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CellValueTest extends AnyFlatSpec with Matchers {

  private val ESC = 0x1b.toChar.toString // ANSI escape character
  private val DEL = 0x7f.toChar.toString // DEL (0x7F)
  private val C1  = 0x9f.toChar.toString // last C1 control code

  "CellValue.sanitizeTerminal" should "pass through normal strings unchanged" in {
    CellValue.sanitizeTerminal("hello world") shouldBe "hello world"
  }

  it should "pass through strings with tab, LF, and CR" in {
    CellValue.sanitizeTerminal("a\tb\nc\r") shouldBe "a\tb\nc\r"
  }

  it should "strip ESC (0x1B)" in {
    CellValue.sanitizeTerminal(ESC + "[2Jhello") shouldBe "[2Jhello"
  }

  it should "strip full ANSI clear-screen sequence" in {
    CellValue.sanitizeTerminal(ESC + "[2J" + ESC + "[1;1H") shouldBe "[2J[1;1H"
  }

  it should "strip NUL (0x00)" in {
    CellValue.sanitizeTerminal(0.toChar.toString) shouldBe ""
  }

  it should "strip DEL (0x7F)" in {
    CellValue.sanitizeTerminal("abc" + DEL + "def") shouldBe "abcdef"
  }

  it should "strip C1 control codes (0x80-0x9F)" in {
    CellValue.sanitizeTerminal(C1) shouldBe ""
  }

  it should "preserve Unicode non-control characters above 0xA0" in {
    CellValue.sanitizeTerminal(" 中文") shouldBe " 中文"
  }

  "CellValue.safeDisplay" should "strip ESC from Str values" in {
    CellValue
      .Str(ESC + "[31mred" + ESC + "[0m")
      .safeDisplay shouldBe "[31mred[0m"
  }

  it should "not affect numeric display values" in {
    CellValue.I32(42).safeDisplay shouldBe "42"
    CellValue.F64(3.14).safeDisplay shouldBe "3.14"
  }
}
