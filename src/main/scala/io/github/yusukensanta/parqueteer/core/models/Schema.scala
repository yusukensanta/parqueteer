package io.github.yusukensanta.parqueteer.core.models

/** Column-level schema with aggregate row group and row count metadata. */
case class ParquetSchema(
    columns: List[ColumnInfo],
    rowGroupCount: Long,
    totalRowCount: Long
)

case class ColumnInfo(
    name: String,
    dataType: String,
    isOptional: Boolean,
    maxDefinitionLevel: Int,
    maxRepetitionLevel: Int,
    compressionType: String,
    encodings: List[String] = Nil
)

case class FieldSummary(name: String, dataType: String, isOptional: Boolean) {
  def isNested: Boolean = NestedType.isNested(dataType)
}

/**
 * Canonical prefixes for nested (group) column type names, e.g.
 * `STRUCT<a:INT32,b:BINARY>`. The single source of truth for both producing
 * these names and recognising them, so the two can't drift apart.
 */
object NestedType {
  val Struct = "STRUCT"
  val Map    = "MAP"
  val List   = "LIST"

  private val prefixes = scala.List(Struct, Map, List)

  def isNested(dataType: String): Boolean = prefixes.exists(dataType.startsWith)

  def struct(fields: String): String = s"$Struct<$fields>"
}

enum SchemaMode:
  case Strict, Union

object SchemaMode:

  def fromString(s: String): Option[SchemaMode] =
    values.find(_.toString.equalsIgnoreCase(s))

case class ColumnChange(
    name: String,
    fromType: String,
    toType: String,
    fromOptional: Boolean,
    toOptional: Boolean
)

/** Result of comparing two Parquet schemas: added, removed, changed, and unchanged columns. */
case class SchemaDiff(
    added: List[ColumnInfo],
    removed: List[ColumnInfo],
    changed: List[ColumnChange],
    unchanged: List[String]
) {
  def identical: Boolean = added.isEmpty && removed.isEmpty && changed.isEmpty
}
