package io.github.yusukensanta.parqueteer.core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EithersTest extends AnyFlatSpec with Matchers {

  "Eithers.traverse" should "collect results in order when all succeed" in {
    Eithers.traverse(List(1, 2, 3))(i => Right(i * 2)) shouldBe Right(Vector(2, 4, 6))
  }

  it should "return Right(empty) for empty input" in {
    Eithers.traverse(List.empty[Int])(i => Right(i)) shouldBe Right(Vector.empty)
  }

  it should "stop at the first Left without evaluating later elements" in {
    var seen = List.empty[Int]
    val result = Eithers.traverse(List(1, 2, 3)) { i =>
      seen = seen :+ i
      if i == 2 then Left(s"bad $i") else Right(i)
    }
    result shouldBe Left("bad 2")
    seen shouldBe List(1, 2)
  }
}
