package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import org.scalatest.flatspec.AnyFlatSpec
import java.io.{ByteArrayOutputStream, PrintStream}

class StreamingOutputTest extends CliTestSupport {

  // ── ProgressRowStreamWriter ────────────────────────────────────────────

  "ProgressRowStreamWriter" should "emit progress at configured interval" in {
    val errBuf = new ByteArrayOutputStream()
    val errPs  = new PrintStream(errBuf)
    val delegate = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def writeRow(row: Map[String, CellValue]): Unit = ()
    }
    val pw = new ProgressRowStreamWriter(delegate, errPs, intervalRows = 5)
    pw.begin()
    (1 to 12).foreach(_ => pw.writeRow(Map.empty))
    pw.end()
    val stderr = errBuf.toString("UTF-8")
    stderr should include("5 rows")
    stderr should include("10 rows")
    stderr should include("done")
  }

  it should "not emit done if count below interval" in {
    val errBuf = new ByteArrayOutputStream()
    val errPs  = new PrintStream(errBuf)
    val delegate = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def writeRow(row: Map[String, CellValue]): Unit = ()
    }
    val pw = new ProgressRowStreamWriter(delegate, errPs, intervalRows = 100)
    pw.begin()
    (1 to 5).foreach(_ => pw.writeRow(Map.empty))
    pw.end()
    errBuf.toString("UTF-8") shouldBe empty
  }

  // ── runWithDeferredBegin ───────────────────────────────────────────────

  "runWithDeferredBegin" should "call begin before first row and end after all rows" in {
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    val writer = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def begin(): Unit                               = calls += "begin"
      override def writeRow(row: Map[String, CellValue]): Unit = calls += "row"
      override def end(): Unit                                 = calls += "end"
    }
    val result = StreamingOutput.runWithDeferredBegin(
      writer,
      process => { process(Map.empty); process(Map.empty); Right(2L) }
    )
    result shouldBe Right(2L)
    calls.toList shouldBe List("begin", "row", "row", "end")
  }

  it should "call begin and end even with no rows on success" in {
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    val writer = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def begin(): Unit                               = calls += "begin"
      override def writeRow(row: Map[String, CellValue]): Unit = calls += "row"
      override def end(): Unit                                 = calls += "end"
    }
    val result = StreamingOutput.runWithDeferredBegin(
      writer,
      _ => Right(0L)
    )
    result shouldBe Right(0L)
    calls.toList shouldBe List("begin", "end")
  }

  it should "propagate read error without calling begin or end" in {
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    val writer = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def begin(): Unit                               = calls += "begin"
      override def writeRow(row: Map[String, CellValue]): Unit = calls += "row"
      override def end(): Unit                                 = calls += "end"
    }
    val err = ParqueteerError.FileNotFound("/x")
    val result = StreamingOutput.runWithDeferredBegin(
      writer,
      _ => Left(err)
    )
    result shouldBe Left(err)
    calls should not contain "begin"
    calls should not contain "end"
  }

  it should "return Left when end() throws and read succeeded" in {
    val writer = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def writeRow(row: Map[String, CellValue]): Unit = ()
      override def end(): Unit = throw new java.io.IOException("flush failed")
    }
    val result = StreamingOutput.runWithDeferredBegin(
      writer,
      process => { process(Map.empty); Right(1L) }
    )
    result.isLeft shouldBe true
  }

  // ── runWithDeferredBeginMulti ────────────────────────────────────────────

  "runWithDeferredBeginMulti" should "share one begin/end lifecycle across multiple reads" in {
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    val writer = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def begin(): Unit                               = calls += "begin"
      override def writeRow(row: Map[String, CellValue]): Unit = calls += "row"
      override def end(): Unit                                 = calls += "end"
    }
    val reads: List[(Map[String, CellValue] => Unit) => Either[ParqueteerError, Long]] = List(
      process => { process(Map.empty); Right(1L) },
      process => { process(Map.empty); process(Map.empty); Right(2L) }
    )
    val result = StreamingOutput.runWithDeferredBeginMulti(writer, reads)
    result shouldBe Right(3L)
    // begin fires once, before the first row of the first read, not once per read
    calls.toList shouldBe List("begin", "row", "row", "row", "end")
  }

  it should "stop at the first failing read and never call end without begin" in {
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    val writer = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def begin(): Unit                               = calls += "begin"
      override def writeRow(row: Map[String, CellValue]): Unit = calls += "row"
      override def end(): Unit                                 = calls += "end"
    }
    val err               = ParqueteerError.FileNotFound("/first.parquet")
    var secondReadInvoked = false
    val reads: List[(Map[String, CellValue] => Unit) => Either[ParqueteerError, Long]] = List(
      _ => Left(err),
      process => { secondReadInvoked = true; process(Map.empty); Right(1L) }
    )
    val result = StreamingOutput.runWithDeferredBeginMulti(writer, reads)
    result shouldBe Left(err)
    secondReadInvoked shouldBe false
    // the first read failed before writing any row, so begin() never fired —
    // end() must not fire either, matching runWithDeferredBegin's single-read contract
    calls.toList shouldBe empty
  }

  it should "call begin and end even when every read succeeds with zero rows" in {
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    val writer = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def begin(): Unit                               = calls += "begin"
      override def writeRow(row: Map[String, CellValue]): Unit = calls += "row"
      override def end(): Unit                                 = calls += "end"
    }
    val reads: List[(Map[String, CellValue] => Unit) => Either[ParqueteerError, Long]] =
      List(_ => Right(0L), _ => Right(0L))
    val result = StreamingOutput.runWithDeferredBeginMulti(writer, reads)
    result shouldBe Right(0L)
    calls.toList shouldBe List("begin", "end")
  }

  it should "keep writer.writeRow calls in read order when parallelism > 1, even if a later read finishes first" in {
    val written = scala.collection.mutable.ArrayBuffer.empty[Int]
    val writer = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def begin(): Unit = ()
      override def writeRow(row: Map[String, CellValue]): Unit =
        written += row("id").asInstanceOf[CellValue.I64].l.toInt
      override def end(): Unit = ()
    }
    val delayMs = List(30L, 0L, 10L)
    val reads: List[(Map[String, CellValue] => Unit) => Either[ParqueteerError, Long]] =
      delayMs.zipWithIndex.map { case (delay, i) =>
        process => {
          if delay > 0 then Thread.sleep(delay)
          process(Map("id" -> CellValue.I64((i + 1).toLong)))
          Right(1L)
        }
      }
    val result = StreamingOutput.runWithDeferredBeginMulti(writer, reads, parallelism = 3)
    result shouldBe Right(3L)
    written.toList shouldBe List(1, 2, 3)
  }

  it should "still abort on the first failing read when parallelism > 1" in {
    val err = ParqueteerError.FileNotFound("/b.parquet")
    val reads: List[(Map[String, CellValue] => Unit) => Either[ParqueteerError, Long]] = List(
      process => { process(Map.empty); Right(1L) },
      _ => Left(err),
      process => { process(Map.empty); Right(1L) }
    )
    val writer = new io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter {
      override def begin(): Unit                               = ()
      override def writeRow(row: Map[String, CellValue]): Unit = ()
      override def end(): Unit                                 = ()
    }
    val result = StreamingOutput.runWithDeferredBeginMulti(writer, reads, parallelism = 3)
    result shouldBe Left(err)
  }
}
