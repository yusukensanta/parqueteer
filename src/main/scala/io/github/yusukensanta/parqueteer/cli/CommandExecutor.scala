package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.services.ParquetService
import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  CompressionType,
  ConversionConfig,
  OutputFormat,
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
import io.github.yusukensanta.parqueteer.core.util.FileExtension

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
            streaming
          ) =>
        executeRead(
          service,
          filePath,
          maxRows,
          columns,
          filter,
          format,
          parallelism,
          streaming,
          globalOptions
        )

      case InfoCommand(filePath, format, verbose) =>
        executeInfo(service, filePath, format, verbose, globalOptions)

      case WriteCommand(
            inputPath,
            outputPath,
            inputFormat,
            compression,
            rowGroupSize,
            dryRun
          ) =>
        executeWrite(
          service,
          outputPath,
          inputPath,
          inputFormat,
          compression,
          rowGroupSize,
          dryRun,
          globalOptions
        )

      case ValidateCommand(filePath, verbose, deep) =>
        executeValidate(service, filePath, verbose, deep, globalOptions)

      case ConvertCommand(
            inputPath,
            outputPath,
            compression,
            maxRows,
            dryRun
          ) =>
        executeConvert(
          service,
          inputPath,
          outputPath,
          compression,
          maxRows,
          dryRun,
          globalOptions
        )

      case cmd: ConfigCommand =>
        executeConfig(cmd, globalOptions)

      case cmd: SchemaCommand =>
        executeSchemaInfo(service, cmd, globalOptions)

      case cmd: SchemaDiffCommand =>
        executeSchemaDiff(service, cmd, globalOptions)

      case StatsCommand(filePath, format) =>
        executeStats(service, filePath, format, globalOptions)

      case CountCommand(filePath, format) =>
        executeCount(service, filePath, format, globalOptions)

      case MergeCommand(inputPaths, outputPath, compression, schemaMode) =>
        executeMerge(
          service,
          inputPaths,
          outputPath,
          compression,
          schemaMode,
          globalOptions
        )

      case CompletionsCommand(shell) =>
        executeCompletions(shell, globalOptions)
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
            "Warning: --filter disables parallel mode; falling back to sequential read."
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
        s"Warning: --parallelism $effectiveParallelism is ignored in streaming mode; streaming is always sequential."
      )

    if effectiveStreaming && format == OutputFormat.Pretty && !globalOptions.quiet
    then
      System.err.println(
        "Warning: --format pretty is not supported in streaming mode; falling back to ndjson."
      )

    if effectiveStreaming then {
      val writer =
        if globalOptions.quiet then
          new RowStreamWriter {
            override def writeRow(row: Map[String, CellValue]): Unit = ()
          }
        else RowStreamWriter(format, System.out)
      val result =
        runWithDeferredBegin(writer, service.streamRead(filePath, readConfig))
      val stdoutError = System.out.checkError()
      result match {
        case _ if stdoutError =>
          System.err.println(
            "[parqueteer] error: output stream write error (disk full or broken pipe)"
          )
          1
        case Right(_) => 0
        case Left(error) =>
          reportError("Error", globalOptions)(error)
      }
    } else {
      service.readFile(filePath, readConfig) match {
        case Right(file) =>
          if !globalOptions.quiet then {
            val useColors = globalOptions.colorMode match {
              case ColorMode.Never  => false
              case ColorMode.Always => true
              case ColorMode.Auto   => System.console() != null
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

  private[cli] def executeInfo(
      service: ParquetService,
      filePath: String,
      format: OutputFormat,
      verbose: Boolean,
      globalOptions: GlobalOptions
  ): Int =
    service.getFileInfo(filePath) match {
      case Right(file) =>
        if !globalOptions.quiet then {
          format match {
            case OutputFormat.JSON =>
              println(CliOutputFormatter.formatInfoJson(file, verbose))
            case _ =>
              val metaOut = file.metadata match {
                case Some(metadata) =>
                  new TableFormatter().formatMetadata(metadata)
                case None => "No metadata information available"
              }
              val schemaOut = file.schema.fold("") { s =>
                s"\nRows:        ${s.totalRowCount}\n" +
                  s"Row Groups:  ${s.rowGroupCount}\n" +
                  s"Columns:     ${s.columns.size}"
              }
              val verboseOut =
                if verbose && file.rowGroups.nonEmpty then
                  "\n\n" + CliOutputFormatter.formatRowGroupsTable(
                    file.rowGroups
                  )
                else ""
              println(metaOut + schemaOut + verboseOut)
          }
        }
        0
      case Left(error) =>
        reportError("Failed to get file info", globalOptions)(error)
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
          service.readDataFile(inputPath, formatStr) match {
            case Left(error) =>
              reportError("Failed to read input file", globalOptions)(error)
            case Right(inputData) =>
              service.writeFile(outputPath, inputData, writeConfig) match {
                case Right(_) =>
                  if showStatus(globalOptions) then
                    println(s"Successfully wrote data to $outputPath")
                  0
                case Left(error) =>
                  reportError("Failed to write file", globalOptions)(error)
              }
          }
        }
    }
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
            service.getFileInfo(filePath) match {
              case Right(file) =>
                file.schema.foreach { s =>
                  println(s"  Columns:    ${s.columns.size}")
                  println(s"  Row groups: ${s.rowGroupCount}")
                  println(s"  Total rows: ${s.totalRowCount}")
                }
              case Left(_) => ()
            }
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
          if showStatus(globalOptions) then
            println(s"Successfully converted $inputPath to $outputPath")
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
          .readDataFile(inputPath, ext)
          .flatMap(data => service.writeFile(outputPath, data, conversionConfig.writeConfig))
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
        scala.util
          .Try {
            import better.files.*
            val preExisted = File(outputPath).exists
            val outFile =
              File(outputPath).createIfNotExists(createParents = true)
            (
              preExisted,
              outFile,
              new java.io.PrintStream(outFile.newOutputStream)
            )
          }
          .toEither
          .left
          .map(ParqueteerError.IOError.apply)
          .flatMap { case (preExisted, outFile, ps) =>
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
              if failed && !preExisted then
                scala.util.Try(outFile.delete(swallowIOExceptions = true))
            }
          }
      }

  private[cli] def executeMerge(
      service: ParquetService,
      inputPaths: List[String],
      outputPath: String,
      compression: CompressionType,
      schemaMode: SchemaMode,
      globalOptions: GlobalOptions
  ): Int = {
    val writeConfig = WriteConfig(compressionType = compression)
    checkOutputWritable(outputPath) match {
      case Left(err) =>
        reportError("Failed to merge", globalOptions)(err)
      case Right(_) =>
        val total = inputPaths.size
        val onProgress: (Int, Int, String) => Unit = (i, n, path) =>
          if !globalOptions.quiet then System.err.println(s"[$i/$n] Merging: $path")

        service.mergeFiles(
          inputPaths,
          outputPath,
          writeConfig,
          schemaMode,
          onProgress
        ) match {
          case Right(count) =>
            if showStatus(globalOptions) then
              println(s"Merged $total files ($count rows) → $outputPath")
            0
          case Left(error) =>
            reportError("Failed to merge", globalOptions)(error)
        }
    }
  }

  private[cli] def executeSchemaInfo(
      service: ParquetService,
      cmd: SchemaCommand,
      globalOptions: GlobalOptions
  ): Int =
    if cmd.filePath.isEmpty then {
      System.err.println(
        "Error: schema requires a file path, or use 'schema diff FILE1 FILE2'"
      )
      2
    } else
      service.getFileInfo(cmd.filePath) match {
        case Left(error) =>
          reportError("Failed to read schema", globalOptions)(error)
        case Right(file) =>
          if !globalOptions.quiet then {
            cmd.format match {
              case OutputFormat.JSON =>
                println(CliOutputFormatter.formatSchemaJson(file))
              case _ =>
                val output = file.schema match {
                  case Some(schema) => new TableFormatter().formatSchema(schema)
                  case None         => "No schema information available"
                }
                println(output)
            }
          }
          0
      }

  private[cli] def executeStats(
      service: ParquetService,
      filePath: String,
      format: OutputFormat,
      globalOptions: GlobalOptions
  ): Int =
    service.getStats(filePath) match {
      case Right(stats) =>
        if !globalOptions.quiet then {
          format match {
            case OutputFormat.JSON =>
              println(CliOutputFormatter.formatStatsJson(stats))
            case _ => println(CliOutputFormatter.formatStatsTable(stats))
          }
        }
        0
      case Left(error) =>
        reportError("Failed to get stats", globalOptions)(error)
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
          format match {
            case OutputFormat.JSON =>
              println(CliOutputFormatter.formatCountJson(count))
            case _ => println(count)
          }
        }
        0
      case Left(error) =>
        reportError("Failed to count rows", globalOptions)(error)
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
        System.err.println(s"Unsupported shell: $other")
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

  private[cli] def checkOutputWritable(
      outputPath: String
  ): Either[ParqueteerError, Unit] =
    if cloudUriPattern.findFirstIn(outputPath).isDefined then Right(())
    else {
      val parent = java.nio.file.Paths.get(outputPath).toAbsolutePath.getParent
      if parent != null && parent.toFile.exists() && !parent.toFile.canWrite then
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
  ): Either[ParqueteerError, Long] = {
    var started = false
    val result = read { row =>
      if !started then { writer.begin(); started = true }
      writer.writeRow(row)
    }
    if !started && result.isRight then { writer.begin(); started = true }
    val endFailure: Option[Throwable] =
      if started then scala.util.Try(writer.end()).failed.toOption else None
    endFailure match {
      case Some(ex) if result.isRight => Left(ParqueteerError.IOError(ex))
      case Some(ex) =>
        System.err.println(
          s"[parqueteer] warning: error flushing output: ${CredentialRedactor
              .redact(Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))}"
        )
        result
      case None => result
    }
  }

  private def isStdoutTTY: Boolean = System.console() != null

  private[cli] def showStatus(opts: GlobalOptions): Boolean =
    !opts.quiet && isStdoutTTY
}
