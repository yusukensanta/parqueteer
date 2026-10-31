package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError.toParqueteerError
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import io.github.yusukensanta.parqueteer.core.services.LocationParsing.{
  parseLocation,
  parseLocations
}
import io.github.yusukensanta.parqueteer.core.util.{Eithers, RowPipeline}

/**
 * Multi-file parquet operations that stream rows into one output: merging
 * several parquet files and parquet → parquet conversion.
 */
private[services] class MergeOperations(
    repository: ParquetRepository,
    streamWrite: StreamWriteSupport
) {
  import streamWrite.{abortOnReadError, buildExplicitSchema, handleStreamWriteResult, projectRow}

  def mergeFiles(
      inputPaths: List[String],
      outputPath: String,
      writeConfig: WriteConfig,
      schemaMode: SchemaMode,
      onProgress: (Int, Int, String) => Unit = (_, _, _) => (),
      fileParallelism: Int = 1
  ): Either[ParqueteerError, Long] =
    if inputPaths.size < 2 then
      Left(
        ParqueteerError.UnsupportedOperation(
          "merge",
          "merge requires at least two input files"
        )
      )
    else
      for {
        inputLocations <- parseLocations(inputPaths)
        schemas        <- readAllSchemas(inputLocations)
        mergedFields   <- SchemaReconciler.reconcile(schemas, inputPaths, schemaMode)
        outputLocation <- parseLocation(outputPath)
        count          <- streamMerge(
          inputLocations,
          inputPaths,
          mergedFields,
          outputLocation,
          writeConfig,
          onProgress,
          fileParallelism
        )
      } yield count

  def checkSchemaCompatibility(
      paths: List[String],
      schemaMode: SchemaMode
  ): Either[ParqueteerError, Unit] =
    for {
      locations <- parseLocations(paths)
      schemas   <- readAllSchemas(locations)
      _         <- SchemaReconciler.reconcile(schemas, paths, schemaMode)
    } yield ()

  /**
   * Read the field-summary schema for each input file, preserving order. Stops
   * on the first read failure.
   */
  private def readAllSchemas(
      locations: Vector[StorageLocation]
  ): Either[ParqueteerError, Vector[List[FieldSummary]]] =
    Eithers.traverse(locations)(loc =>
      repository.readSchemaFields(ParquetFile(loc)).toParqueteerError
    )

  /**
   * Stream rows from each input file into a single output writer. On the first
   * read failure throws a sentinel exception through the writer so the partial
   * output is deleted before returning the real error. Returns the count of
   * rows written on success.
   */
  private def streamMerge(
      inputLocations: Vector[StorageLocation],
      inputPaths: List[String],
      mergedFields: List[FieldSummary],
      outputLocation: StorageLocation,
      writeConfig: WriteConfig,
      onProgress: (Int, Int, String) => Unit,
      fileParallelism: Int
  ): Either[ParqueteerError, Long] = {
    val nestedFields = mergedFields.filter(_.isNested)
    if nestedFields.nonEmpty then
      Left(
        ParqueteerError.UnsupportedOperation(
          "merge",
          s"Cannot merge files containing nested columns: ${nestedFields.map(_.name).mkString(", ")}. " +
            "Flatten STRUCT/MAP/LIST columns before merging."
        )
      )
    else {
      // compression controlled by WriteConfig, not ColumnInfo
      val explicitSchema                = buildExplicitSchema(mergedFields, compressionCodec = "")
      val fieldNames                    = mergedFields.map(_.name).toArray
      val nameToIndex: Map[String, Int] = fieldNames.zipWithIndex.toMap
      val writeResult                   = repository
        .writeContentStream(outputLocation, explicitSchema, writeConfig) { write =>
          // Up to `fileParallelism` input files are fetched concurrently
          // ahead of `write`, each buffered through a bounded queue — see
          // RowPipeline. Output row order still matches inputLocations order
          // regardless of which file's cloud fetch happens to finish first.
          val pipelineResult = RowPipeline.run(
            inputLocations.zipWithIndex.toList,
            fileParallelism
          ) { case ((loc, i), sink) =>
            onProgress(i + 1, inputLocations.size, inputPaths(i))
            repository
              .streamContent(ParquetFile(loc), ReadConfig())(row =>
                sink(projectRow(row, fieldNames, nameToIndex))
              )
              .toEither
          }(write)
          abortOnReadError(pipelineResult.toTry)
        }

      handleStreamWriteResult(outputLocation, writeResult)
    }
  }

  /**
   * Stream parquet → parquet conversion without loading the entire file into
   * memory. Reads the source schema, opens a streaming writer for the output,
   * and feeds rows one at a time. Deletes partial output on failure.
   */
  def convertParquetFile(
      inputPath: String,
      outputPath: String,
      conversionConfig: ConversionConfig
  ): Either[ParqueteerError, Long] =
    for {
      inputLocation  <- parseLocation(inputPath)
      outputLocation <- parseLocation(outputPath)
      schemaFields   <- repository
        .readSchemaFields(ParquetFile(inputLocation))
        .toParqueteerError
      explicitSchema = buildExplicitSchema(
        schemaFields,
        conversionConfig.writeConfig.compressionType.codecName
      )
      writeResult = repository.writeContentStream(
        outputLocation,
        explicitSchema,
        conversionConfig.writeConfig
      ) { write =>
        val readResult = repository
          .streamContent(
            ParquetFile(inputLocation),
            ReadConfig(maxRows = conversionConfig.maxRows)
          )(write)
        abortOnReadError(readResult)
      }
      count <- handleStreamWriteResult(outputLocation, writeResult)
    } yield count
}
