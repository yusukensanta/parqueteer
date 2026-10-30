package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import io.circe.Json

/** Renders a [[ParquetMetadataReport]] as a human-readable table or as JSON. */
private[cli] object MetadataFormatter {

  // Writers such as Spark and Arrow embed the whole serialized schema in a
  // single key-value entry (often several KB, or base64 in ARROW:schema), so
  // the table view shortens values. JSON output always keeps them intact.
  private val MaxKeyValueDisplayLength = 80

  /**
   * Shortens a key-value metadata value for the table view. Line breaks and
   * runs of whitespace are collapsed to single spaces so each entry stays on
   * one line. Values longer than `maxLen` are cut and suffixed with an
   * ellipsis and the original length, e.g. `{"type":"struct"… (4213 chars)`.
   */
  private[cli] def truncateKeyValue(value: String, maxLen: Int): String = {
    val flat = value.replaceAll("\\s+", " ").trim
    if flat.length <= maxLen then flat
    else s"${flat.take(maxLen)}… (${value.length} chars)"
  }

  def formatJson(report: ParquetMetadataReport): String = {
    def optLong(o: Option[Long])  = o.fold(Json.Null)(Json.fromLong)
    def optStr(o: Option[String]) = o.fold(Json.Null)(Json.fromString)
    def optInt(o: Option[Int])    = o.fold(Json.Null)(Json.fromInt)
    def strings(xs: List[String]) = Json.fromValues(xs.map(Json.fromString))
    val f                         = report.file

    Json
      .obj(
        "file" -> Json.obj(
          "fileSize"      -> Json.fromLong(f.fileSize),
          "modifiedAt"    -> optStr(f.modifiedAt.map(_.toString)),
          "version"       -> Json.fromString(f.version),
          "createdBy"     -> optStr(f.createdBy),
          "rowCount"      -> Json.fromLong(f.rowCount),
          "rowGroupCount" -> Json.fromInt(f.rowGroupCount),
          "columnCount"   -> Json.fromInt(f.columnCount)
        ),
        "keyValueMetadata" -> Json.fromFields(
          report.keyValueMetadata.map { case (k, v) => k -> Json.fromString(v) }
        ),
        "messageSchema" -> Json.fromString(report.messageSchema),
        "columns" -> Json.fromValues(report.columns.map { c =>
          Json.obj(
            "path"               -> Json.fromString(c.path),
            "physicalType"       -> Json.fromString(c.physicalType),
            "logicalType"        -> optStr(c.logicalType),
            "repetition"         -> Json.fromString(c.repetition),
            "maxDefinitionLevel" -> Json.fromInt(c.maxDefinitionLevel),
            "maxRepetitionLevel" -> Json.fromInt(c.maxRepetitionLevel),
            "typeLength"         -> optInt(c.typeLength),
            "fieldId"            -> optInt(c.fieldId)
          )
        }),
        "rowGroups" -> Json.fromValues(report.rowGroups.map { rg =>
          Json.obj(
            "index"             -> Json.fromInt(rg.index),
            "rowCount"          -> Json.fromLong(rg.rowCount),
            "startOffset"       -> Json.fromLong(rg.startOffset),
            "compressedBytes"   -> Json.fromLong(rg.compressedBytes),
            "uncompressedBytes" -> Json.fromLong(rg.uncompressedBytes),
            "columns" -> Json.fromValues(rg.columns.map { c =>
              Json.obj(
                "path"                 -> Json.fromString(c.path),
                "codec"                -> Json.fromString(c.codec),
                "encodings"            -> strings(c.encodings),
                "valueCount"           -> Json.fromLong(c.valueCount),
                "compressedBytes"      -> Json.fromLong(c.compressedBytes),
                "uncompressedBytes"    -> Json.fromLong(c.uncompressedBytes),
                "dataPageOffset"       -> Json.fromLong(c.dataPageOffset),
                "dictionaryPageOffset" -> optLong(c.dictionaryPageOffset),
                "nullCount"            -> optLong(c.nullCount),
                "minValue"             -> optStr(c.minValue),
                "maxValue"             -> optStr(c.maxValue),
                "hasBloomFilter"       -> Json.fromBoolean(c.hasBloomFilter),
                "hasColumnIndex"       -> Json.fromBoolean(c.hasColumnIndex),
                "hasOffsetIndex"       -> Json.fromBoolean(c.hasOffsetIndex)
              )
            })
          )
        })
      )
      .spaces2
  }

  def formatTable(report: ParquetMetadataReport): String = {
    val sb = new StringBuilder
    val f  = report.file

    sb.append("File Metadata\n=============\n")
    sb.append(
      s"File Size:       ${CliOutputFormatter.formatBytesForDisplay(f.fileSize)} (${f.fileSize} bytes)\n"
    )
    f.modifiedAt.foreach(t => sb.append(s"Modified:        $t\n"))
    sb.append(s"Format Version:  ${f.version}\n")
    sb.append(s"Created By:      ${f.createdBy.getOrElse("n/a")}\n")
    sb.append(s"Rows:            ${f.rowCount}\n")
    sb.append(s"Row Groups:      ${f.rowGroupCount}\n")
    sb.append(s"Columns:         ${f.columnCount}\n")

    sb.append("\nKey-Value Metadata\n------------------\n")
    if report.keyValueMetadata.isEmpty then sb.append("(none)\n")
    else
      report.keyValueMetadata.foreach { case (k, v) =>
        sb.append(s"$k = ${truncateKeyValue(v, MaxKeyValueDisplayLength)}\n")
      }

    sb.append("\nMessage Schema\n--------------\n")
    sb.append(report.messageSchema.stripTrailing()).append("\n")

    sb.append("\nColumn Schema\n-------------\n")
    val schemaHeader =
      f"${"Column"}%-30s ${"Physical"}%-22s ${"Logical"}%-28s ${"Repetition"}%-10s ${"Def"}%3s ${"Rep"}%3s ${"Len"}%4s ${"Id"}%4s"
    sb.append(schemaHeader + "\n").append("-" * schemaHeader.length + "\n")
    report.columns.foreach { c =>
      sb.append(
        f"${c.path}%-30s ${c.physicalType}%-22s ${c.logicalType
            .getOrElse("-")}%-28s ${c.repetition}%-10s ${c.maxDefinitionLevel}%3d ${c.maxRepetitionLevel}%3d ${c.typeLength
            .fold("-")(_.toString)}%4s ${c.fieldId.fold("-")(_.toString)}%4s\n"
      )
    }

    report.rowGroups.foreach { rg =>
      sb.append(
        s"\nRow Group ${rg.index}: ${rg.rowCount} rows, " +
          s"${CliOutputFormatter.formatBytesForDisplay(rg.compressedBytes)} compressed, " +
          s"${CliOutputFormatter.formatBytesForDisplay(rg.uncompressedBytes)} uncompressed, " +
          s"offset ${rg.startOffset}\n"
      )
      val header =
        f"${"Column"}%-30s ${"Codec"}%-12s ${"Encodings"}%-34s ${"Values"}%10s ${"Compressed"}%12s ${"Uncompressed"}%12s ${"Nulls"}%8s ${"Min"}%-16s ${"Max"}%-16s ${"Flags"}%-5s"
      sb.append(header + "\n").append("-" * header.length + "\n")
      rg.columns.foreach { c =>
        val flags =
          (if c.hasDictionary then "D" else "-") +
            (if c.hasBloomFilter then "B" else "-") +
            (if c.hasColumnIndex then "C" else "-") +
            (if c.hasOffsetIndex then "O" else "-")
        sb.append(
          f"${c.path}%-30s ${c.codec}%-12s ${c.encodings.mkString(",")}%-34s ${c.valueCount}%10d ${CliOutputFormatter
              .formatBytesForDisplay(c.compressedBytes)}%12s ${CliOutputFormatter
              .formatBytesForDisplay(c.uncompressedBytes)}%12s ${c.nullCount.fold("n/a")(_.toString)}%8s ${c.minValue
              .getOrElse("n/a")}%-16s ${c.maxValue.getOrElse("n/a")}%-16s $flags%-5s\n"
        )
      }
    }
    sb.append("\nFlags: D=dictionary page, B=bloom filter, C=column index, O=offset index")
    sb.toString
  }
}
