package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocationParsingTest extends AnyFlatSpec with Matchers {

  "LocationParsing.parseLocation" should "parse a local path" in {
    LocationParsing.parseLocation("/tmp/a.parquet") shouldBe Right(LocalPath("/tmp/a.parquet"))
  }

  it should "wrap a malformed location in InvalidLocation" in {
    LocationParsing.parseLocation("s3://") should matchPattern {
      case Left(_: ParqueteerError.InvalidLocation) =>
    }
  }

  "LocationParsing.parseLocations" should "preserve order" in {
    LocationParsing.parseLocations(List("/a", "/b")) shouldBe Right(
      Vector(LocalPath("/a"), LocalPath("/b"))
    )
  }

  it should "short-circuit on the first malformed path" in {
    LocationParsing.parseLocations(List("/a", "s3://", "/c")).isLeft shouldBe true
  }
}
