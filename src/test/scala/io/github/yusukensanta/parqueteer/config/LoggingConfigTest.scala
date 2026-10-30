package io.github.yusukensanta.parqueteer.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

class LoggingConfigTest extends AnyFlatSpec with Matchers {

  private def logger(name: String) = LoggerFactory.getLogger(name)

  "simplelogger.properties" should "silence the S3 analytics accelerator's spurious ERROR logs" in {
    val reader = logger(
      "software.amazon.s3.analyticsaccelerator.io.physical.reader.StreamReader"
    )
    reader.isErrorEnabled shouldBe false
  }

  it should "keep the existing third-party loggers at error level" in {
    logger("org.apache.hadoop.fs.s3a.S3AFileSystem").isWarnEnabled shouldBe false
    logger("org.apache.hadoop.fs.s3a.S3AFileSystem").isErrorEnabled shouldBe true
  }
}
