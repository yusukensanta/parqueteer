package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.{
  ColumnInfo,
  FileStats,
  ParquetFile,
  SchemaDiff
}
import io.circe.Json

private[cli] object CliOutputFormatter {

  def formatInfoJson(file: ParquetFile): String =
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

  def formatSchemaJson(file: ParquetFile): String =
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

  def formatStatsTable(stats: FileStats): String = {
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

  def formatStatsJson(stats: FileStats): Json =
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

  def formatSchemaDiffTable(
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

  def formatSchemaDiffJson(diff: SchemaDiff): String = {
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

  def formatBytesForDisplay(bytes: Long): String =
    io.github.yusukensanta.parqueteer.core.util.ByteFormatter.format(bytes)
}
