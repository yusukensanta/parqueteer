package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.services.ParquetService
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.models.{
  ReadConfig,
  WriteConfig,
  OutputFormat,
  CompressionType,
  ParqueteerError,
  SchemaMode,
  ConversionConfig,
  FileContent
}
import io.github.yusukensanta.parqueteer.core.formatters.{
  JSONFormatter,
  CSVFormatter
}
import io.github.yusukensanta.parqueteer.config.{
  ConfigurationManager,
  EnvConfig
}
import io.github.yusukensanta.parqueteer.core.util.FileExtension
import scopt.OParser
import scala.util.{Success, Failure}
import org.slf4j.LoggerFactory

object CliApp {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    if (shouldShowVersion(args)) {
      showVersion()
      System.exit(0)
    }

    // Show custom top-level help only when no subcommand is present
    if (shouldShowTopLevelHelp(args)) {
      println(HelpFormatter.topLevelHelp())
      System.exit(0)
    }

    val initialConfig = ArgumentParser.Config(
      globalOptions = EnvConfig.buildInitialGlobalOptions
    )
    OParser.parse(ArgumentParser.parser, args, initialConfig) match {
      case Some(config) =>
        val exitCode = run(config)
        System.exit(exitCode)
      case None =>
        val exitCode =
          if (args.contains("--help") || args.contains("-h")) 0 else 2
        System.exit(exitCode)
    }
  }

  private def shouldShowVersion(args: Array[String]): Boolean =
    args.contains("--version") || args.contains("-V")

  private def showVersion(): Unit = {
    import io.github.yusukensanta.parqueteer.BuildInfo
    val javaVersion = System.getProperty("java.version")
    val javaVendor = System.getProperty("java.vendor", "")
    val vendorSuffix = if (javaVendor.nonEmpty) s" ($javaVendor)" else ""
    println(s"parqueteer ${BuildInfo.version}")
    println(s"Scala ${BuildInfo.scalaVersion} · Java $javaVersion$vendorSuffix")
  }

  private val knownCommands = Set(
    "read",
    "info",
    "write",
    "validate",
    "convert",
    "merge",
    "schema",
    "stats",
    "config",
    "completions",
    "diff"
  )

  private def shouldShowTopLevelHelp(args: Array[String]): Boolean = {
    val hasHelp = args.contains("--help") || args.contains("-h")
    val hasCommand = args.exists(knownCommands.contains)
    hasHelp && !hasCommand
  }

  private def run(config: ArgumentParser.Config): Int =
    config.command match {
      case None =>
        println(HelpFormatter.topLevelHelp())
        1
      case Some(cmd) =>
        try {
          val repository = new ParquetRepository(
            profile = config.globalOptions.profile,
            region = config.globalOptions.region
          )
          val service = new ParquetService(repository)
          executeCommand(cmd, service, config.globalOptions)
        } catch {
          case e: Exception =>
            logger.error("Unexpected error", e)
            System.err.println(s"Error: ${e.getMessage}")
            if (config.globalOptions.verbose) e.printStackTrace()
            1
        }
    }

  private def executeCommand(
      command: Command,
      service: ParquetService,
      globalOptions: GlobalOptions
  ): Int = {
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

      case InfoCommand(filePath, format) =>
        executeInfo(service, filePath, format, globalOptions)

      case WriteCommand(
            outputPath,
            inputPath,
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

      case ValidateCommand(filePath, verbose) =>
        executeValidate(service, filePath, verbose, globalOptions)

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
  }

  private def executeRead(
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
    val readConfig = ReadConfig(
      maxRows = maxRows,
      columns = columns,
      filter = filter,
      outputFormat = format,
      parallelism = parallelism,
      streamingMode = streaming
    )

    if (streaming) {
      import io.github.yusukensanta.parqueteer.core.formatters.RowStreamWriter
      val writer = if (globalOptions.quiet) new RowStreamWriter {
        override def writeRow(row: Map[String, Any]): Unit = ()
      }
      else RowStreamWriter(format, System.out)
      writer.begin()
      service.streamRead(filePath, readConfig)(writer.writeRow) match {
        case Right(_) =>
          writer.end()
          0
        case Left(error) =>
          reportError("Error", globalOptions)(error)
      }
    } else {
      service.readFile(filePath, readConfig) match {
        case Right(file) =>
          if (!globalOptions.quiet) {
            val useColors = globalOptions.colorMode match {
              case ColorMode.Never  => false
              case ColorMode.Always => true
              case ColorMode.Auto   => System.console() != null
            }
            import io.github.yusukensanta.parqueteer.core.formatters.OutputFormatter
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
            if (
              filter.isDefined && error.userMessage.contains(
                "FilterPredicate"
              ) && error.userMessage.contains("BINARY")
            )
              Some(
                "Hint: CSV-imported files store all columns as BINARY (string). Use string comparisons instead: column = \"value\""
              )
            else None
          reportError("Error", globalOptions, filterHint)(error)
      }
    }
  }

  private def executeInfo(
      service: ParquetService,
      filePath: String,
      format: OutputFormat,
      globalOptions: GlobalOptions
  ): Int = {
    service.getFileInfo(filePath) match {
      case Right(file) =>
        if (!globalOptions.quiet) {
          format match {
            case OutputFormat.JSON =>
              println(CliOutputFormatter.formatInfoJson(file))
            case _ =>
              import io.github.yusukensanta.parqueteer.core.formatters.TableFormatter
              val output = file.metadata match {
                case Some(metadata) =>
                  new TableFormatter().formatMetadata(metadata)
                case None => "No metadata information available"
              }
              println(output)
          }
        }
        0
      case Left(error) =>
        reportError("Failed to get file info", globalOptions)(error)
    }
  }

  private def executeWrite(
      service: ParquetService,
      outputPath: String,
      inputPath: String,
      inputFormat: String,
      compression: CompressionType,
      rowGroupSize: Option[Long],
      dryRun: Boolean,
      globalOptions: GlobalOptions
  ): Int = {
    val writeConfig = WriteConfig(
      compressionType = compression,
      rowGroupSize = rowGroupSize.getOrElse(WriteConfig.DefaultRowGroupSize)
    )
    service.readDataFile(inputPath, inputFormat) match {
      case Left(error) =>
        reportError("Failed to read input file", globalOptions)(error)
      case Right(inputData) =>
        if (dryRun) {
          val columns =
            inputData.headOption.map(_.keys.toList.sorted).getOrElse(Nil)
          println(s"Dry run: would write $outputPath")
          println(s"  Input:       $inputPath ($inputFormat)")
          println(s"  Rows:        ${inputData.size}")
          println(s"  Columns:     ${columns.mkString(", ")}")
          println(s"  Compression: ${compression.toString.toLowerCase}")
          0
        } else {
          service.writeFile(outputPath, inputData, writeConfig) match {
            case Right(_) =>
              if (showStatus(globalOptions))
                println(s"Successfully wrote data to $outputPath")
              0
            case Left(error) =>
              reportError("Failed to write file", globalOptions)(error)
          }
        }
    }
  }

  private def executeValidate(
      service: ParquetService,
      filePath: String,
      verbose: Boolean,
      globalOptions: GlobalOptions
  ): Int = {
    service.validateFile(filePath) match {
      case Right(result) =>
        if (result.isValid) {
          if (!globalOptions.quiet) println(s"✓ File $filePath is valid")
          if (verbose) {
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
  }

  private def executeConvert(
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

    if (dryRun) {
      val inputExt = FileExtension.of(inputPath)
      if (inputExt == "parquet") {
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
      } else {
        println(s"Dry run: would convert $inputPath → $outputPath")
        println(s"  Input format: $inputExt")
        println(
          s"  Compression:  ${compression.toString.toLowerCase} (output)"
        )
        0
      }
    } else {
      val outputExt = FileExtension.of(outputPath)
      val inputExt = FileExtension.of(inputPath)
      val result: Either[ParqueteerError, Unit] = (inputExt, outputExt) match {
        case ("parquet", ext @ ("json" | "csv")) =>
          service
            .readFile(inputPath, ReadConfig(maxRows = conversionConfig.maxRows))
            .flatMap { file =>
              val content =
                file.content.getOrElse(FileContent(List.empty, 0, false))
              val text = ext match {
                case "json" => new JSONFormatter().formatContent(content, None)
                case _      => new CSVFormatter().formatContent(content, None)
              }
              scala.util
                .Try {
                  import better.files._
                  File(outputPath).createIfNotExists().write(text)
                }
                .toEither
                .left
                .map(ParqueteerError.IOError.apply)
                .map(_ => ())
            }
        case ("parquet", "parquet") =>
          service
            .readFile(inputPath, ReadConfig(maxRows = conversionConfig.maxRows))
            .flatMap { file =>
              val data = file.content.map(_.rows).getOrElse(List.empty)
              service.writeFile(outputPath, data, conversionConfig.writeConfig)
            }
        case (ext @ ("json" | "csv"), "parquet") =>
          service
            .readDataFile(inputPath, ext)
            .flatMap(data =>
              service.writeFile(outputPath, data, conversionConfig.writeConfig)
            )
        case _ =>
          Left(
            ParqueteerError.InvalidFormat(
              inputPath,
              s"Unsupported conversion: $inputExt → $outputExt. Supported: parquet→parquet, parquet→json, parquet→csv, json→parquet, csv→parquet"
            )
          )
      }
      result match {
        case Right(_) =>
          if (showStatus(globalOptions))
            println(s"Successfully converted $inputPath to $outputPath")
          0
        case Left(error) =>
          reportError("Failed to convert file", globalOptions)(error)
      }
    }
  }

  private def executeMerge(
      service: ParquetService,
      inputPaths: List[String],
      outputPath: String,
      compression: CompressionType,
      schemaMode: SchemaMode,
      globalOptions: GlobalOptions
  ): Int = {
    if (inputPaths.size < 2) {
      System.err.println("Error: merge requires at least two input files")
      1
    } else {
      val writeConfig = WriteConfig(compressionType = compression)
      val total = inputPaths.size
      val onProgress: (Int, Int, String) => Unit = (i, n, path) =>
        if (!globalOptions.quiet)
          System.err.println(s"[$i/$n] Merging: $path")

      service.mergeFiles(
        inputPaths,
        outputPath,
        writeConfig,
        schemaMode,
        onProgress
      ) match {
        case Right(count) =>
          if (showStatus(globalOptions))
            println(s"Merged $total files ($count rows) → $outputPath")
          0
        case Left(error) =>
          reportError("Failed to merge", globalOptions)(error)
      }
    }
  }

  private def executeSchemaInfo(
      service: ParquetService,
      cmd: SchemaCommand,
      globalOptions: GlobalOptions
  ): Int = {
    if (cmd.filePath.isEmpty) {
      System.err.println(
        "Error: schema requires a file path, or use 'schema diff FILE1 FILE2'"
      )
      2
    } else
      service.getFileInfo(cmd.filePath) match {
        case Left(error) =>
          reportError("Failed to read schema", globalOptions)(error)
        case Right(file) =>
          if (!globalOptions.quiet) {
            cmd.format match {
              case OutputFormat.JSON =>
                println(CliOutputFormatter.formatSchemaJson(file))
              case _ =>
                import io.github.yusukensanta.parqueteer.core.formatters.TableFormatter
                val output = file.schema match {
                  case Some(schema) => new TableFormatter().formatSchema(schema)
                  case None         => "No schema information available"
                }
                println(output)
            }
          }
          0
      }
  }

  private def executeStats(
      service: ParquetService,
      filePath: String,
      format: OutputFormat,
      globalOptions: GlobalOptions
  ): Int = {
    service.getStats(filePath) match {
      case Right(stats) =>
        if (!globalOptions.quiet) {
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
  }

  private def executeCompletions(
      shell: String,
      globalOptions: GlobalOptions
  ): Int =
    shell.toLowerCase match {
      case "bash" =>
        if (!globalOptions.quiet) println(ShellCompletions.bash)
        0
      case "zsh" =>
        if (!globalOptions.quiet) println(ShellCompletions.zsh)
        0
      case "fish" =>
        if (!globalOptions.quiet) println(ShellCompletions.fish)
        0
      case other =>
        System.err.println(s"Unsupported shell: $other")
        1
    }

  private def executeSchemaDiff(
      service: ParquetService,
      cmd: SchemaDiffCommand,
      globalOptions: GlobalOptions
  ): Int = {
    service.diffSchemas(cmd.file1, cmd.file2) match {
      case Left(error) =>
        reportError("Failed to diff schemas", globalOptions)(error)
      case Right(diff) =>
        if (!globalOptions.quiet)
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
        if (diff.identical) 0 else 1
    }
  }

  private def executeConfig(
      cmd: ConfigCommand,
      globalOptions: GlobalOptions
  ): Int = {
    val configManager = new ConfigurationManager()
    val configPath = globalOptions.configPath
    if (cmd.validate) {
      configManager.validate(configPath) match {
        case scala.util.Success(Nil) =>
          if (!globalOptions.quiet) println(s"✓ Configuration is valid")
          0
        case scala.util.Success(issues) =>
          issues.foreach(i => println(s"  $i"))
          0
        case scala.util.Failure(ex) =>
          System.err.println(s"Config error: ${ex.getMessage}")
          1
      }
    } else {
      if (!globalOptions.quiet) {
        val resolvedPath = configManager.resolvedConfigPath(configPath)
        val fileExists = better.files.File(resolvedPath).exists
        println(s"Config file: $resolvedPath [${
            if (fileExists) "exists" else "not found"
          }]")
        if (fileExists)
          println(
            "  Note: config file is parsed for validation only; settings are not yet applied."
          )
        println()

        val envVars = EnvConfig.allSet
        if (envVars.isEmpty) {
          println("Environment variables: (none set)")
        } else {
          println("Environment variables:")
          EnvConfig.SupportedVars.foreach { key =>
            envVars.get(key) match {
              case Some(v) => println(s"  $key=$v")
              case None    => ()
            }
          }
        }
        println()

        println("Resolved settings:")
        val fmt = EnvConfig.parsedDefaultFormat
          .map(_.toString.toLowerCase + " [env]")
          .getOrElse("table [default]")
        println(s"  format:   $fmt")
        val color = {
          val cm = globalOptions.colorMode
          val src =
            if (sys.env.get("NO_COLOR").exists(_.nonEmpty)) "[env: NO_COLOR]"
            else if (sys.env.contains("PARQUETEER_COLOR")) "[env]"
            else "[default]"
          s"${cm.toString.toLowerCase} $src"
        }
        println(s"  color:    $color")
        val verboseFlag =
          if (globalOptions.verbose) "true [cli/env]" else "false [default]"
        println(s"  verbose:  $verboseFlag")
        val quiet =
          if (globalOptions.quiet) "true [cli]" else "false [default]"
        println(s"  quiet:    $quiet")
        EnvConfig.parsedMaxRows.foreach { n =>
          println(s"  limit: $n [env: PARQUETEER_MAX_ROWS]")
        }
      }
      0
    }
  }

  private def reportError(
      prefix: String,
      opts: GlobalOptions,
      hint: Option[String] = None
  )(error: ParqueteerError): Int = {
    System.err.println(s"$prefix: ${error.userMessage}")
    hint.foreach(System.err.println)
    if (opts.verbose) error match {
      case ParqueteerError.IOError(cause) =>
        System.err.println(
          s"[verbose] ${CredentialRedactor.redact(cause.toString)}"
        )
        cause.getStackTrace.foreach(f => System.err.println(s"\tat $f"))
        Option(cause.getCause).foreach { c =>
          System.err.println(
            s"Caused by: ${CredentialRedactor.redact(c.toString)}"
          )
          c.getStackTrace.foreach(f => System.err.println(s"\tat $f"))
        }
      case _ => ()
    }
    error.exitCode
  }

  private def isStdoutTTY: Boolean = System.console() != null

  private def showStatus(opts: GlobalOptions): Boolean =
    !opts.quiet && isStdoutTTY
}
