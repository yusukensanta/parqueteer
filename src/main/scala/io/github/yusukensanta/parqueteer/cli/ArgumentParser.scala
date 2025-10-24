package io.github.yusukensanta.parqueteer.cli

import scopt.OParser
import io.github.yusukensanta.parqueteer.core.models.{
  OutputFormat,
  CompressionType
}

object ArgumentParser {
  case class Config(
      command: Option[Command] = None,
      globalOptions: GlobalOptions = GlobalOptions()
  )

  private val builder = OParser.builder[Config]

  val parser: OParser[Unit, Config] = {
    import builder._

    OParser.sequence(
      programName("parqueteer"),
      head("parqueteer", "0.1.0"),
      help("help").abbr("h").text("Show help information"),
      version("version").abbr("V").text("Show version information"),
      opt[Unit]("verbose")
        .abbr("v")
        .action((_, c) =>
          c.copy(globalOptions = c.globalOptions.copy(verbose = true))
        )
        .text("Enable verbose output"),
      opt[String]("config")
        .action((x, c) =>
          c.copy(globalOptions = c.globalOptions.copy(configPath = Some(x)))
        )
        .text("Path to configuration file"),
      opt[String]("profile")
        .action((x, c) =>
          c.copy(globalOptions = c.globalOptions.copy(profile = Some(x)))
        )
        .text("Cloud credentials profile to use"),
      opt[String]("region")
        .action((x, c) =>
          c.copy(globalOptions = c.globalOptions.copy(region = Some(x)))
        )
        .text("Cloud region to use"),
      opt[Unit]("no-color")
        .action((_, c) =>
          c.copy(globalOptions = c.globalOptions.copy(noColor = true))
        )
        .text("Disable colored output"),
      cmd("read")
        .text("Display parquet file content")
        .children(
          arg[String]("<file>")
            .required()
            .action((x, c) => c.copy(command = Some(ReadCommand(x))))
            .text("Path to parquet file (local, s3://, gs://, abfss://)"),
          opt[Long]("max-rows")
            .abbr("n")
            .action((x, c) => updateReadCommand(c, _.copy(maxRows = Some(x))))
            .text("Maximum number of rows to display"),
          opt[Seq[String]]("columns")
            .abbr("c")
            .action((x, c) =>
              updateReadCommand(c, _.copy(columns = Some(x.toList)))
            )
            .text("Comma-separated list of columns to display"),
          opt[String]("filter")
            .abbr("f")
            .action((x, c) => updateReadCommand(c, _.copy(filter = Some(x))))
            .text("Filter expression for rows"),
          opt[String]("format")
            .action((x, c) =>
              updateReadCommand(c, _.copy(format = parseOutputFormat(x)))
            )
            .validate(x =>
              if (
                List("table", "json", "csv", "pretty").contains(x.toLowerCase)
              ) success
              else failure(s"Invalid format: $x")
            )
            .text("Output format: table, json, csv, pretty(default: table)")
        ),
      cmd("info")
        .text("Show file metadata and schema information")
        .children(
          arg[String]("<file>")
            .required()
            .action((x, c) => c.copy(command = Some(InfoCommand(x))))
            .text("Path to parquet file"),
          opt[String]("format")
            .action((x, c) =>
              updateInfoCommand(c, _.copy(format = parseOutputFormat(x)))
            )
            .text("Output format: table, json"),
          opt[Unit]("no-schema")
            .abbr("s")
            .action((_, c) => updateInfoCommand(c, _.copy(showSchema = false)))
            .text("Don't show schema information"),
          opt[Unit]("no-metadata")
            .abbr("m")
            .action((_, c) =>
              updateInfoCommand(c, _.copy(showMetadata = false))
            )
            .text("Don't show metadata information")
        ),
      cmd("write")
        .text("Create parquet file from input data")
        .children(
          arg[String]("<output>")
            .required()
            .action((x, c) => c.copy(command = Some(WriteCommand("", x))))
            .text("Output parquet file path"),
          opt[String]("input")
            .abbr("i")
            .required()
            .action((x, c) => updateWriteCommand(c, _.copy(inputPath = x)))
            .text("Input data file path"),
          opt[String]("input-format")
            .action((x, c) => updateWriteCommand(c, _.copy(inputFormat = x)))
            .validate(x =>
              if (List("json", "csv", "tsv").contains(x.toLowerCase)) success
              else failure(s"Invalid input format: $x")
            )
            .text("Input file format: json, csv, tsv (default: json)"),
          opt[String]("compression")
            .abbr("c")
            .action((x, c) =>
              updateWriteCommand(
                c,
                _.copy(compression = parseCompressionType(x))
              )
            )
            .text(
              "Compression type: none, snappy, gzip, lzo, brotli, lz4, zstd"
            ),
          opt[String]("row-group-size")
            .action((x, c) =>
              updateWriteCommand(c, _.copy(rowGroupSize = Some(parseSize(x))))
            )
            .text("Row group size (e.g., 128MB)")
        ),
      cmd("validate")
        .text("Verify parquet file integrity")
        .children(
          arg[String]("<file>")
            .required()
            .action((x, c) => c.copy(command = Some(ValidateCommand(x))))
            .text("Path to parquet file"),
          opt[Unit]("verbose")
            .abbr("v")
            .action((_, c) => updateValidateCommand(c, _.copy(verbose = true)))
            .text("Show detailed validation information")
        ),
      cmd("convert")
        .text("Convert between parquet and other formats")
        .children(
          arg[String]("<input>")
            .required()
            .action((x, c) => c.copy(command = Some(ConvertCommand(x, ""))))
            .text("Input file path"),
          arg[String]("<output>")
            .required()
            .action((x, c) => updateConvertCommand(c, _.copy(outputPath = x)))
            .text("Output file path"),
          opt[String]("compression")
            .action((x, c) =>
              updateConvertCommand(
                c,
                _.copy(compression = parseCompressionType(x))
              )
            )
            .text("Compression type for output"),
          opt[Long]("max-rows")
            .abbr("n")
            .action((x, c) =>
              updateConvertCommand(c, _.copy(maxRows = Some(x)))
            )
            .text("Maximum number of rows to convert")
        )
    )
  }

  private def updateReadCommand(
      config: Config,
      update: ReadCommand => ReadCommand
  ): Config = {
    config.command match {
      case Some(cmd: ReadCommand) => config.copy(command = Some(update(cmd)))
      case _                      => config
    }
  }

  private def updateInfoCommand(
      config: Config,
      update: InfoCommand => InfoCommand
  ): Config = {
    config.command match {
      case Some(cmd: InfoCommand) => config.copy(command = Some(update(cmd)))
      case _                      => config
    }
  }

  private def updateWriteCommand(
      config: Config,
      update: WriteCommand => WriteCommand
  ): Config = {
    config.command match {
      case Some(cmd: WriteCommand) => config.copy(command = Some(update(cmd)))
      case _                       => config
    }
  }

  private def updateValidateCommand(
      config: Config,
      update: ValidateCommand => ValidateCommand
  ): Config = {
    config.command match {
      case Some(cmd: ValidateCommand) =>
        config.copy(command = Some(update(cmd)))
      case _ => config
    }
  }

  private def updateConvertCommand(
      config: Config,
      update: ConvertCommand => ConvertCommand
  ): Config = {
    config.command match {
      case Some(cmd: ConvertCommand) => config.copy(command = Some(update(cmd)))
      case _                         => config
    }
  }

  private def parseOutputFormat(format: String): OutputFormat = {
    format.toLowerCase match {
      case "table"  => OutputFormat.Table
      case "json"   => OutputFormat.JSON
      case "csv"    => OutputFormat.CSV
      case "pretty" => OutputFormat.Pretty
      case _        => OutputFormat.Table
    }
  }

  private def parseCompressionType(compression: String): CompressionType = {
    compression.toLowerCase match {
      case "none" | "uncompressed" => CompressionType.Uncompressed
      case "snappy"                => CompressionType.Snappy
      case "gzip" | "gz"           => CompressionType.Gzip
      case "lzo"                   => CompressionType.Lzo
      case "brotli"                => CompressionType.Brotli
      case "lz4"                   => CompressionType.Lz4
      case "zstd"                  => CompressionType.Zstd
      case _                       => CompressionType.Snappy
    }
  }

  private def parseSize(sizeStr: String): Long = {
    val units = Map(
      "B" -> 1L,
      "KB" -> 1024L,
      "MB" -> 1024L * 1024L,
      "GB" -> 1024L * 1024L * 1024L
    )

    val pattern = """(\d+)\s*(B|KB|MB|GB)""".r
    sizeStr.toUpperCase match {
      case pattern(size, unit) => size.toLong * units(unit)
      case _ =>
        throw new IllegalArgumentException(s"Invalid size format: $sizeStr")
    }
  }
}
