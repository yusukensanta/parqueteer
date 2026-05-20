package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.services.{
  ParquetService,
  ConversionConfig,
  SchemaDiff
}
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.models.{
  ReadConfig,
  WriteConfig,
  OutputFormat,
  CompressionType,
  ParqueteerError,
  ColumnInfo,
  FileStats,
  ParquetFile,
  SchemaMode
}
import io.circe.Json
import io.github.yusukensanta.parqueteer.config.{
  ConfigurationManager,
  EnvConfig
}
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

    // Intercept help requests for custom hierarchical help
    if (shouldShowHelp(args)) {
      showHelp(args)
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
        System.exit(2)
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

  /** Check if help should be displayed */
  private def shouldShowHelp(args: Array[String]): Boolean = {
    args.contains("--help") || args.contains("-h")
  }

  /** Show appropriate help based on command context */
  private def showHelp(args: Array[String]): Unit = {
    val commands = Set(
      "read",
      "info",
      "write",
      "validate",
      "convert",
      "merge",
      "schema",
      "stats",
      "config",
      "completions"
    )
    val commandBeforeHelp = args
      .takeWhile(arg => !arg.startsWith("--help") && !arg.startsWith("-h"))
      .find(commands.contains)

    commandBeforeHelp match {
      case Some(command) =>
        HelpFormatter.commandHelp(command) match {
          case Some(help) => println(help)
          case None       => println(HelpFormatter.topLevelHelp())
        }
      case None =>
        println(HelpFormatter.topLevelHelp())
    }
  }

  private def run(config: ArgumentParser.Config): Int =
    config.command match {
      case None =>
        println(HelpFormatter.topLevelHelp())
        1
      case Some(cmd) =>
        try {
          val configManager = new ConfigurationManager()
          configManager.loadConfig(config.globalOptions.configPath) match {
            case Failure(error) =>
              System.err.println(
                s"Failed to load configuration: ${error.getMessage}"
              )
              1
            case Success(_) =>
              val repository = new ParquetRepository()
              val service = new ParquetService(repository)
              executeCommand(cmd, service, config.globalOptions)
          }
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

      case ValidateCommand(filePath, _) =>
        executeValidate(service, filePath, globalOptions)

      case ConvertCommand(inputPath, outputPath, compression, _, dryRun) =>
        executeConvert(
          service,
          inputPath,
          outputPath,
          compression,
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
          System.err.println(s"Error: ${error.userMessage}")
          if (globalOptions.verbose) error match {
            case ParqueteerError.IOError(cause) => cause.printStackTrace()
            case _                              => ()
          }
          error.exitCode
      }
    } else {
      service.readFile(filePath, readConfig) match {
        case Right(file) =>
          if (!globalOptions.quiet) {
            val useColors = globalOptions.colorMode match {
              case ColorMode.Never  => false
              case ColorMode.Always => true
              case ColorMode.Auto =>
                sys.env.get("NO_COLOR").isEmpty && System.console() != null
            }
            import io.github.yusukensanta.parqueteer.core.formatters.OutputFormatter
            val formatter = OutputFormatter(format, useColors)
            val output = file.content match {
              case Some(content) => formatter.formatContent(content, file.schema)
              case None          => "No content available"
            }
            println(output)
          }
          0
        case Left(error) =>
          System.err.println(s"Error: ${error.userMessage}")
          if (
            filter.isDefined &&
            error.userMessage.contains("FilterPredicate") &&
            error.userMessage.contains("BINARY")
          )
            System.err.println(
              "Hint: CSV-imported files store all columns as BINARY (string). Use string comparisons instead: column = \"value\""
            )
          if (globalOptions.verbose) error match {
            case ParqueteerError.IOError(cause) => cause.printStackTrace()
            case _                              => ()
          }
          error.exitCode
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
            case OutputFormat.JSON => println(formatInfoJson(file))
            case _ =>
              import io.github.yusukensanta.parqueteer.core.formatters.TableFormatter
              val output = file.metadata match {
                case Some(metadata) => new TableFormatter().formatMetadata(metadata)
                case None           => "No metadata information available"
              }
              println(output)
          }
        }
        0
      case Left(error) =>
        System.err.println(s"Failed to get file info: ${error.userMessage}")
        if (globalOptions.verbose) error match {
          case ParqueteerError.IOError(cause) => cause.printStackTrace()
          case _                              => ()
        }
        1
    }
  }

  private def formatInfoJson(file: ParquetFile): String =
    file.metadata.fold(Json.obj().spaces2) { m =>
      Json
        .obj(
          "fileSize" -> Json.fromLong(m.fileSize),
          "createdAt" -> m.createdAt.fold(Json.Null)(t =>
            Json.fromString(t.toString)
          ),
          "modifiedAt" -> m.modifiedAt.fold(Json.Null)(t =>
            Json.fromString(t.toString)
          ),
          "compressionRatio" -> m.compressionRatio.fold(Json.Null)(
            Json.fromDoubleOrNull
          ),
          "version" -> Json.fromString(m.version),
          "createdBy" -> m.createdBy.fold(Json.Null)(Json.fromString)
        )
        .spaces2
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
      case Failure(error) =>
        System.err.println(s"Failed to read input data: ${error.getMessage}")
        if (globalOptions.verbose) error.printStackTrace()
        1
      case Success(inputData) =>
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
              System.err.println(s"Failed to write file: ${error.userMessage}")
              if (globalOptions.verbose) error match {
                case ParqueteerError.IOError(cause) => cause.printStackTrace()
                case _                              => ()
              }
              1
          }
        }
    }
  }

  private def executeValidate(
      service: ParquetService,
      filePath: String,
      globalOptions: GlobalOptions
  ): Int = {
    service.validateFile(filePath) match {
      case Right(result) =>
        if (result.isValid) {
          if (!globalOptions.quiet) println(s"✓ File $filePath is valid")
          0
        } else {
          println(s"✗ File $filePath has issues:")
          result.issues.foreach(issue => println(s"  - $issue"))
          1
        }
      case Left(error) =>
        System.err.println(s"Failed to validate file: ${error.userMessage}")
        if (globalOptions.verbose) error match {
          case ParqueteerError.IOError(cause) => cause.printStackTrace()
          case _                              => ()
        }
        1
    }
  }

  private def executeConvert(
      service: ParquetService,
      inputPath: String,
      outputPath: String,
      compression: CompressionType,
      dryRun: Boolean,
      globalOptions: GlobalOptions
  ): Int = {
    val conversionConfig = ConversionConfig(
      writeConfig = WriteConfig(compressionType = compression)
    )

    if (dryRun) {
      val inputExt =
        inputPath
          .split("\\.")
          .lastOption
          .map(_.toLowerCase)
          .getOrElse("unknown")
      if (inputExt == "parquet") {
        service.getFileInfo(inputPath) match {
          case Left(error) =>
            System.err.println(s"Failed to read input: ${error.userMessage}")
            if (globalOptions.verbose) error match {
              case ParqueteerError.IOError(cause) => cause.printStackTrace()
              case _                              => ()
            }
            1
          case Right(file) =>
            println(s"Dry run: would convert $inputPath → $outputPath")
            println(s"  Input:       $inputPath")
            file.metadata.foreach(m =>
              println(s"  File size:   ${formatBytesForDisplay(m.fileSize)}")
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
      service.convertFile(inputPath, outputPath, conversionConfig) match {
        case Right(_) =>
          if (showStatus(globalOptions))
            println(s"Successfully converted $inputPath to $outputPath")
          0
        case Left(error) =>
          System.err.println(s"Failed to convert file: ${error.userMessage}")
          if (globalOptions.verbose) error match {
            case ParqueteerError.IOError(cause) => cause.printStackTrace()
            case _                              => ()
          }
          error.exitCode
      }
    }
  }

  private def formatBytesForDisplay(bytes: Long): String = {
    val units = List("B", "KB", "MB", "GB", "TB")
    @annotation.tailrec
    def loop(size: Double, idx: Int): String =
      if (size < 1024 || idx >= units.length - 1) f"$size%.1f ${units(idx)}"
      else loop(size / 1024, idx + 1)
    loop(bytes.toDouble, 0)
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
      return 1
    }
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
        System.err.println(s"Failed to merge: ${error.userMessage}")
        if (globalOptions.verbose) error match {
          case ParqueteerError.IOError(cause) => cause.printStackTrace()
          case _                              => ()
        }
        error.exitCode
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
      return 2
    }
    service.getFileInfo(cmd.filePath) match {
      case Left(error) =>
        System.err.println(s"Failed to read schema: ${error.userMessage}")
        if (globalOptions.verbose) error match {
          case ParqueteerError.IOError(cause) => cause.printStackTrace()
          case _                              => ()
        }
        1
      case Right(file) =>
        if (!globalOptions.quiet) {
          cmd.format match {
            case OutputFormat.JSON => println(formatSchemaJson(file))
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

  private def formatSchemaJson(file: ParquetFile): String =
    file.schema.fold(Json.obj().spaces2) { s =>
      Json
        .obj(
          "totalRowCount" -> Json.fromLong(s.totalRowCount),
          "rowGroupCount" -> Json.fromLong(s.rowGroupCount),
          "columns" -> Json.fromValues(s.columns.map { c =>
            Json.obj(
              "name" -> Json.fromString(c.name),
              "dataType" -> Json.fromString(c.dataType),
              "optional" -> Json.fromBoolean(c.isOptional),
              "compressionType" -> Json.fromString(c.compressionType)
            )
          })
        )
        .spaces2
    }

  private def formatStatsTable(stats: FileStats): String = {
    val sb = new StringBuilder
    sb.append(
      s"Stats: ${stats.totalRows} rows, ${stats.rowGroupCount} row groups\n\n"
    )
    val header =
      f"${"Column"}%-30s ${"Type"}%-15s ${"Nulls"}%10s ${"Min"}%-20s ${"Max"}%-20s"
    sb.append(header + "\n")
    sb.append("-" * header.length + "\n")
    stats.columns.foreach { col =>
      val nulls = if (col.nullCount < 0) "n/a" else col.nullCount.toString
      val min = col.minValue.getOrElse("n/a")
      val max = col.maxValue.getOrElse("n/a")
      sb.append(
        f"${col.name}%-30s ${col.dataType}%-15s ${nulls}%10s ${min}%-20s ${max}%-20s\n"
      )
    }
    sb.toString.stripTrailing()
  }

  private def formatStatsJson(stats: FileStats): Json =
    Json.obj(
      "totalRows" -> Json.fromLong(stats.totalRows),
      "rowGroupCount" -> Json.fromLong(stats.rowGroupCount),
      "columns" -> Json.fromValues(stats.columns.map { col =>
        Json.obj(
          "name" -> Json.fromString(col.name),
          "dataType" -> Json.fromString(col.dataType),
          "nullCount" -> Json.fromLong(col.nullCount),
          "minValue" -> col.minValue.fold(Json.Null)(Json.fromString),
          "maxValue" -> col.maxValue.fold(Json.Null)(Json.fromString)
        )
      })
    )

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
            case OutputFormat.JSON => println(formatStatsJson(stats).spaces2)
            case _                 => println(formatStatsTable(stats))
          }
        }
        0
      case Left(error) =>
        System.err.println(s"Failed to get stats: ${error.userMessage}")
        if (globalOptions.verbose) error match {
          case ParqueteerError.IOError(cause) => cause.printStackTrace()
          case _                              => ()
        }
        1
    }
  }

  private def executeCompletions(
      shell: String,
      globalOptions: GlobalOptions
  ): Int = {
    val script = shell.toLowerCase match {
      case "bash" => ShellCompletions.bash
      case "zsh"  => ShellCompletions.zsh
      case "fish" => ShellCompletions.fish
      case other =>
        System.err.println(s"Unsupported shell: $other")
        return 1
    }
    if (!globalOptions.quiet) println(script)
    0
  }

  private def executeSchemaDiff(
      service: ParquetService,
      cmd: SchemaDiffCommand,
      globalOptions: GlobalOptions
  ): Int = {
    service.diffSchemas(cmd.file1, cmd.file2) match {
      case Left(error) =>
        System.err.println(s"Failed to diff schemas: ${error.userMessage}")
        if (globalOptions.verbose) error match {
          case ParqueteerError.IOError(cause) => cause.printStackTrace()
          case _                              => ()
        }
        error.exitCode
      case Right(diff) =>
        if (!globalOptions.quiet)
          cmd.format match {
            case OutputFormat.JSON => println(formatSchemaDiffJson(diff))
            case _ => println(formatSchemaDiffTable(cmd.file1, cmd.file2, diff))
          }
        if (diff.identical) 0 else 1
    }
  }

  private def formatSchemaDiffTable(
      file1: String,
      file2: String,
      diff: SchemaDiff
  ): String = {
    val sb = new StringBuilder
    sb.append(s"Schema diff: $file1 → $file2\n")

    if (diff.identical) {
      sb.append("Schemas are identical.")
    } else {
      diff.removed.foreach { c =>
        val opt = if (c.isOptional) "optional" else "required"
        sb.append(s"- ${c.name} (${c.dataType}, $opt)\n")
      }
      diff.added.foreach { c =>
        val opt = if (c.isOptional) "optional" else "required"
        sb.append(s"+ ${c.name} (${c.dataType}, $opt)\n")
      }
      diff.changed.foreach { c =>
        val fromOpt = if (c.fromOptional) "optional" else "required"
        val toOpt = if (c.toOptional) "optional" else "required"
        if (c.fromType != c.toType && c.fromOptional != c.toOptional)
          sb.append(
            s"~ ${c.name}: ${c.fromType} $fromOpt → ${c.toType} $toOpt\n"
          )
        else if (c.fromType != c.toType)
          sb.append(s"~ ${c.name}: ${c.fromType} → ${c.toType}\n")
        else
          sb.append(s"~ ${c.name}: $fromOpt → $toOpt\n")
      }
      if (diff.unchanged.nonEmpty)
        sb.append(s"= ${diff.unchanged.mkString(", ")}")
    }

    sb.toString.stripTrailing()
  }

  private def formatSchemaDiffJson(diff: SchemaDiff): String = {
    def colJson(c: ColumnInfo) =
      Json.obj(
        "name" -> Json.fromString(c.name),
        "type" -> Json.fromString(c.dataType),
        "optional" -> Json.fromBoolean(c.isOptional)
      )

    Json
      .obj(
        "identical" -> Json.fromBoolean(diff.identical),
        "added" -> Json.fromValues(diff.added.map(colJson)),
        "removed" -> Json.fromValues(diff.removed.map(colJson)),
        "changed" -> Json.fromValues(diff.changed.map { c =>
          Json.obj(
            "name" -> Json.fromString(c.name),
            "from_type" -> Json.fromString(c.fromType),
            "to_type" -> Json.fromString(c.toType),
            "from_optional" -> Json.fromBoolean(c.fromOptional),
            "to_optional" -> Json.fromBoolean(c.toOptional)
          )
        }),
        "unchanged" -> Json.fromValues(diff.unchanged.map(Json.fromString))
      )
      .spaces2
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

  private def isStdoutTTY: Boolean = System.console() != null

  private def showStatus(opts: GlobalOptions): Boolean =
    !opts.quiet && isStdoutTTY
}
