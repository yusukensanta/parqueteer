package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.services.{
  ParquetService,
  ConversionConfig
}
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.models.{
  ReadConfig,
  WriteConfig,
  OutputFormat,
  CompressionType
}
import io.github.yusukensanta.parqueteer.config.{
  ConfigurationManager,
  LoggingConfig
}
import scopt.OParser
import scala.util.{Success, Failure}
import org.slf4j.LoggerFactory

object CliApp {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Intercept help requests for custom hierarchical help
    if (shouldShowHelp(args)) {
      showHelp(args)
      System.exit(0)
    }

    OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config()) match {
      case Some(config) =>
        val exitCode = run(config)
        System.exit(exitCode)
      case None =>
        System.exit(2)
    }
  }

  /** Check if help should be displayed */
  private def shouldShowHelp(args: Array[String]): Boolean = {
    args.contains("--help") || args.contains("-h")
  }

  /** Show appropriate help based on command context */
  private def showHelp(args: Array[String]): Unit = {
    val commands = Set("read", "info", "write", "validate", "convert")
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
            case Success(appConfig) =>
              setupLogging(appConfig.logging, config.globalOptions.verbose)
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
      case ReadCommand(filePath, maxRows, columns, filter, format) =>
        executeRead(
          service,
          filePath,
          maxRows,
          columns,
          filter,
          format,
          globalOptions
        )

      case InfoCommand(filePath, format, showSchema, showMetadata) =>
        executeInfo(
          service,
          filePath,
          format,
          showSchema,
          showMetadata,
          globalOptions
        )

      case WriteCommand(
            outputPath,
            inputPath,
            inputFormat,
            compression,
            rowGroupSize
          ) =>
        executeWrite(
          service,
          outputPath,
          inputPath,
          inputFormat,
          compression,
          rowGroupSize,
          globalOptions
        )

      case ValidateCommand(filePath, verbose) =>
        executeValidate(
          service,
          filePath,
          verbose || globalOptions.verbose,
          globalOptions
        )

      case ConvertCommand(inputPath, outputPath, compression, maxRows) =>
        executeConvert(
          service,
          inputPath,
          outputPath,
          compression,
          maxRows,
          globalOptions
        )
    }
  }

  private def executeRead(
      service: ParquetService,
      filePath: String,
      maxRows: Option[Long],
      columns: Option[List[String]],
      filter: Option[String],
      format: OutputFormat,
      globalOptions: GlobalOptions
  ): Int = {
    val readConfig = ReadConfig(
      maxRows = maxRows,
      columns = columns,
      filter = filter,
      outputFormat = format
    )

    service.readFile(filePath, readConfig) match {
      case Success(file) =>
        val output = service.formatContent(file, format)
        println(output)
        0
      case Failure(error) =>
        System.err.println(s"Failed to read file: ${error.getMessage}")
        if (globalOptions.verbose) error.printStackTrace()
        1
    }
  }

  private def executeInfo(
      service: ParquetService,
      filePath: String,
      format: OutputFormat,
      showSchema: Boolean,
      showMetadata: Boolean,
      globalOptions: GlobalOptions
  ): Int = {
    val _ = (format, globalOptions) // suppress unused warnings
    service.getFileInfo(filePath) match {
      case Success(file) =>
        if (showMetadata) {
          println(service.formatMetadata(file))
          println()
        }
        if (showSchema) {
          println(service.formatSchema(file))
        }
        0
      case Failure(error) =>
        System.err.println(s"Failed to get file info: ${error.getMessage}")
        if (globalOptions.verbose) error.printStackTrace()
        1
    }
  }

  private def executeWrite(
      service: ParquetService,
      outputPath: String,
      inputPath: String,
      inputFormat: String,
      compression: CompressionType,
      rowGroupSize: Option[Long],
      globalOptions: GlobalOptions
  ): Int = {
    try {
      val inputData = readInputData(inputPath, inputFormat)

      val writeConfig = WriteConfig(
        compressionType = compression,
        rowGroupSize = rowGroupSize.getOrElse(128 * 1024 * 1024L)
      )

      service.writeFile(outputPath, inputData, writeConfig) match {
        case Success(_) =>
          println(s"Successfully wrote data to $outputPath")
          0
        case Failure(error) =>
          System.err.println(s"Failed to write file: ${error.getMessage}")
          if (globalOptions.verbose) error.printStackTrace()
          1
      }
    } catch {
      case e: Exception =>
        System.err.println(s"Failed to read input data: ${e.getMessage}")
        if (globalOptions.verbose) e.printStackTrace()
        1
    }
  }

  private def executeValidate(
      service: ParquetService,
      filePath: String,
      verbose: Boolean,
      globalOptions: GlobalOptions
  ): Int = {
    val _ = (verbose, globalOptions) // suppress unused warnings
    service.validateFile(filePath) match {
      case Success(result) =>
        if (result.isValid) {
          println(s"✓ File $filePath is valid")
          0
        } else {
          println(s"✗ File $filePath has issues:")
          result.issues.foreach(issue => println(s"  - $issue"))
          1
        }
      case Failure(error) =>
        System.err.println(s"Failed to validate file: ${error.getMessage}")
        if (globalOptions.verbose) error.printStackTrace()
        1
    }
  }

  private def executeConvert(
      service: ParquetService,
      inputPath: String,
      outputPath: String,
      compression: CompressionType,
      maxRows: Option[Long],
      globalOptions: GlobalOptions
  ): Int = {
    val _ = (maxRows, globalOptions) // suppress unused warnings
    val conversionConfig = ConversionConfig(
      writeConfig = WriteConfig(compressionType = compression)
    )

    service.convertFile(inputPath, outputPath, conversionConfig) match {
      case Success(_) =>
        println(s"Successfully converted $inputPath to $outputPath")
        0
      case Failure(error) =>
        System.err.println(s"Failed to convert file: ${error.getMessage}")
        if (globalOptions.verbose) error.printStackTrace()
        1
    }
  }

  private def readInputData(
      inputPath: String,
      inputFormat: String
  ): List[Map[String, Any]] = {
    import better.files._
    import io.circe.parser._

    val content = File(inputPath).contentAsString

    inputFormat.toLowerCase match {
      case "json" =>
        parse(content) match {
          case Left(error) =>
            throw new IllegalArgumentException(
              s"Failed to parse JSON: ${error.getMessage}"
            )
          case Right(json) =>
            json.asArray match {
              case Some(array) =>
                array.toList.map(
                  _.asObject.get.toMap.view
                    .mapValues {
                      case json if json.isString => json.asString.get
                      case json if json.isNumber =>
                        json.asNumber.get.toDouble
                      case json if json.isBoolean => json.asBoolean.get
                      case json if json.isNull    => null
                      case json                   => json.toString
                    }
                    .toMap
                )
              case None =>
                throw new java.lang.IllegalArgumentException(
                  "JSON must be an array of objects"
                )
            }
        }
      case "csv" =>
        val lines = content.split("\n").toList
        if (lines.isEmpty) return List.empty

        val headers = lines.head.split(",").map(_.trim)
        lines.tail.map { line =>
          val values = line.split(",").map(_.trim)
          headers.zip(values).toMap.asInstanceOf[Map[String, Any]]
        }
      case _ =>
        throw new java.lang.IllegalArgumentException(
          s"Unsupported input format: $inputFormat"
        )
    }
  }

  private def setupLogging(
      loggingConfig: LoggingConfig,
      verbose: Boolean
  ): Unit = {
    // Note: Using slf4j-simple which doesn't support programmatic configuration
    // To configure logging level, set system property before JVM starts:
    //   -Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG
    // We keep this method for compatibility but logging config is now static
    ()
  }
}
