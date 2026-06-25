package io.github.yusukensanta.parqueteer.core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.{ByteArrayOutputStream, PrintStream}

class WarningsTest extends AnyFlatSpec with Matchers {

  private def captureStdErr(block: => Unit): String = {
    val buf = new ByteArrayOutputStream()
    val old = System.err
    System.setErr(new PrintStream(buf))
    try block
    finally System.setErr(old)
    buf.toString("UTF-8")
  }

  "Warnings.emit" should "write WARN message to stderr via SLF4J" in {
    val captured = captureStdErr {
      Warnings.emit("test warning message")
    }
    captured should include("WARN")
    captured should include("test warning message")
  }

  it should "include logger name in output" in {
    val captured = captureStdErr {
      Warnings.emit("logger check")
    }
    captured should include("parqueteer")
  }
}
