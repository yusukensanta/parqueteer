package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.util.ByteFormatter
import io.circe.Json
import io.circe.syntax.*
import io.github.yusukensanta.parqueteer.core.formatters.JSONFormatter.given

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
        "columns"       -> Json.fromValues(report.columns.map { c =>
          Json.obj(
            "path"               -> Json.fromString(c.path),
            "physicalType"       -> Json.fromString(c.physicalType),
            "logicalType"        -> optStr(c.logicalType),
            "repetition"         -> Json.fromString(c.repetition),
            "maxDefinitionLevel" -> Json.fromInt(c.maxDefinitionLevel),
            "maxRepetitionLevel" -> Json.fromInt(c.maxRepetitionLevel),
            "typeLength"         -> optInt(c.typeLength),
            "fieldId"            -> optInt(c.fieldId),
            "groups"             -> c.groups.asJson
          )
        }),
        "schemaTree" -> report.schemaTree.fold(Json.Null)(_.asJson),
        "extended"   -> report.extended.fold(Json.Null)(_.asJson),
        "rowGroups"  -> Json.fromValues(report.rowGroups.map { rg =>
          Json.obj(
            "index"             -> Json.fromInt(rg.index),
            "rowCount"          -> Json.fromLong(rg.rowCount),
            "startOffset"       -> Json.fromLong(rg.startOffset),
            "compressedBytes"   -> Json.fromLong(rg.compressedBytes),
            "uncompressedBytes" -> Json.fromLong(rg.uncompressedBytes),
            "extended"          -> rg.extended.fold(Json.Null)(_.asJson),
            "columns"           -> Json.fromValues(rg.columns.map { c =>
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
                "hasOffsetIndex"       -> Json.fromBoolean(c.hasOffsetIndex),
                "extended"             -> c.extended.fold(Json.Null)(_.asJson),
                "pageIndex"            -> c.pageIndex.fold(Json.Null)(_.asJson),
                "pages"                -> c.pages.fold(Json.Null)(_.asJson)
              )
            })
          )
        })
      )
      .spaces2
  }

  private case class Col(title: String, width: Int, rightAligned: Boolean = false)

  // Fixed-width table: header, dashed rule, rows. Trailing padding is stripped
  // so the last column doesn't leave whitespace at the end of each line.
  private def renderTable(cols: List[Col], rows: List[List[String]]): String = {
    def line(cells: List[String]): String =
      cols
        .zip(cells)
        .map { case (c, v) => s"%${if c.rightAligned then "" else "-"}${c.width}s".format(v) }
        .mkString(" ")
        .stripTrailing()
    val header = line(cols.map(_.title))
    (header :: "-" * header.length :: rows.map(line)).mkString("\n")
  }

  private def bytes(n: Long): String = ByteFormatter.format(n)

  private def flags(c: ColumnChunkDetail): String =
    List(
      c.hasDictionary  -> "D",
      c.hasBloomFilter -> "B",
      c.hasColumnIndex -> "C",
      c.hasOffsetIndex -> "O"
    ).map { case (present, letter) => if present then letter else "-" }.mkString

  private val columnSchemaCols = List(
    Col("Column", 30),
    Col("Physical", 22),
    Col("Logical", 28),
    Col("Repetition", 10),
    Col("Def", 3, rightAligned = true),
    Col("Rep", 3, rightAligned = true),
    Col("Len", 4, rightAligned = true),
    Col("Id", 4, rightAligned = true),
    Col("Groups", 30)
  )

  private val chunkCols = List(
    Col("Column", 30),
    Col("Codec", 12),
    Col("Encodings", 34),
    Col("Values", 10, rightAligned = true),
    Col("Compressed", 12, rightAligned = true),
    Col("Uncompressed", 12, rightAligned = true),
    Col("Nulls", 8, rightAligned = true),
    Col("Min", 16),
    Col("Max", 16),
    Col("Flags", 5)
  )

  private val extendedChunkCols = List(
    Col("Column", 30),
    Col("Dict pages", 22),
    Col("Data pages", 28),
    Col("V2", 3),
    Col("Distinct", 9, rightAligned = true),
    Col("Exact", 5),
    Col("Bloom", 14),
    Col("ColIdx", 14),
    Col("OffIdx", 14),
    Col("Unencoded", 10, rightAligned = true)
  )

  private def region(r: Option[FileRegion]): String =
    r.fold("-")(x => s"@${x.offset}+${x.length.fold("?")(_.toString)}")

  private def pageCounts(xs: List[EncodingPageCount]): String =
    if xs.isEmpty then "-" else xs.map(e => s"${e.encoding}:${e.pages}").mkString(",")

  private def triState(b: Option[Boolean]): String = b.fold("-")(v => if v then "Y" else "N")

  private def extendedFileSection(e: ExtendedFileDetail): List[String] =
    List(
      "Extended File Detail",
      "--------------------",
      s"Column Orders:    ${
          if e.columnOrders.isEmpty then "(none)" else e.columnOrders.distinct.mkString(", ")
        }",
      s"Encryption:       ${e.encryptionAlgorithm.getOrElse("none")}",
      s"Footer Signing:   ${if e.hasFooterSigningKey then "key metadata present" else "none"}"
    )

  private def extendedRowGroupSection(rg: RowGroupDetail): List[String] =
    rg.extended.toList.flatMap { e =>
      val sorting =
        if e.sortingColumns.isEmpty then "(none)"
        else
          e.sortingColumns
            .map(sc =>
              s"${sc.column} ${if sc.descending then "DESC" else "ASC"} NULLS ${
                  if sc.nullsFirst then "FIRST" else "LAST"
                }"
            )
            .mkString(", ")
      val header =
        s"Row Group ${rg.index} (extended): ordinal ${e.ordinal.fold("n/a")(_.toString)}, " +
          s"file offset ${e.fileOffset.fold("n/a")(_.toString)}, first row index ${e.rowIndexOffset
              .fold("n/a")(_.toString)}, sorted by $sorting"
      val rows = rg.columns.flatMap(c => c.extended.map(x => c -> x)).map { case (c, x) =>
        List(
          c.path,
          pageCounts(x.dictionaryPages),
          pageCounts(x.dataPages),
          triState(x.usesV2Pages),
          x.distinctCount.fold("-")(_.toString),
          s"${triState(x.isMinExact)}/${triState(x.isMaxExact)}",
          region(x.bloomFilter),
          region(x.columnIndex),
          region(x.offsetIndex),
          x.sizeStatistics.flatMap(_.unencodedByteArrayDataBytes).fold("-")(n => bytes(n))
        )
      }
      List(header, renderTable(extendedChunkCols, rows))
    }

  private val pageIndexCols = List(
    Col("Page", 5, rightAligned = true),
    Col("Offset", 10, rightAligned = true),
    Col("Bytes", 8, rightAligned = true),
    Col("First row", 10, rightAligned = true),
    Col("Null page", 9),
    Col("Nulls", 8, rightAligned = true),
    Col("Min", 18),
    Col("Max", 18)
  )

  private val pageHeaderCols = List(
    Col("Type", 14),
    Col("Offset", 10, rightAligned = true),
    Col("Header", 6, rightAligned = true),
    Col("Compressed", 10, rightAligned = true),
    Col("Uncompressed", 12, rightAligned = true),
    Col("Values", 8, rightAligned = true),
    Col("Rows", 8, rightAligned = true),
    Col("Nulls", 8, rightAligned = true),
    Col("Encoding", 18),
    Col("Stats", 5),
    Col("CRC", 3)
  )

  private def opt[A](o: Option[A]): String = o.fold("-")(_.toString)

  private def pageDetailSections(rg: RowGroupDetail): List[List[String]] =
    rg.columns.flatMap { c =>
      val index = c.pageIndex.toList.map { pi =>
        List(
          s"Row Group ${rg.index} / ${c.path}: column & offset index (boundary order ${pi.boundaryOrder
              .getOrElse("n/a")})",
          renderTable(
            pageIndexCols,
            pi.pages.map { p =>
              List(
                p.page.toString,
                opt(p.offset),
                opt(p.compressedBytes),
                opt(p.firstRowIndex),
                p.isNullPage.fold("-")(v => if v then "Y" else "N"),
                opt(p.nullCount),
                p.minValue.getOrElse("-"),
                p.maxValue.getOrElse("-")
              )
            }
          )
        )
      }
      val headers = c.pages.toList.map { ps =>
        List(
          s"Row Group ${rg.index} / ${c.path}: ${ps.size} page headers",
          renderTable(
            pageHeaderCols,
            ps.map { p =>
              List(
                p.pageType,
                p.offset.toString,
                p.headerBytes.toString,
                p.compressedBytes.toString,
                p.uncompressedBytes.toString,
                opt(p.valueCount),
                opt(p.rowCount),
                opt(p.nullCount),
                p.encoding.getOrElse("-"),
                if p.hasStatistics then "Y" else "N",
                if p.hasCrc then "Y" else "N"
              )
            }
          )
        )
      }
      index ++ headers
    }

  def formatTable(report: ParquetMetadataReport): String = {
    val f = report.file

    val fileSection = List(
      "File Metadata",
      "=============",
      s"File Size:       ${bytes(f.fileSize)} (${f.fileSize} bytes)"
    ) ++ f.modifiedAt.map(t => s"Modified:        $t") ++ List(
      s"Format Version:  ${f.version}",
      s"Created By:      ${f.createdBy.getOrElse("n/a")}",
      s"Rows:            ${f.rowCount}",
      s"Row Groups:      ${f.rowGroupCount}",
      s"Columns:         ${f.columnCount}"
    )

    val keyValueSection = List("Key-Value Metadata", "------------------") ++
      (if report.keyValueMetadata.isEmpty then List("(none)")
       else
         report.keyValueMetadata.map { case (k, v) =>
           s"$k = ${truncateKeyValue(v, MaxKeyValueDisplayLength)}"
         })

    val schemaSection =
      List("Message Schema", "--------------", report.messageSchema.stripTrailing())

    val columnSection = List(
      "Column Schema",
      "-------------",
      renderTable(
        columnSchemaCols,
        report.columns.map { c =>
          List(
            c.path,
            c.physicalType,
            c.logicalType.getOrElse("-"),
            c.repetition,
            c.maxDefinitionLevel.toString,
            c.maxRepetitionLevel.toString,
            c.typeLength.fold("-")(_.toString),
            c.fieldId.fold("-")(_.toString),
            c.groups.flatMap(_.annotation).mkString(", ") match {
              case "" => "-"
              case s  => s
            }
          )
        }
      )
    )

    val rowGroupSections = report.rowGroups.map { rg =>
      List(
        s"Row Group ${rg.index}: ${rg.rowCount} rows, ${bytes(rg.compressedBytes)} compressed, " +
          s"${bytes(rg.uncompressedBytes)} uncompressed, offset ${rg.startOffset}",
        renderTable(
          chunkCols,
          rg.columns.map { c =>
            List(
              c.path,
              c.codec,
              c.encodings.mkString(","),
              c.valueCount.toString,
              bytes(c.compressedBytes),
              bytes(c.uncompressedBytes),
              c.nullCount.fold("n/a")(_.toString),
              c.minValue.getOrElse("n/a"),
              c.maxValue.getOrElse("n/a"),
              flags(c)
            )
          }
        )
      )
    }

    val legend = List("Flags: D=dictionary page, B=bloom filter, C=column index, O=offset index")

    val extendedSections =
      report.extended.map(extendedFileSection).toList ++
        report.rowGroups.map(extendedRowGroupSection).filter(_.nonEmpty)

    val pageSections = report.rowGroups.flatMap(pageDetailSections)

    (List(fileSection, keyValueSection, schemaSection, columnSection) ++ rowGroupSections ++
      extendedSections ++ pageSections :+ legend)
      .map(_.mkString("\n"))
      .mkString("\n\n")
  }
}
