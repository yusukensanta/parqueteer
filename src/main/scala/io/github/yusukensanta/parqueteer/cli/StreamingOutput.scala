package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter
import io.github.yusukensanta.parqueteer.core.util.{CredentialRedactor, FileExtension, RowPipeline}
import CommandSupport.*

/** Drives RowStreamWriters: deferred begin/end lifecycle and safe local text output files. */
private[cli] object StreamingOutput {

  // Refuses a cloud URI output, refuses to silently truncate a pre-existing
  // output file, then creates it and hands `doRead` a RowStreamWriter over
  // it. Deletes the just-created file if `doRead` — or the write itself —
  // fails. We already verified the file didn't pre-exist, so any file at
  // outputPath afterward was created by this run and is safe to remove on
  // failure. Shared by the single-file and multi-file parquet→text streaming
  // conversion paths, which differ only in how `doRead` drives the writer.
  private[cli] def withSafeTextOutput(
      outputPath: String,
      outFormat: OutputFormat
  )(
      doRead: RowStreamWriter => Either[ParqueteerError, Long]
  ): Either[ParqueteerError, Long] =
    if cloudUriPattern.findFirstIn(outputPath).isDefined then
      Left(
        ParqueteerError.UnsupportedOperation(
          outputPath,
          s"Cloud URI output is not supported for text conversion (parquet → ${FileExtension
              .of(outputPath)}). " +
            "Convert to a local file first, then upload separately."
        )
      )
    else
      checkOutputWritable(outputPath).flatMap { _ =>
        val outFilePath = java.nio.file.Paths.get(outputPath)
        if java.nio.file.Files.exists(outFilePath) then
          Left(ParqueteerError.OutputExists(outputPath))
        else
          scala.util
            .Try {
              import java.nio.file.Files
              Option(outFilePath.getParent).foreach(Files.createDirectories(_))
              Files.createFile(outFilePath)
              (
                outFilePath,
                new java.io.PrintStream(
                  new java.io.BufferedOutputStream(Files.newOutputStream(outFilePath), 1 << 16)
                )
              )
            }
            .toEither
            .left
            .map(ParqueteerError.IOError.apply)
            .flatMap { case (outFile, ps) =>
              val writer = RowStreamWriter(outFormat, ps)
              var failed = true
              try {
                val result     = doRead(writer)
                val writeError = ps.checkError()
                failed = result.isLeft || writeError
                if writeError && result.isRight then
                  Left(
                    ParqueteerError.IOError(
                      new java.io.IOException(
                        "Output stream write error (disk full or broken pipe)"
                      )
                    )
                  )
                else result
              } finally {
                ps.close()
                if failed then scala.util.Try(java.nio.file.Files.deleteIfExists(outFile))
              }
            }
      }

  private[cli] def runWithDeferredBegin(
      writer: RowStreamWriter,
      read: (Map[String, CellValue] => Unit) => Either[ParqueteerError, Long]
  ): Either[ParqueteerError, Long] =
    runWithDeferredBeginMulti(writer, List(read))

  /**
   * Runs each `read` in `reads`, sharing one begin/end lifecycle across all
   * of them: `writer.begin()` fires at most once, on the first row of the
   * first read that produces one (or once at the end if every read
   * succeeds with zero rows); `writer.end()` fires only if `begin()` ever
   * fired. Aborts on the first read that returns `Left`. Generalizes the
   * single-read case (`runWithDeferredBegin`) to N reads — used by
   * multi-file streaming conversions where one output writer spans several
   * input files. `writer.begin()`/`writeRow` only ever run on the calling
   * thread, in `reads` order, even when `parallelism > 1` fetches several
   * reads concurrently ahead of the writer (see RowPipeline).
   */
  private[cli] def runWithDeferredBeginMulti(
      writer: RowStreamWriter,
      reads: List[(Map[String, CellValue] => Unit) => Either[ParqueteerError, Long]],
      parallelism: Int = 1
  ): Either[ParqueteerError, Long] = {
    var started = false
    // Each `read` thunk is itself the pipeline "item" — up to `parallelism`
    // of them run concurrently ahead of the writer (see RowPipeline), but
    // writer.begin()/writeRow still only ever run on this thread, in order.
    val pipelineResult = RowPipeline.run(reads, parallelism)((read, sink) => read(sink)) { row =>
      if !started then { writer.begin(); started = true }
      writer.writeRow(row)
    }
    if !started && pipelineResult.isRight then { writer.begin(); started = true }
    val endFailure: Option[Throwable] =
      if started then scala.util.Try(writer.end()).failed.toOption else None
    (pipelineResult, endFailure) match {
      case (Right(_), Some(ex)) => Left(ParqueteerError.IOError(ex))
      case (Left(err), Some(ex)) =>
        System.err.println(
          s"[parqueteer] warning: error flushing output: ${CredentialRedactor
              .redact(Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))}"
        )
        Left(err)
      case (Left(err), None)    => Left(err)
      case (Right(total), None) => Right(total)
    }
  }
}

private[cli] class ProgressRowStreamWriter(
    delegate: RowStreamWriter,
    err: java.io.PrintStream,
    intervalRows: Long = 10000
) extends RowStreamWriter {
  private var count: Long = 0L

  override def begin(): Unit = delegate.begin()

  override def writeRow(row: Map[String, CellValue]): Unit = {
    delegate.writeRow(row)
    count += 1
    if count % intervalRows == 0 then err.print(s"\r[parqueteer] streamed $count rows...")
  }

  override def end(): Unit = {
    if count >= intervalRows then err.println(s"\r[parqueteer] streamed $count rows — done")
    delegate.end()
  }
}
