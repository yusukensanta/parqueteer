package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.services.ParquetService
import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.formatters.TableFormatter
import CommandSupport.*
import MultiFileReport.*

/** Handlers for the metadata commands: info, validate, schema, schema diff, stats, count. */
private[cli] object InspectCommands {

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
      cmd: InfoCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.{filePath, format, verbose}
    service.getFileInfo(filePath) match {
      case Right(file) =>
        if !globalOptions.quiet then println(formatInfoText(file, format, verbose))
        0
      case Left(error) =>
        reportError("Failed to get file info", globalOptions)(error)
    }
  }

  private[cli] def executeInfoMulti(
      service: ParquetService,
      paths: List[String],
      cmd: InfoCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.{format, verbose}
    runMultiFileReport(paths, format, globalOptions) { path =>
      service.getFileInfo(path).map(file => (formatInfoText(file, format, verbose), true))
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
      cmd: ValidateCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.{filePath, verbose, deep}
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
  }

  private[cli] def executeValidateMulti(
      service: ParquetService,
      paths: List[String],
      cmd: ValidateCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.{verbose, deep}
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

  private[cli] def formatStatsText(stats: FileStats, format: OutputFormat): String =
    format match {
      case OutputFormat.JSON => CliOutputFormatter.formatStatsJson(stats)
      case _                 => CliOutputFormatter.formatStatsTable(stats)
    }

  private[cli] def executeStats(
      service: ParquetService,
      cmd: StatsCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.{filePath, format}
    service.getStats(filePath) match {
      case Right(stats) =>
        if !globalOptions.quiet then println(formatStatsText(stats, format))
        0
      case Left(error) =>
        reportError("Failed to get stats", globalOptions)(error)
    }
  }

  private[cli] def executeStatsMulti(
      service: ParquetService,
      paths: List[String],
      cmd: StatsCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.format
    runMultiFileReport(paths, format, globalOptions) { path =>
      service.getStats(path).map(stats => (formatStatsText(stats, format), true))
    }
  }

  private[cli] def formatCountText(count: Long, format: OutputFormat): String =
    format match {
      case OutputFormat.JSON => CliOutputFormatter.formatCountJson(count)
      case _                 => count.toString
    }

  private[cli] def executeCount(
      service: ParquetService,
      cmd: CountCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.{filePath, format}
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
  }

  private[cli] def executeCountMulti(
      service: ParquetService,
      paths: List[String],
      cmd: CountCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.format
    runMultiFileReport(paths, format, globalOptions) { path =>
      service.getFileInfo(path).map { file =>
        val count = file.schema.fold(0L)(_.totalRowCount)
        (formatCountText(count, format), true)
      }
    }
  }
}
