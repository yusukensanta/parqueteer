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
    encodings: List[String] = Nil,
    physicalType: String = "",
    typeLength: Option[Int] = None,
    logicalType: Option[LogicalTypeDetail] = None,
    convertedType: Option[String] = None,
    groups: List[GroupInfo] = Nil,
    fieldId: Option[Int] = None
)

/**
 * A group (struct / LIST / MAP wrapper) on the path from the schema root to a
 * leaf column. LIST and MAP are annotations on the group, not on the leaf, so
 * this is the only place they surface in a leaf-per-row view.
 */
case class GroupInfo(
    name: String,
    repetition: String,
    logicalType: Option[LogicalTypeDetail] = None,
    convertedType: Option[String] = None,
    fieldId: Option[Int] = None
) {

  /** `tags=LIST` for an annotated group, `None` for a plain struct group. */
  def annotation: Option[String] = logicalType.map(lt => s"$name=${lt.render}")
}

/**
 * A column's logical type annotation with all of its parameters, e.g.
 * `TIMESTAMP` with `unit=MICROS`, `isAdjustedToUTC=true`. Parameter values are
 * rendered as strings; `true`/`false` and integers are emitted as native JSON
 * booleans/numbers by the JSON output.
 */
case class LogicalTypeDetail(name: String, params: List[(String, String)] = Nil) {

  def render: String =
    if params.isEmpty then name
    else s"$name(${params.map { case (k, v) => s"$k=$v" }.mkString(", ")})"
}

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
