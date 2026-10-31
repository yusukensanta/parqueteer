package io.github.yusukensanta.parqueteer.fuzz

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.lang.reflect.Modifier

/**
 * Keeps the Jazzer targets honest between fuzzing runs: Jazzer can only drive a
 * class that exposes `public static fuzzerTestOneInput(byte[])`, and each target
 * must survive empty, malformed and hostile input without throwing.
 */
class FuzzTargetsTest extends AnyFlatSpec with Matchers {

  private val targets = List(
    "io.github.yusukensanta.parqueteer.fuzz.FilterParserFuzzer",
    "io.github.yusukensanta.parqueteer.fuzz.CsvParserFuzzer",
    "io.github.yusukensanta.parqueteer.fuzz.LtsvParserFuzzer",
    "io.github.yusukensanta.parqueteer.fuzz.SizeParserFuzzer"
  )

  private val inputs: List[Array[Byte]] = List(
    "",
    "\u0000\u0001\u00ff",
    "a=1,b=\"unterminated",
    "col = 'x' AND (NOT y > 3 OR z IN (1,2",
    "id,name\n1,\"a\"\"b\"\n2",
    "k:v\tk2:v2\n:\t::",
    "1.5GB",
    "9999999999999999999999TB",
    "((((((((((((((((((((((((((((((((((((((((x=1))))))))))))))))))))))))))))))))))))))))"
  ).map(_.getBytes("UTF-8"))

  targets.foreach { name =>
    val short = name.substring(name.lastIndexOf('.') + 1)

    s"$short" should "expose the static entry point Jazzer looks for" in {
      val m = Class.forName(name).getMethod("fuzzerTestOneInput", classOf[Array[Byte]])
      Modifier.isStatic(m.getModifiers) shouldBe true
      Modifier.isPublic(m.getModifiers) shouldBe true
    }

    it should "accept empty, malformed and hostile input without throwing" in {
      val m = Class.forName(name).getMethod("fuzzerTestOneInput", classOf[Array[Byte]])
      inputs.foreach(in => m.invoke(null, in))
    }
  }
}
