package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.services.ParquetService
import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.util.{FileExtension, RowBudget}
import CommandSupport.*
import DryRunReport.{detail, field}
import StreamingOutput.*

/** Handlers for the commands that produce a file: write, convert, merge. */
private[cli] object WriteCommands {

  // The one place CLI writer flags become a WriteConfig, so write, convert
  // and merge can't drift on defaults.
  private[cli] def writeConfigFor(
      compression: CompressionType,
      rowGroupSize: Option[Long],
      writer: WriterOptions
  ): WriteConfig = {
    val defaults = WriteConfig()
    WriteConfig(
      compressionType = compression,
      rowGroupSize = rowGroupSize.getOrElse(WriteConfig.DefaultRowGroupSize),
      pageSize = writer.pageSize.getOrElse(defaults.pageSize),
      enableDictionary = writer.dictionary
    )
  }

  // CLI spelling of a codec in dry-run output (e.g. "snappy", "uncompressed").
  private def codec(compression: CompressionType): String = compression.toString.toLowerCase

  // Shared by performConvert/executeConvertMulti: parquet → text conversions
  // only ever target one of these three formats.
  private[cli] def textOutputFormatFor(ext: String): OutputFormat = ext match {
    case "json"   => OutputFormat.JSON
    case "ndjson" => OutputFormat.NDJSON
    case _        => OutputFormat.CSV
  }

  private[cli] def executeWrite(
      service: ParquetService,
      cmd: WriteCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.{inputPath, outputPath, inputFormat, compression, rowGroupSize, dryRun}
    val writeConfig = writeConfigFor(compression, rowGroupSize, cmd.writer)
    checkOutputWritable(outputPath) match {
      case Left(err) =>
        reportError("Failed to write file", globalOptions)(err)
      case Right(_) =>
        if dryRun then {
          service.readDataFile(inputPath, inputFormat, maxRows = Some(1L)) match {
            case Left(error) =>
              reportError("Failed to read input file", globalOptions)(error)
            case Right(rows) =>
              val columns = rows.headOption.map(_.keys.toList).getOrElse(Nil)
              DryRunReport(
                s"write $outputPath",
                List(
                  field("Input", s"$inputPath (${inputFormat.name})"),
                  field("Columns", columns.mkString(", ")),
                  field("Compression", codec(compression))
                )
              ).print()
          }
        } else {
          service.streamWriteDataFile(inputPath, inputFormat, outputPath, writeConfig) match {
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
      inputPaths: List[String],
      cmd: WriteCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.{outputPath, inputFormat, compression, rowGroupSize, schemaMode, dryRun}
    val writeConfig = writeConfigFor(compression, rowGroupSize, cmd.writer)
    checkOutputWritable(outputPath) match {
      case Left(err) => reportError("Failed to write file", globalOptions)(err)
      case Right(_) =>
        if dryRun then {
          DryRunReport(
            s"write $outputPath",
            field("Inputs", s"${inputPaths.size} files matched") ::
              inputPaths.map(p => detail(s"  - $p")) :::
              List(field("Schema mode", schemaMode), field("Compression", codec(compression)))
          ).print()
        } else {
          val onProgress: (Int, Int, String) => Unit = (i, n, path) =>
            if !globalOptions.quiet then System.err.println(s"[$i/$n] Writing: $path")
          service.writeMultiRawToParquet(
            inputPaths,
            inputFormat,
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

  private[cli] def executeConvert(
      service: ParquetService,
      cmd: ConvertCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.{inputPath, outputPath, compression, maxRows, dryRun}
    val conversionConfig = ConversionConfig(
      writeConfig = writeConfigFor(compression, None, cmd.writer),
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

  private[cli] def runConvertDryRun(
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
          val sizeLine = file.metadata.toList.map(m =>
            field("File size", CliOutputFormatter.formatBytesForDisplay(m.fileSize))
          )
          val schemaLines = file.schema.toList.flatMap { s =>
            List(field("Rows", s.totalRowCount), field("Columns", s.columns.size)) ++
              s.columns.headOption.map(c =>
                field("Compression", s"${c.compressionType.toLowerCase} → ${codec(compression)}")
              )
          }
          DryRunReport(
            s"convert $inputPath → $outputPath",
            field("Input", inputPath) :: sizeLine ::: schemaLines
          ).print()
      }
    else
      DryRunReport(
        s"convert $inputPath → $outputPath",
        List(
          field("Input format", inputExt),
          field("Compression", s"${codec(compression)} (output)")
        )
      ).print()
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
        val outFormat = textOutputFormatFor(ext)
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
      case (InputFormat.FromName(inFormat), "parquet") =>
        service
          .streamWriteDataFile(
            inputPath,
            inFormat,
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

  private[cli] def convertParquetStreamed(
      service: ParquetService,
      inputPath: String,
      outputPath: String,
      outFormat: OutputFormat,
      conversionConfig: ConversionConfig
  ): Either[ParqueteerError, Unit] =
    withSafeTextOutput(outputPath, outFormat) { writer =>
      runWithDeferredBegin(
        writer,
        service.streamRead(inputPath, ReadConfig(maxRows = conversionConfig.maxRows))
      )
    }.map(_ => ())

  private[cli] def executeConvertMulti(
      service: ParquetService,
      inputPaths: List[String],
      cmd: ConvertCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.{outputPath, compression, maxRows, schemaMode, dryRun}
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
          DryRunReport(
            s"convert ${inputPaths.size} files → $outputPath",
            List(
              field("Input format", inputExt),
              field("Schema mode", schemaMode),
              field("Compression", s"${codec(compression)} (output)")
            )
          ).print()
        } else {
          val writeConfig = writeConfigFor(compression, None, cmd.writer)
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
              val outFormat = textOutputFormatFor(ext)
              convertParquetMultiStreamed(
                service,
                inputPaths,
                outputPath,
                outFormat,
                schemaMode,
                maxRows,
                globalOptions.fileParallelism
              )
            case (InputFormat.FromName(inFormat), "parquet") =>
              service.writeMultiRawToParquet(
                inputPaths,
                inFormat,
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

  private[cli] def convertParquetMultiStreamed(
      service: ParquetService,
      inputPaths: List[String],
      outputPath: String,
      outFormat: OutputFormat,
      schemaMode: SchemaMode,
      maxRows: Option[Long],
      fileParallelism: Int
  ): Either[ParqueteerError, Long] =
    service.checkSchemaCompatibility(inputPaths, schemaMode).flatMap { _ =>
      withSafeTextOutput(outputPath, outFormat) { writer =>
        // Every closure shares one budget, so --limit applies to the
        // concatenated output, the same semantics ParquetService.streamAllFiles
        // uses for multi-file `read`.
        val budget = RowBudget(maxRows)
        val reads: List[(Map[String, CellValue] => Unit) => Either[ParqueteerError, Long]] =
          inputPaths.map { path => process =>
            if budget.isExhausted then Right(0L)
            else
              service.streamRead(path, ReadConfig(maxRows = budget.nextLimit))(process).map { n =>
                budget.consume(n)
                n
              }
          }
        // The budget is consumed in file order, so honoring it requires each
        // file to finish before the next starts: only prefetch ahead when
        // there's no limit to honor.
        val effectiveParallelism = if maxRows.isDefined then 1 else fileParallelism
        runWithDeferredBeginMulti(writer, reads, effectiveParallelism)
      }
    }

  private[cli] def executeMerge(
      service: ParquetService,
      cmd: MergeCommand,
      globalOptions: GlobalOptions
  ): Int = {
    import cmd.{inputPaths, outputPath, compression, schemaMode, dryRun}
    val writeConfig = writeConfigFor(compression, None, cmd.writer)
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
            schemaMode
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

  private[cli] def executeMergeDryRun(
      service: ParquetService,
      inputPaths: List[String],
      outputPath: String,
      compression: CompressionType,
      schemaMode: SchemaMode
  ): Int = {
    val perFile = inputPaths.zipWithIndex.map { (path, i) =>
      val summary = service.getFileInfo(path) match {
        case Right(file) =>
          val rows = file.schema.map(_.totalRowCount).getOrElse(0L)
          val cols = file.schema.map(_.columns.size).getOrElse(0)
          s"($rows rows, $cols columns)"
        case Left(error) => s"— error: ${error.userMessage}"
      }
      detail(s"[${i + 1}/${inputPaths.size}] $path $summary")
    }
    DryRunReport(
      s"merge ${inputPaths.size} files → $outputPath",
      field("Schema mode", schemaMode) :: field("Compression", codec(compression)) :: perFile
    ).print()
  }
}
