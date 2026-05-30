package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models._
import org.apache.parquet.schema.{
  LogicalTypeAnnotation,
  MessageType,
  PrimitiveType,
  Type,
  Types
}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import scala.jdk.CollectionConverters._

private[repositories] object ParquetSchemaBuilder {

  def projectSchema(
      fileSchema: MessageType,
      columns: List[String]
  ): MessageType = {
    val columnSet = columns.toSet
    val fields = fileSchema.getFields.asScala
      .filter(f => columnSet.contains(f.getName))
      .toList
    if (fields.isEmpty)
      throw new IllegalArgumentException(
        s"None of the requested columns exist in the file: ${columns.mkString(", ")}"
      )
    new MessageType("root", fields.asJava)
  }

  def buildMessageType(schema: ParquetSchema): MessageType = {
    val builder = Types.buildMessage()
    schema.columns.foreach { col =>
      val repetition =
        if (col.isOptional) Type.Repetition.OPTIONAL
        else Type.Repetition.REQUIRED
      val (primitive, annotation) = mapDeclaredType(col.dataType)
      builder.addField(makeField(col.name, primitive, repetition, annotation))
    }
    builder.named("root")
  }

  // Helper to infer schema from data
  def inferSchemaFromData(data: List[Map[String, CellValue]]): MessageType = {
    if (data.isEmpty)
      throw new IllegalArgumentException("Cannot infer schema from empty data")

    val builder = Types.buildMessage()
    val allKeys = data.flatMap(_.keys).distinct.sorted

    allKeys.foreach { key =>
      val nonNullValues =
        data.flatMap(_.get(key)).filter(_ != CellValue.Null)
      val effectiveRank = nonNullValues
        .map(typeRankForValue)
        .reduceOption(widenTypeRanks)
        .getOrElse(TypeRank.String)

      val (primitive, annotation) = rankToParquetType(effectiveRank)
      builder.addField(
        makeField(key, primitive, Type.Repetition.OPTIONAL, annotation)
      )
    }
    builder.named("root")
  }

  // ── private helpers ───────────────────────────────────────────────────────

  /** Build a Parquet primitive field with an optional logical type annotation.
    */
  private def makeField(
      name: String,
      primitive: PrimitiveTypeName,
      repetition: Type.Repetition,
      annotation: Option[LogicalTypeAnnotation]
  ): PrimitiveType = {
    val base = Types.primitive(primitive, repetition)
    annotation.foldLeft(base)(_.as(_)).named(name)
  }

  /** Map a user-declared dataType string to its (PrimitiveTypeName,
    * annotation). Unknown types fall back to BINARY without annotation (legacy
    * behavior).
    */
  private def mapDeclaredType(
      dataType: String
  ): (PrimitiveTypeName, Option[LogicalTypeAnnotation]) =
    dataType.toUpperCase match {
      case "INT32" | "INT"  => (PrimitiveTypeName.INT32, None)
      case "INT64" | "LONG" => (PrimitiveTypeName.INT64, None)
      case "DOUBLE"         => (PrimitiveTypeName.DOUBLE, None)
      case "FLOAT"          => (PrimitiveTypeName.FLOAT, None)
      case "BOOLEAN"        => (PrimitiveTypeName.BOOLEAN, None)
      case "DATE"           => (PrimitiveTypeName.INT32, Some(dateAnnotation))
      case "TIMESTAMP" | "TIMESTAMP_MILLIS" =>
        (PrimitiveTypeName.INT64, Some(timestampMillisAnnotation))
      case "STRING" => (PrimitiveTypeName.BINARY, Some(stringAnnotation))
      case "BINARY" => (PrimitiveTypeName.BINARY, None)
      case _        => (PrimitiveTypeName.BINARY, None)
    }

  private def dateAnnotation: LogicalTypeAnnotation =
    LogicalTypeAnnotation.dateType()

  private def timestampMillisAnnotation: LogicalTypeAnnotation =
    LogicalTypeAnnotation.timestampType(
      true,
      LogicalTypeAnnotation.TimeUnit.MILLIS
    )

  private def stringAnnotation: LogicalTypeAnnotation =
    LogicalTypeAnnotation.stringType()

  /** Inferred CellValue type for schema-from-data. Ordering matters for
    * `widenTypeRanks`: numeric ranks (Int < Long < Float < Double) are widened
    * within the numeric family; everything else falls back to String.
    */
  private enum TypeRank:
    case Int, Long, Float, Double, Boolean, Date, Timestamp, String

  private val numericRanks: Set[TypeRank] =
    Set(TypeRank.Int, TypeRank.Long, TypeRank.Float, TypeRank.Double)

  private def typeRankForValue(v: CellValue): TypeRank = v match {
    case CellValue.I32(_)  => TypeRank.Int
    case CellValue.I64(_)  => TypeRank.Long
    case CellValue.F32(_)  => TypeRank.Float
    case CellValue.F64(_)  => TypeRank.Double
    case CellValue.Bool(_) => TypeRank.Boolean
    case CellValue.Date(_) => TypeRank.Date
    case CellValue.Ts(_)   => TypeRank.Timestamp
    case _                 => TypeRank.String
  }

  /** Within the numeric family widen to the max rank; incompatible types fall
    * back to String.
    */
  private def widenTypeRanks(a: TypeRank, b: TypeRank): TypeRank =
    if (a == b) a
    else if (numericRanks(a) && numericRanks(b))
      if (a.ordinal >= b.ordinal) a else b
    else TypeRank.String

  private def rankToParquetType(
      rank: TypeRank
  ): (PrimitiveTypeName, Option[LogicalTypeAnnotation]) = rank match {
    case TypeRank.Int     => (PrimitiveTypeName.INT32, None)
    case TypeRank.Long    => (PrimitiveTypeName.INT64, None)
    case TypeRank.Float   => (PrimitiveTypeName.FLOAT, None)
    case TypeRank.Double  => (PrimitiveTypeName.DOUBLE, None)
    case TypeRank.Boolean => (PrimitiveTypeName.BOOLEAN, None)
    case TypeRank.Date    => (PrimitiveTypeName.INT32, Some(dateAnnotation))
    case TypeRank.Timestamp =>
      (PrimitiveTypeName.INT64, Some(timestampMillisAnnotation))
    case TypeRank.String => (PrimitiveTypeName.BINARY, Some(stringAnnotation))
  }
}
