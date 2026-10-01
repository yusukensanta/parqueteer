package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.services.ParquetService
import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.util.FileExtension
import CommandSupport.*
import StreamingOutput.*

/** Handlers for the commands that produce a file: write, convert, merge. */
private[cli] object WriteCommands {

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
    val writeConfig = WriteConfig(
      compressionType = compression,
      rowGroupSize = rowGroupSize.getOrElse(WriteConfig.DefaultRowGroupSize)
    )
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
              println(s"Dry run: would write $outputPath")
              println(s"  Input:       $inputPath (${inputFormat.name})")
              println(s"  Columns:     ${columns.mkString(", ")}")
              println(s"  Compression: ${compression.toString.toLowerCase}")
              0
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
    val writeConfig = WriteConfig(
      compressionType = compression,
      rowGroupSize = rowGroupSize.getOrElse(WriteConfig.DefaultRowGroupSize)
    )
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
        // Each closure shares `remaining` by reference, so the row budget
        // decrements across files as runWithDeferredBeginMulti invokes them
        // in order — the same running --limit semantics
        // ParquetService.streamAllFiles uses for multi-file `read`.
        var remaining = maxRows
        val reads: List[(Map[String, CellValue] => Unit) => Either[ParqueteerError, Long]] =
          inputPaths.map { path => process =>
            if remaining.contains(0L) then Right(0L)
            else
              service.streamRead(path, ReadConfig(maxRows = remaining))(process).map { n =>
                remaining = remaining.map(r => r - n)
                n
              }
          }
        // A running --limit budget is shared (by mutable closure) across
        // `reads` in file order, so honoring it correctly requires each file
        // to finish before the next starts — only prefetch ahead when
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

  private[cli] def executeMergeDryRun(
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
}
