package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import org.scalatest.flatspec.AnyFlatSpec
import java.io.{ByteArrayOutputStream, PrintStream}

class MultiFileReportTest extends CliTestSupport {

  // ── runMultiFileReport ──────────────────────────────────────────────────

  "runMultiFileReport" should "print one table block per file and return 0 when all succeed" in {
    val out = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      val code = MultiFileReport.runMultiFileReport(
        List("/a.parquet", "/b.parquet"),
        OutputFormat.Table,
        GlobalOptions()
      )(path => Right((s"text for $path", true)))
      code shouldBe 0
    }
    val printed = out.toString
    printed should include("==> /a.parquet <==")
    printed should include("text for /a.parquet")
    printed should include("==> /b.parquet <==")
    printed should include("text for /b.parquet")
  }

  it should "return 1 and skip nothing when one file errors" in {
    val code = MultiFileReport.runMultiFileReport(
      List("/a.parquet", "/b.parquet"),
      OutputFormat.Table,
      GlobalOptions()
    ) {
      case "/a.parquet" => Right(("ok", true))
      case path         => Left(ParqueteerError.FileNotFound(path))
    }
    code shouldBe 1
  }

  it should "return 1 when a file renders successfully but is marked unsuccessful" in {
    val code = MultiFileReport.runMultiFileReport(
      List("/a.parquet"),
      OutputFormat.Table,
      GlobalOptions()
    )(_ => Right(("has issues", false)))
    code shouldBe 1
  }

  it should "wrap per-file JSON text into a single JSON array in JSON mode" in {
    val out = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      MultiFileReport.runMultiFileReport(
        List("/a.parquet", "/b.parquet"),
        OutputFormat.JSON,
        GlobalOptions()
      )(_ => Right((s"""{"count":42}""", true)))
    }
    val parsed = io.circe.parser.parse(out.toString).getOrElse(fail("not valid JSON"))
    parsed.asArray.map(_.size) shouldBe Some(2)
  }

  it should "tag each successful JSON element with its own path and an ok status" in {
    val out = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      MultiFileReport.runMultiFileReport(
        List("/a.parquet", "/b.parquet"),
        OutputFormat.JSON,
        GlobalOptions()
      )(path => Right((s"""{"count":${path.length}}""", true)))
    }
    val elements = io.circe.parser
      .parse(out.toString)
      .getOrElse(fail("not valid JSON"))
      .asArray
      .getOrElse(fail("not a JSON array"))
    elements.map(_.hcursor.get[String]("path")) shouldBe Vector(
      Right("/a.parquet"),
      Right("/b.parquet")
    )
    elements.map(_.hcursor.get[String]("status")) shouldBe Vector(Right("ok"), Right("ok"))
    // Distinguishable by path, not merely structurally identical objects.
    elements(0) should not be elements(1)
    elements(0).hcursor.get[Int]("count") shouldBe Right("/a.parquet".length)
    elements(1).hcursor.get[Int]("count") shouldBe Right("/b.parquet".length)
  }

  it should "tag each errored JSON element with its own path and an error status" in {
    val out = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      MultiFileReport.runMultiFileReport(
        List("/a.parquet", "/b.parquet"),
        OutputFormat.JSON,
        GlobalOptions()
      ) {
        case "/a.parquet" => Right((s"""{"count":1}""", true))
        case path         => Left(ParqueteerError.FileNotFound(path))
      }
    }
    val elements = io.circe.parser
      .parse(out.toString)
      .getOrElse(fail("not valid JSON"))
      .asArray
      .getOrElse(fail("not a JSON array"))
    elements.map(_.hcursor.get[String]("path")) shouldBe Vector(
      Right("/a.parquet"),
      Right("/b.parquet")
    )
    elements(0).hcursor.get[String]("status") shouldBe Right("ok")
    elements(1).hcursor.get[String]("status") shouldBe Right("error")
    elements(1).hcursor.get[String]("error").toOption shouldBe defined
  }

  it should "redact credential material from a per-file error's JSON message" in {
    val out = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      MultiFileReport.runMultiFileReport(
        List("/secret.parquet"),
        OutputFormat.JSON,
        GlobalOptions()
      ) { _ =>
        Left(
          ParqueteerError.FileNotFound(
            "/secret.parquet?X-Amz-Signature=SUPERSECRET123"
          )
        )
      }
    }
    val printed = out.toString
    printed should not include "SUPERSECRET123"
    printed should include("[REDACTED]")
  }

  it should "never run more than file-parallelism renders concurrently" in {
    val inFlight    = new java.util.concurrent.atomic.AtomicInteger(0)
    val maxObserved = new java.util.concurrent.atomic.AtomicInteger(0)
    val paths       = (1 to 12).map(i => s"/f$i.parquet").toList
    val code = MultiFileReport.runMultiFileReport(
      paths,
      OutputFormat.Table,
      GlobalOptions(quiet = true, fileParallelism = 3)
    ) { path =>
      val current = inFlight.incrementAndGet()
      maxObserved.updateAndGet(prev => prev.max(current))
      Thread.sleep(20)
      inFlight.decrementAndGet()
      Right((path, true))
    }
    code shouldBe 0
    maxObserved.get should be <= 3
  }

  it should "preserve input order in output even when later files finish first" in {
    val out = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      MultiFileReport.runMultiFileReport(
        List("/slow.parquet", "/fast.parquet"),
        OutputFormat.Table,
        GlobalOptions(fileParallelism = 4)
      ) {
        case "/slow.parquet" =>
          Thread.sleep(50)
          Right(("slow result", true))
        case _ =>
          Right(("fast result", true))
      }
    }
    val printed = out.toString
    // The block for /slow.parquet must still appear before /fast.parquet's,
    // regardless of which one's renderOne call actually finished first.
    printed.indexOf("==> /slow.parquet <==") should be < printed.indexOf("==> /fast.parquet <==")
  }

  it should "capture a thrown exception from one file without losing the others" in {
    val code = MultiFileReport.runMultiFileReport(
      List("/a.parquet", "/boom.parquet", "/c.parquet"),
      OutputFormat.Table,
      GlobalOptions(quiet = true, fileParallelism = 4)
    ) {
      case "/boom.parquet" => throw new RuntimeException("simulated network failure")
      case path            => Right((path, true))
    }
    code shouldBe 1
  }

  it should "fall back to sequential execution when file-parallelism is 1" in {
    val order = scala.collection.mutable.ListBuffer.empty[String]
    MultiFileReport.runMultiFileReport(
      List("/a.parquet", "/b.parquet", "/c.parquet"),
      OutputFormat.Table,
      GlobalOptions(quiet = true, fileParallelism = 1)
    ) { path =>
      order += path
      Right((path, true))
    }
    order.toList shouldBe List("/a.parquet", "/b.parquet", "/c.parquet")
  }
}
