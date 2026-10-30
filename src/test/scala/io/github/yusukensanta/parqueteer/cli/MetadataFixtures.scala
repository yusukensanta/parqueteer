package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import java.time.Instant

object MetadataFixtures {

  val report: ParquetMetadataReport = ParquetMetadataReport(
    file = FileDetail(
      fileSize = 2048L,
      modifiedAt = Some(Instant.parse("2024-01-01T00:00:00Z")),
      version = "1.0",
      createdBy = Some("parquet-mr version 1.15.2"),
      rowCount = 100L,
      rowGroupCount = 1,
      columnCount = 2
    ),
    keyValueMetadata = List("writer.model.name" -> "example", "z.key" -> "z-value"),
    messageSchema = "message schema {\n  required int64 id;\n  optional binary name (STRING);\n}",
    columns = List(
      ColumnSchemaDetail("id", "INT64", None, "REQUIRED", 0, 0, None, Some(1)),
      ColumnSchemaDetail("name", "BINARY", Some("STRING"), "OPTIONAL", 1, 0, None, None)
    ),
    rowGroups = List(
      RowGroupDetail(
        index = 0,
        rowCount = 100L,
        startOffset = 4L,
        compressedBytes = 1000L,
        uncompressedBytes = 2000L,
        columns = List(
          ColumnChunkDetail(
            path = "id",
            codec = "SNAPPY",
            encodings = List("PLAIN", "RLE"),
            valueCount = 100L,
            compressedBytes = 400L,
            uncompressedBytes = 800L,
            dataPageOffset = 4L,
            dictionaryPageOffset = None,
            nullCount = Some(0L),
            minValue = Some("1"),
            maxValue = Some("100"),
            hasBloomFilter = false,
            hasColumnIndex = true,
            hasOffsetIndex = true
          ),
          ColumnChunkDetail(
            path = "name",
            codec = "SNAPPY",
            encodings = List("PLAIN", "RLE", "RLE_DICTIONARY"),
            valueCount = 100L,
            compressedBytes = 600L,
            uncompressedBytes = 1200L,
            dataPageOffset = 404L,
            dictionaryPageOffset = Some(404L),
            nullCount = None,
            minValue = None,
            maxValue = None,
            hasBloomFilter = true,
            hasColumnIndex = false,
            hasOffsetIndex = false
          )
        )
      )
    )
  )
}
