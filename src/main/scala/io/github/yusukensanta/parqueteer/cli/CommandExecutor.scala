package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.services.ParquetService
import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  ColorMode,
  CompressionType,
  ConversionConfig,
  FileStats,
  GlobalOptions,
  OutputFormat,
  ParquetFile,
  ParqueteerError,
  ReadConfig,
  SchemaMode,
  WriteConfig
}
import io.github.yusukensanta.parqueteer.core.formatters.{
  OutputFormatter,
  RowStreamWriter,
  TableFormatter
}
import io.github.yusukensanta.parqueteer.core.util.{FileExtension, RowPipeline}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

private[cli] object CommandExecutor {

  private val cloudUriPattern = "^(s3a?|gs|abfss?|wasbs?)://".r

  def execute(
      command: Command,
      service: ParquetService,
      globalOptions: GlobalOptions
  ): Int =
    command match {
      case ReadCommand(
            filePath,
            maxRows,
            columns,
            filter,
            format,
            parallelism,
            streaming,
            schemaMode
          ) =>
        service.resolveGlob(filePath) match {
          case Left(error) => reportError("Error", globalOptions)(error)
          case Right(paths) if paths.size == 1 =>
            warnIfSchemaModeNoop(schemaMode, globalOptions)
            executeRead(
              service,
              paths.head,
              maxRows,
              columns,
              filter,
              format,
              parallelism,
              streaming,
              globalOptions
            )
          case Right(paths) =>
            executeReadMulti(
              service,
              paths,
              maxRows,
              columns,
              filter,
              format,
              schemaMode,
              globalOptions
            )
        }

      case InfoCommand(filePath, format, verbose) =>
        service.resolveGlob(filePath) match {
          case Left(error) => reportError("Failed to get file info", globalOptions)(error)
          case Right(paths) if paths.size == 1 =>
            executeInfo(service, paths.head, format, verbose, globalOptions)
          case Right(paths) =>
            executeInfoMulti(service, paths, format, verbose, globalOptions)
        }

      case WriteCommand(
            inputPath,
            outputPath,
            inputFormat,
            compression,
            rowGroupSize,
            dryRun,
            schemaMode
          ) =>
        service.resolveGlob(inputPath) match {
          case Left(error) => reportError("Failed to write file", globalOptions)(error)
          case Right(paths) if paths.size == 1 =>
            warnIfSchemaModeNoop(schemaMode, globalOptions)
            executeWrite(
              service,
              outputPath,
              paths.head,
              inputFormat,
              compression,
              rowGroupSize,
              dryRun,
              globalOptions
            )
          case Right(paths) =>
            executeWriteMulti(
              service,
              outputPath,
              paths,
              inputFormat,
              compression,
              rowGroupSize,
              schemaMode,
              dryRun,
              globalOptions
            )
        }

      case ValidateCommand(filePath, verbose, deep) =>
        service.resolveGlob(filePath) match {
          case Left(error) => reportError("Failed to validate file", globalOptions)(error)
          case Right(paths) if paths.size == 1 =>
            executeValidate(service, paths.head, verbose, deep, globalOptions)
          case Right(paths) =>
            executeValidateMulti(service, paths, verbose, deep, globalOptions)
        }

      case ConvertCommand(
            inputPath,
            outputPath,
            compression,
            maxRows,
            dryRun,
            schemaMode
          ) =>
        service.resolveGlob(inputPath) match {
          case Left(error) => reportError("Failed to convert file", globalOptions)(error)
          case Right(paths) if paths.size == 1 =>
            warnIfSchemaModeNoop(schemaMode, globalOptions)
            executeConvert(
              service,
              paths.head,
              outputPath,
              compression,
              maxRows,
              dryRun,
              globalOptions
            )
          case Right(paths) =>
            executeConvertMulti(
              service,
              paths,
              outputPath,
              compression,
              maxRows,
              schemaMode,
              dryRun,
              globalOptions
            )
        }

      case cmd: ConfigCommand =>
        executeConfig(cmd, globalOptions)

      case cmd: SchemaCommand =>
        service.resolveGlob(cmd.filePath) match {
          case Left(error) => reportError("Failed to read schema", globalOptions)(error)
          case Right(paths) if paths.size == 1 =>
            executeSchemaInfo(service, cmd.copy(filePath = paths.head), globalOptions)
          case Right(paths) =>
            executeSchemaInfoMulti(service, paths, cmd.format, globalOptions)
        }

      case cmd: SchemaDiffCommand =>
        executeSchemaDiff(service, cmd, globalOptions)

      case StatsCommand(filePath, format) =>
        service.resolveGlob(filePath) match {
          case Left(error) => reportError("Failed to get stats", globalOptions)(error)
          case Right(paths) if paths.size == 1 =>
            executeStats(service, paths.head, format, globalOptions)
          case Right(paths) =>
            executeStatsMulti(service, paths, format, globalOptions)
        }

      case CountCommand(filePath, format) =>
        service.resolveGlob(filePath) match {
          case Left(error) => reportError("Failed to count rows", globalOptions)(error)
          case Right(paths) if paths.size == 1 =>
            executeCount(service, paths.head, format, globalOptions)
          case Right(paths) =>
            executeCountMulti(service, paths, format, globalOptions)
        }

      case MergeCommand(inputPaths, outputPath, compression, schemaMode, dryRun) =>
        resolveAllGlobs(service, inputPaths) match {
          case Left(error) => reportError("Failed to merge", globalOptions)(error)
          case Right(expandedPaths) =>
            executeMerge(
              service,
              expandedPaths,
              outputPath,
              compression,
              schemaMode,
              dryRun,
              globalOptions
            )
        }

      case CompletionsCommand(shell) =>
        executeCompletions(shell, globalOptions)
    }

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
      filePath: String,
      maxRows: Option[Long],
      columns: Option[List[String]],
      filter: Option[String],
      format: OutputFormat,
      parallelism: Int,
      streaming: Boolean,
      globalOptions: GlobalOptions
  ): Int = {
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
      maxRows: Option[Long],
      columns: Option[List[String]],
      filter: Option[String],
      format: OutputFormat,
      schemaMode: SchemaMode,
      globalOptions: GlobalOptions
  ): Int = {
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

  // Shared by executeInfo/executeInfoMulti so the two rendering paths can't
  // silently drift apart (JSON vs table branch, metadata/schema/row-group
  // sections) the way they used to when each had its own copy.
  private[cli] def formatInfoText(
      file: ParquetFile,
      format: OutputFormat,
      verbose: Boolean
  ): String = format match {
    case OutputFormat.JSON => CliOutputFormatter.formatInfoJson(file, verbose)
    case _ =>
      val metaOut = file.metadata match {
        case Some(metadata) => new TableFormatter().formatMetadata(metadata)
        case None           => "No metadata information available"
      }
      val schemaOut = file.schema.fold("") { s =>
        s"\nRows:        ${s.totalRowCount}\n" +
          s"Row Groups:  ${s.rowGroupCount}\n" +
          s"Columns:     ${s.columns.size}"
      }
      val verboseOut =
        if verbose && file.rowGroups.nonEmpty then
          "\n\n" + CliOutputFormatter.formatRowGroupsTable(file.rowGroups)
        else ""
      metaOut + schemaOut + verboseOut
  }

  private[cli] def executeInfo(
      service: ParquetService,
      filePath: String,
      format: OutputFormat,
      verbose: Boolean,
      globalOptions: GlobalOptions
  ): Int =
    service.getFileInfo(filePath) match {
      case Right(file) =>
        if !globalOptions.quiet then println(formatInfoText(file, format, verbose))
        0
      case Left(error) =>
        reportError("Failed to get file info", globalOptions)(error)
    }

  private[cli] def executeInfoMulti(
      service: ParquetService,
      paths: List[String],
      format: OutputFormat,
      verbose: Boolean,
      globalOptions: GlobalOptions
  ): Int =
    runMultiFileReport(paths, format, globalOptions) { path =>
      service.getFileInfo(path).map(file => (formatInfoText(file, format, verbose), true))
    }

  private[cli] def executeWrite(
      service: ParquetService,
      outputPath: String,
      inputPath: String,
      inputFormat: InputFormat,
      compression: CompressionType,
      rowGroupSize: Option[Long],
      dryRun: Boolean,
      globalOptions: GlobalOptions
  ): Int = {
    val writeConfig = WriteConfig(
      compressionType = compression,
      rowGroupSize = rowGroupSize.getOrElse(WriteConfig.DefaultRowGroupSize)
    )
    val formatStr = InputFormat.toServiceString(inputFormat)
    checkOutputWritable(outputPath) match {
      case Left(err) =>
        reportError("Failed to write file", globalOptions)(err)
      case Right(_) =>
        if dryRun then {
          service.readDataFile(inputPath, formatStr, maxRows = Some(1L)) match {
            case Left(error) =>
              reportError("Failed to read input file", globalOptions)(error)
            case Right(rows) =>
              val columns = rows.headOption.map(_.keys.toList).getOrElse(Nil)
              println(s"Dry run: would write $outputPath")
              println(s"  Input:       $inputPath ($formatStr)")
              println(s"  Columns:     ${columns.mkString(", ")}")
              println(s"  Compression: ${compression.toString.toLowerCase}")
              0
          }
        } else {
          service.streamWriteDataFile(inputPath, formatStr, outputPath, writeConfig) match {
            case Right(_) =>
              if !globalOptions.quiet then println(s"Successfully wrote data to $outputPath")
              0
            case Left(error) =>
              reportError("Failed to write file", globalOptions)(error)
          }
        }
    }
  }

  private[cli] def executeWriteMulti(
      service: ParquetService,
      outputPath: String,
      inputPaths: List[String],
      inputFormat: InputFormat,
      compression: CompressionType,
      rowGroupSize: Option[Long],
      schemaMode: SchemaMode,
      dryRun: Boolean,
      globalOptions: GlobalOptions
  ): Int = {
    val writeConfig = WriteConfig(
      compressionType = compression,
      rowGroupSize = rowGroupSize.getOrElse(WriteConfig.DefaultRowGroupSize)
    )
    val formatStr = InputFormat.toServiceString(inputFormat)
    checkOutputWritable(outputPath) match {
      case Left(err) => reportError("Failed to write file", globalOptions)(err)
      case Right(_) =>
        if dryRun then {
          println(s"Dry run: would write $outputPath")
          println(s"  Inputs:      ${inputPaths.size} files matched")
          inputPaths.foreach(p => println(s"    - $p"))
          println(s"  Schema mode: $schemaMode")
          println(s"  Compression: ${compression.toString.toLowerCase}")
          0
        } else {
          val onProgress: (Int, Int, String) => Unit = (i, n, path) =>
            if !globalOptions.quiet then System.err.println(s"[$i/$n] Writing: $path")
          service.writeMultiRawToParquet(
            inputPaths,
            formatStr,
            outputPath,
            writeConfig,
            schemaMode,
            onProgress
          ) match {
            case Right(count) =>
              if !globalOptions.quiet then
                println(s"Successfully wrote ${inputPaths.size} files ($count rows) → $outputPath")
              0
            case Left(error) => reportError("Failed to write file", globalOptions)(error)
          }
        }
    }
  }

  // Shared by executeValidate/executeValidateMulti's --verbose schema
  // summary. Returns "" when there's no schema to show (missing file info,
  // or the file legitimately has none) so callers can decide how to attach
  // it (println vs string concatenation) without duplicating the lookup.
  private[cli] def formatValidateVerboseSchema(service: ParquetService, path: String): String =
    service.getFileInfo(path) match {
      case Right(file) =>
        file.schema.fold("") { s =>
          s"  Columns:    ${s.columns.size}\n" +
            s"  Row groups: ${s.rowGroupCount}\n" +
            s"  Total rows: ${s.totalRowCount}"
        }
      case Left(_) => ""
    }

  private[cli] def executeValidate(
      service: ParquetService,
      filePath: String,
      verbose: Boolean,
      deep: Boolean,
      globalOptions: GlobalOptions
  ): Int =
    service.validateFile(filePath, deep) match {
      case Right(result) =>
        if result.isValid then {
          if !globalOptions.quiet then println(s"✓ File $filePath is valid")
          if verbose then {
            val summary = formatValidateVerboseSchema(service, filePath)
            if summary.nonEmpty then println(summary)
          }
          0
        } else {
          println(s"✗ File $filePath has issues:")
          result.issues.foreach(issue => println(s"  - $issue"))
          1
        }
      case Left(error) =>
        reportError("Failed to validate file", globalOptions)(error)
    }

  private[cli] def executeValidateMulti(
      service: ParquetService,
      paths: List[String],
      verbose: Boolean,
      deep: Boolean,
      globalOptions: GlobalOptions
  ): Int =
    runMultiFileReport(paths, OutputFormat.Table, globalOptions) { path =>
      service.validateFile(path, deep).map { result =>
        val text =
          if result.isValid then {
            val summary    = if verbose then formatValidateVerboseSchema(service, path) else ""
            val verboseOut = if summary.nonEmpty then "\n" + summary else ""
            s"✓ File $path is valid" + verboseOut
          } else (s"✗ File $path has issues:" :: result.issues.map(i => s"  - $i")).mkString("\n")
        (text, result.isValid)
      }
    }

  private[cli] def executeConvert(
      service: ParquetService,
      inputPath: String,
      outputPath: String,
      compression: CompressionType,
      maxRows: Option[Long],
      dryRun: Boolean,
      globalOptions: GlobalOptions
  ): Int = {
    val conversionConfig = ConversionConfig(
      writeConfig = WriteConfig(compressionType = compression),
      maxRows = maxRows
    )

    if dryRun then
      runConvertDryRun(
        service,
        inputPath,
        outputPath,
        compression,
        globalOptions
      )
    else
      performConvert(service, inputPath, outputPath, conversionConfig) match {
        case Right(_) =>
          if !globalOptions.quiet then println(s"Successfully converted $inputPath to $outputPath")
          0
        case Left(error) =>
          reportError("Failed to convert file", globalOptions)(error)
      }
  }

  private def runConvertDryRun(
      service: ParquetService,
      inputPath: String,
      outputPath: String,
      compression: CompressionType,
      globalOptions: GlobalOptions
  ): Int = {
    val inputExt = FileExtension.of(inputPath)
    if inputExt == "parquet" then
      service.getFileInfo(inputPath) match {
        case Left(error) =>
          reportError("Failed to read input", globalOptions)(error)
        case Right(file) =>
          println(s"Dry run: would convert $inputPath → $outputPath")
          println(s"  Input:       $inputPath")
          file.metadata.foreach(m =>
            println(
              s"  File size:   ${CliOutputFormatter.formatBytesForDisplay(m.fileSize)}"
            )
          )
          file.schema.foreach { s =>
            println(s"  Rows:        ${s.totalRowCount}")
            println(s"  Columns:     ${s.columns.size}")
            s.columns.headOption.foreach(c =>
              println(
                s"  Compression: ${c.compressionType.toLowerCase} → ${compression.toString.toLowerCase}"
              )
            )
          }
          0
      }
    else {
      println(s"Dry run: would convert $inputPath → $outputPath")
      println(s"  Input format: $inputExt")
      println(
        s"  Compression:  ${compression.toString.toLowerCase} (output)"
      )
      0
    }
  }

  private[cli] def performConvert(
      service: ParquetService,
      inputPath: String,
      outputPath: String,
      conversionConfig: ConversionConfig
  ): Either[ParqueteerError, Unit] = {
    val inputExt  = FileExtension.of(inputPath)
    val outputExt = FileExtension.of(outputPath)
    (inputExt, outputExt) match {
      case ("parquet", ext @ ("json" | "ndjson" | "csv")) =>
        val outFormat = ext match {
          case "json"   => OutputFormat.JSON
          case "ndjson" => OutputFormat.NDJSON
          case _        => OutputFormat.CSV
        }
        convertParquetStreamed(
          service,
          inputPath,
          outputPath,
          outFormat,
          conversionConfig
        )
      case ("parquet", "parquet") =>
        service
          .convertParquetFile(inputPath, outputPath, conversionConfig)
          .map(_ => ())
      case (ext @ ("json" | "ndjson" | "csv" | "ltsv"), "parquet") =>
        service
          .streamWriteDataFile(
            inputPath,
            ext,
            outputPath,
            conversionConfig.writeConfig,
            conversionConfig.maxRows
          )
          .map(_ => ())
      case _ =>
        Left(
          ParqueteerError.InvalidFormat(
            inputPath,
            s"Unsupported conversion: $inputExt → $outputExt. Supported: parquet→parquet, parquet→json, parquet→ndjson, parquet→csv, json→parquet, ndjson→parquet, csv→parquet, ltsv→parquet"
          )
        )
    }
  }

  private def convertParquetStreamed(
      service: ParquetService,
      inputPath: String,
      outputPath: String,
      outFormat: OutputFormat,
      conversionConfig: ConversionConfig
  ): Either[ParqueteerError, Unit] =
    if cloudUriPattern.findFirstIn(outputPath).isDefined then
      Left(
        ParqueteerError.InvalidFormat(
          outputPath,
          s"Cloud URI output is not supported for text conversion (parquet → ${FileExtension
              .of(outputPath)}). " +
            "Convert to a local file first, then upload separately."
        )
      )
    else
      checkOutputWritable(outputPath).flatMap { _ =>
        // Mirror the parquet-to-parquet path's overwrite guard: refuse to silently
        // truncate an existing output file instead of only checking the parent
        // directory is writable.
        val outFilePath = java.nio.file.Paths.get(outputPath)
        if java.nio.file.Files.exists(outFilePath) then
          Left(
            ParqueteerError.InvalidFormat(
              outputPath,
              s"Output file already exists: $outputPath. Remove it first or choose a different output path."
            )
          )
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
                val result = runWithDeferredBegin(
                  writer,
                  service.streamRead(
                    inputPath,
                    ReadConfig(maxRows = conversionConfig.maxRows)
                  )
                )
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
                else result.map(_ => ())
              } finally {
                ps.close()
                // We already verified the file didn't pre-exist, so any file at
                // outputPath now was created by this run and is safe to remove
                // on failure.
                if failed then scala.util.Try(java.nio.file.Files.deleteIfExists(outFile))
              }
            }
      }

  private[cli] def executeConvertMulti(
      service: ParquetService,
      inputPaths: List[String],
      outputPath: String,
      compression: CompressionType,
      maxRows: Option[Long],
      schemaMode: SchemaMode,
      dryRun: Boolean,
      globalOptions: GlobalOptions
  ): Int = {
    val inputExt      = FileExtension.of(inputPaths.head)
    val outputExt     = FileExtension.of(outputPath)
    val mismatchedExt = inputPaths.find(p => FileExtension.of(p) != inputExt)
    mismatchedExt match {
      case Some(badPath) =>
        reportError("Failed to convert file", globalOptions)(
          ParqueteerError.InvalidFormat(
            badPath,
            s"Mixed input formats in matched files: expected '$inputExt' (from ${inputPaths.head}), " +
              s"found '${FileExtension.of(badPath)}' in $badPath. All matched files must share the same format."
          )
        )
      case None =>
        if dryRun then {
          println(s"Dry run: would convert ${inputPaths.size} files → $outputPath")
          println(s"  Input format: $inputExt")
          println(s"  Schema mode:  $schemaMode")
          println(s"  Compression:  ${compression.toString.toLowerCase} (output)")
          0
        } else {
          val writeConfig = WriteConfig(compressionType = compression)
          val result: Either[ParqueteerError, Long] = (inputExt, outputExt) match {
            case ("parquet", "parquet") =>
              val onProgress: (Int, Int, String) => Unit = (i, n, path) =>
                if !globalOptions.quiet then System.err.println(s"[$i/$n] Converting: $path")
              service.mergeFiles(
                inputPaths,
                outputPath,
                writeConfig,
                schemaMode,
                onProgress,
                globalOptions.fileParallelism
              )
            case ("parquet", ext @ ("json" | "ndjson" | "csv")) =>
              val outFormat = ext match {
                case "json"   => OutputFormat.JSON
                case "ndjson" => OutputFormat.NDJSON
                case _        => OutputFormat.CSV
              }
              convertParquetMultiStreamed(
                service,
                inputPaths,
                outputPath,
                outFormat,
                schemaMode,
                maxRows,
                globalOptions.fileParallelism
              )
            case (ext @ ("json" | "ndjson" | "csv" | "ltsv"), "parquet") =>
              service.writeMultiRawToParquet(
                inputPaths,
                ext,
                outputPath,
                writeConfig,
                schemaMode,
                fileParallelism = globalOptions.fileParallelism
              )
            case _ =>
              Left(
                ParqueteerError.InvalidFormat(
                  inputPaths.head,
                  s"Unsupported conversion: $inputExt → $outputExt for multiple matched files."
                )
              )
          }
          result match {
            case Right(count) =>
              if !globalOptions.quiet then
                println(
                  s"Successfully converted ${inputPaths.size} files ($count rows) → $outputPath"
                )
              0
            case Left(error) => reportError("Failed to convert file", globalOptions)(error)
          }
        }
    }
  }

  private def convertParquetMultiStreamed(
      service: ParquetService,
      inputPaths: List[String],
      outputPath: String,
      outFormat: OutputFormat,
      schemaMode: SchemaMode,
      maxRows: Option[Long],
      fileParallelism: Int
  ): Either[ParqueteerError, Long] =
    if cloudUriPattern.findFirstIn(outputPath).isDefined then
      Left(
        ParqueteerError.InvalidFormat(
          outputPath,
          s"Cloud URI output is not supported for text conversion (parquet → ${FileExtension.of(outputPath)})."
        )
      )
    else
      service.checkSchemaCompatibility(inputPaths, schemaMode).flatMap { _ =>
        checkOutputWritable(outputPath).flatMap { _ =>
          val outFilePath = java.nio.file.Paths.get(outputPath)
          if java.nio.file.Files.exists(outFilePath) then
            Left(
              ParqueteerError.InvalidFormat(
                outputPath,
                s"Output file already exists: $outputPath. Remove it first or choose a different output path."
              )
            )
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
                  // Each closure shares `remaining` by reference, so the row
                  // budget decrements across files as runWithDeferredBeginMulti
                  // invokes them in order — the same running --limit semantics
                  // ParquetService.streamAllFiles uses for multi-file `read`.
                  var remaining = maxRows
                  val reads
                      : List[(Map[String, CellValue] => Unit) => Either[ParqueteerError, Long]] =
                    inputPaths.map { path => process =>
                      if remaining.contains(0L) then Right(0L)
                      else
                        service.streamRead(path, ReadConfig(maxRows = remaining))(process).map {
                          n =>
                            remaining = remaining.map(r => r - n)
                            n
                        }
                    }
                  // A running --limit budget is shared (by mutable closure)
                  // across `reads` in file order, so honoring it correctly
                  // requires each file to finish before the next starts —
                  // only prefetch ahead when there's no limit to honor.
                  val effectiveParallelism = if maxRows.isDefined then 1 else fileParallelism
                  val result     = runWithDeferredBeginMulti(writer, reads, effectiveParallelism)
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
      }

  private[cli] def executeMerge(
      service: ParquetService,
      inputPaths: List[String],
      outputPath: String,
      compression: CompressionType,
      schemaMode: SchemaMode,
      dryRun: Boolean,
      globalOptions: GlobalOptions
  ): Int = {
    val writeConfig = WriteConfig(compressionType = compression)
    checkOutputWritable(outputPath) match {
      case Left(err) =>
        reportError("Failed to merge", globalOptions)(err)
      case Right(_) =>
        if dryRun then
          executeMergeDryRun(
            service,
            inputPaths,
            outputPath,
            compression,
            schemaMode,
            globalOptions
          )
        else {
          val onProgress: (Int, Int, String) => Unit = (i, n, path) =>
            if !globalOptions.quiet then System.err.println(s"[$i/$n] Merging: $path")

          service.mergeFiles(
            inputPaths,
            outputPath,
            writeConfig,
            schemaMode,
            onProgress,
            globalOptions.fileParallelism
          ) match {
            case Right(count) =>
              if !globalOptions.quiet then
                println(s"Merged ${inputPaths.size} files ($count rows) → $outputPath")
              0
            case Left(error) =>
              reportError("Failed to merge", globalOptions)(error)
          }
        }
    }
  }

  private def executeMergeDryRun(
      service: ParquetService,
      inputPaths: List[String],
      outputPath: String,
      compression: CompressionType,
      schemaMode: SchemaMode,
      globalOptions: GlobalOptions
  ): Int = {
    println(s"Dry run: would merge ${inputPaths.size} files → $outputPath")
    println(s"  Schema mode:  $schemaMode")
    println(s"  Compression:  ${compression.toString.toLowerCase}")
    inputPaths.zipWithIndex.foreach { (path, i) =>
      service.getFileInfo(path) match {
        case Right(file) =>
          val rows = file.schema.map(_.totalRowCount).getOrElse(0L)
          val cols = file.schema.map(_.columns.size).getOrElse(0)
          println(s"  [${i + 1}/${inputPaths.size}] $path ($rows rows, $cols columns)")
        case Left(error) =>
          println(s"  [${i + 1}/${inputPaths.size}] $path — error: ${error.userMessage}")
      }
    }
    0
  }

  private[cli] def formatSchemaText(file: ParquetFile, format: OutputFormat): String =
    format match {
      case OutputFormat.JSON => CliOutputFormatter.formatSchemaJson(file)
      case _ =>
        file.schema match {
          case Some(schema) => new TableFormatter().formatSchema(schema)
          case None         => "No schema information available"
        }
    }

  private[cli] def executeSchemaInfo(
      service: ParquetService,
      cmd: SchemaCommand,
      globalOptions: GlobalOptions
  ): Int =
    if cmd.filePath.isEmpty then {
      System.err.println(
        "[parqueteer] error: schema requires a file path, or use 'schema diff FILE1 FILE2'"
      )
      2
    } else
      service.getFileInfo(cmd.filePath) match {
        case Left(error) =>
          reportError("Failed to read schema", globalOptions)(error)
        case Right(file) =>
          if !globalOptions.quiet then println(formatSchemaText(file, cmd.format))
          0
      }

  private[cli] def executeSchemaInfoMulti(
      service: ParquetService,
      paths: List[String],
      format: OutputFormat,
      globalOptions: GlobalOptions
  ): Int =
    runMultiFileReport(paths, format, globalOptions) { path =>
      service.getFileInfo(path).map(file => (formatSchemaText(file, format), true))
    }

  private[cli] def formatStatsText(stats: FileStats, format: OutputFormat): String =
    format match {
      case OutputFormat.JSON => CliOutputFormatter.formatStatsJson(stats)
      case _                 => CliOutputFormatter.formatStatsTable(stats)
    }

  private[cli] def executeStats(
      service: ParquetService,
      filePath: String,
      format: OutputFormat,
      globalOptions: GlobalOptions
  ): Int =
    service.getStats(filePath) match {
      case Right(stats) =>
        if !globalOptions.quiet then println(formatStatsText(stats, format))
        0
      case Left(error) =>
        reportError("Failed to get stats", globalOptions)(error)
    }

  private[cli] def executeStatsMulti(
      service: ParquetService,
      paths: List[String],
      format: OutputFormat,
      globalOptions: GlobalOptions
  ): Int =
    runMultiFileReport(paths, format, globalOptions) { path =>
      service.getStats(path).map(stats => (formatStatsText(stats, format), true))
    }

  private[cli] def formatCountText(count: Long, format: OutputFormat): String =
    format match {
      case OutputFormat.JSON => CliOutputFormatter.formatCountJson(count)
      case _                 => count.toString
    }

  private[cli] def executeCount(
      service: ParquetService,
      filePath: String,
      format: OutputFormat,
      globalOptions: GlobalOptions
  ): Int =
    service.getFileInfo(filePath) match {
      case Right(file) =>
        if !globalOptions.quiet then {
          val count = file.schema.fold(0L)(_.totalRowCount)
          println(formatCountText(count, format))
        }
        0
      case Left(error) =>
        reportError("Failed to count rows", globalOptions)(error)
    }

  private[cli] def executeCountMulti(
      service: ParquetService,
      paths: List[String],
      format: OutputFormat,
      globalOptions: GlobalOptions
  ): Int =
    runMultiFileReport(paths, format, globalOptions) { path =>
      service.getFileInfo(path).map { file =>
        val count = file.schema.fold(0L)(_.totalRowCount)
        (formatCountText(count, format), true)
      }
    }

  private[cli] def executeCompletions(
      shell: String,
      globalOptions: GlobalOptions
  ): Int =
    shell.toLowerCase match {
      case "bash" =>
        if !globalOptions.quiet then println(ShellCompletions.bash)
        0
      case "zsh" =>
        if !globalOptions.quiet then println(ShellCompletions.zsh)
        0
      case "fish" =>
        if !globalOptions.quiet then println(ShellCompletions.fish)
        0
      case other =>
        System.err.println(s"[parqueteer] error: Unsupported shell: $other. Use bash, zsh, or fish")
        1
    }

  private[cli] def executeSchemaDiff(
      service: ParquetService,
      cmd: SchemaDiffCommand,
      globalOptions: GlobalOptions
  ): Int =
    service.diffSchemas(cmd.file1, cmd.file2) match {
      case Left(error) =>
        reportError("Failed to diff schemas", globalOptions)(error)
      case Right(diff) =>
        if !globalOptions.quiet then
          cmd.format match {
            case OutputFormat.JSON =>
              println(CliOutputFormatter.formatSchemaDiffJson(diff))
            case _ =>
              println(
                CliOutputFormatter.formatSchemaDiffTable(
                  cmd.file1,
                  cmd.file2,
                  diff
                )
              )
          }
        if diff.identical then 0 else 1
    }

  private[cli] def executeConfig(
      cmd: ConfigCommand,
      globalOptions: GlobalOptions
  ): Int = ConfigCommandRenderer.render(cmd, globalOptions)

  private[cli] def reportError(
      prefix: String,
      opts: GlobalOptions,
      hint: Option[String] = None
  )(error: ParqueteerError): Int = {
    System.err.println(
      s"$prefix: ${CredentialRedactor.redact(error.userMessage)}"
    )
    hint.foreach(System.err.println)
    if opts.verbose then
      error match {
        case ParqueteerError.IOError(cause) =>
          System.err.println(
            s"[verbose] ${CredentialRedactor.redactThrowable(cause)}"
          )
          cause.getStackTrace.foreach(f =>
            System.err.println(s"\tat ${CredentialRedactor.redact(f.toString)}")
          )
        case _ => ()
      }
    error.exitCode
  }

  private[cli] def resolveAllGlobs(
      service: ParquetService,
      paths: List[String]
  ): Either[ParqueteerError, List[String]] =
    paths.foldLeft[Either[ParqueteerError, List[String]]](Right(Nil)) { (acc, p) =>
      acc.flatMap(resolved => service.resolveGlob(p).map(resolved ++ _))
    }

  private[cli] def warnIfSchemaModeNoop(
      schemaMode: SchemaMode,
      globalOptions: GlobalOptions
  ): Unit =
    if schemaMode != SchemaMode.Strict && !globalOptions.quiet then
      System.err.println(
        "[parqueteer] warning: --schema-mode has no effect — the path resolved to a single file."
      )

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
  private def fetchBounded(
      paths: List[String],
      parallelism: Int
  )(
      renderOne: String => Either[ParqueteerError, (String, Boolean)]
  ): List[(String, Either[ParqueteerError, (String, Boolean)])] =
    if paths.size <= 1 || parallelism <= 1 then paths.map(p => p -> renderOne(p))
    else {
      val pool = Executors.newFixedThreadPool(
        parallelism.min(paths.size),
        new ThreadFactory {
          private val counter = new AtomicInteger(0)
          override def newThread(r: Runnable): Thread =
            val t = new Thread(r, s"parqueteer-file-fetch-${counter.getAndIncrement()}")
            t.setDaemon(true)
            t
        }
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

  private[cli] def checkOutputWritable(
      outputPath: String
  ): Either[ParqueteerError, Unit] =
    if cloudUriPattern.findFirstIn(outputPath).isDefined then Right(())
    else {
      val parent = java.nio.file.Paths.get(outputPath).toAbsolutePath.getParent
      // Files.isWritable performs a real access() check via the filesystem
      // provider and respects POSIX ACLs; java.io.File#canWrite does not.
      // This is still check-then-act (the real write can still fail after
      // this passes) — it's a fail-fast UX nicety, not a security boundary.
      if parent != null && java.nio.file.Files.exists(parent) &&
        !java.nio.file.Files.isWritable(parent)
      then
        Left(
          ParqueteerError.IOError(
            new java.io.IOException(
              s"Output directory is not writable: $parent"
            )
          )
        )
      else Right(())
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

  // Since JDK 22, System.console() always returns a non-null Console even when
  // streams are redirected/piped (JLine became the default provider) — the old
  // `System.console() != null` check would leak ANSI colors into redirected
  // output on 22+. Console.isTerminal() (also added in 22) gives the accurate
  // answer, but this project compiles against JDK 17/21 stdlib, so it can't be
  // called directly; reflection lets it work correctly at runtime on any JDK.
  // On JDK <22, `console() != null` already meant a real terminal, so a missing
  // isTerminal() method (NoSuchMethodException) safely falls back to `true`.
  private def isStdoutTTY: Boolean = {
    val console = System.console()
    console != null && {
      try console.getClass.getMethod("isTerminal").invoke(console).asInstanceOf[Boolean]
      catch case _: NoSuchMethodException => true
    }
  }

  private[cli] def showStatus(opts: GlobalOptions): Boolean =
    !opts.quiet && isStdoutTTY
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
