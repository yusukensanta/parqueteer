package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.services.ParquetService
import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.formatters.{OutputFormatter, RowStreamWriter}
import CommandSupport.*
import StreamingOutput.*
import Terminal.*

/** Handlers for `read` (single file and multi-file/glob). */
private[cli] object ReadCommands {

  // Shared by executeRead/executeReadMulti's streaming branches.
  private[cli] def buildRowStreamWriter(
      format: OutputFormat,
      globalOptions: GlobalOptions
  ): RowStreamWriter = {
    val baseWriter =
      if globalOptions.quiet then
        new RowStreamWriter {
          override def writeRow(row: Map[String, CellValue]): Unit = ()
        }
      else RowStreamWriter(format, System.out)
    if globalOptions.verbose && !globalOptions.quiet then
      new ProgressRowStreamWriter(baseWriter, System.err)
    else baseWriter
  }

  // Shared by executeRead/executeReadMulti: a broken pipe/full disk surfaces
  // as a stdout write error rather than a Left from the read itself, so it
  // needs its own check ahead of the normal Either handling.
  private[cli] def exitCodeForStreamResult(
      result: Either[ParqueteerError, Long],
      globalOptions: GlobalOptions
  ): Int = {
    val stdoutError = System.out.checkError()
    result match {
      case _ if stdoutError =>
        System.err.println(
          "[parqueteer] error: output stream write error (disk full or broken pipe)"
        )
        1
      case Right(_)    => 0
      case Left(error) => reportError("Error", globalOptions)(error)
    }
  }

  private[cli] def executeRead(
      service: ParquetService,
      cmd: ReadCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.{filePath, maxRows, columns, filter, format, parallelism, streaming}
    val effectiveParallelism =
      if filter.isDefined && parallelism > 1 then {
        if !globalOptions.quiet then
          System.err.println(
            "[parqueteer] warning: --filter disables parallel mode; falling back to sequential read."
          )
        1
      } else parallelism

    val readConfig = ReadConfig(
      maxRows = maxRows,
      columns = columns,
      filter = filter,
      outputFormat = format,
      parallelism = effectiveParallelism
    )

    val effectiveStreaming = streaming || (format == OutputFormat.NDJSON)

    if effectiveStreaming && effectiveParallelism > 1 && !globalOptions.quiet then
      System.err.println(
        s"[parqueteer] warning: --parallelism $effectiveParallelism is ignored in streaming mode; streaming is always sequential."
      )

    if effectiveStreaming && format == OutputFormat.Pretty && !globalOptions.quiet
    then
      System.err.println(
        "[parqueteer] warning: --format pretty is not supported in streaming mode; falling back to ndjson."
      )

    if effectiveStreaming then {
      val writer = buildRowStreamWriter(format, globalOptions)
      val result =
        runWithDeferredBegin(writer, service.streamRead(filePath, readConfig))
      exitCodeForStreamResult(result, globalOptions)
    } else {
      service.readFile(filePath, readConfig) match {
        case Right(file) =>
          if !globalOptions.quiet then {
            val useColors = globalOptions.colorMode match {
              case ColorMode.Never  => false
              case ColorMode.Always => true
              case ColorMode.Auto   => isStdoutTTY
            }
            val formatter = OutputFormatter(format, useColors)
            val output = file.content match {
              case Some(content) =>
                formatter.formatContent(content, file.schema)
              case None => "No content available"
            }
            println(output)
          }
          0
        case Left(error) =>
          val filterHint =
            if filter.isDefined && error.userMessage.contains(
                "FilterPredicate"
              ) && error.userMessage.contains("BINARY")
            then
              Some(
                "Hint: BINARY/STRING columns require quoted-string comparisons. Use: column = \"value\""
              )
            else None
          reportError("Error", globalOptions, filterHint)(error)
      }
    }
  }

  private[cli] def executeReadMulti(
      service: ParquetService,
      paths: List[String],
      cmd: ReadCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.{maxRows, columns, filter, format, schemaMode}
    val readConfig = ReadConfig(
      maxRows = maxRows,
      columns = columns,
      filter = filter,
      outputFormat = format
    )
    if format == OutputFormat.Pretty && !globalOptions.quiet then
      System.err.println(
        "[parqueteer] warning: --format pretty is not supported in streaming mode; falling back to ndjson."
      )
    val writer = buildRowStreamWriter(format, globalOptions)
    val result =
      runWithDeferredBegin(writer, service.streamReadMulti(paths, readConfig, schemaMode))
    exitCodeForStreamResult(result, globalOptions)
  }
}
