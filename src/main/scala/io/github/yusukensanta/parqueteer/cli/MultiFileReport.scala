package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.util.{CredentialRedactor, DaemonThreadFactory}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import java.util.concurrent.Executors

/** Renders one report per matched file for the multi-file metadata commands. */
private[cli] object MultiFileReport {

  /**
   * Runs `renderOne` once per matched path. Table mode prints each file's
   * rendered text as its own `==> path <==` block; JSON mode re-parses each
   * file's already-rendered JSON text into one combined array (a file that
   * failed to read contributes an {file, error} element instead). Exit code
   * is 0 only if every file's renderOne result is (text, true).
   */
  private[cli] def runMultiFileReport(
      paths: List[String],
      format: OutputFormat,
      globalOptions: GlobalOptions
  )(renderOne: String => Either[ParqueteerError, (String, Boolean)]): Int = {
    val results = fetchBounded(paths, globalOptions.fileParallelism)(renderOne)

    if !globalOptions.quiet then {
      if format == OutputFormat.JSON then {
        val elements = results.map {
          case (path, Right((json, _))) =>
            val parsed =
              io.circe.parser.parse(json).getOrElse(io.circe.Json.fromString(json))
            io.circe.Json
              .obj(
                "path"   -> io.circe.Json.fromString(path),
                "status" -> io.circe.Json.fromString("ok")
              )
              .deepMerge(parsed)
          case (path, Left(error)) =>
            io.circe.Json.obj(
              "path"   -> io.circe.Json.fromString(path),
              "status" -> io.circe.Json.fromString("error"),
              "error"  -> io.circe.Json.fromString(CredentialRedactor.redact(error.userMessage))
            )
        }
        println(io.circe.Json.arr(elements*).spaces2)
      } else {
        results.zipWithIndex.foreach { case ((path, result), i) =>
          if i > 0 then println()
          println(s"==> $path <==")
          result match {
            case Right((text, _)) => println(text)
            case Left(error) =>
              System.err.println(s"Error: ${CredentialRedactor.redact(error.userMessage)}")
          }
        }
      }
    }
    val allOk = results.forall {
      case (_, Right((_, ok))) => ok
      case (_, Left(_))        => false
    }
    if allOk then 0 else 1
  }

  /**
   * Fetches each path's render result with at most `parallelism` files
   * in flight at once — bounds concurrent cloud connections and in-memory
   * results for large globs instead of opening every file at once. Falls
   * back to a plain sequential map when there's nothing to gain (a single
   * path, or parallelism disabled), avoiding pool setup overhead. Output
   * order always matches `paths`, regardless of completion order, and one
   * path throwing (rather than returning Left) doesn't take the others
   * down with it.
   */
  private[cli] def fetchBounded(
      paths: List[String],
      parallelism: Int
  )(
      renderOne: String => Either[ParqueteerError, (String, Boolean)]
  ): List[(String, Either[ParqueteerError, (String, Boolean)])] =
    if paths.size <= 1 || parallelism <= 1 then paths.map(p => p -> renderOne(p))
    else {
      val pool = Executors.newFixedThreadPool(
        parallelism.min(paths.size),
        new DaemonThreadFactory("parqueteer-file-fetch")
      )
      try {
        implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
        val futures = paths.map { p =>
          Future(p -> renderOne(p)).recover { case NonFatal(e) =>
            p -> Left(ParqueteerError.IOError(e))
          }
        }
        Await.result(Future.sequence(futures), Duration.Inf)
      } finally pool.shutdown()
    }
}
