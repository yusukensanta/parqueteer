package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.{GroupInfo, LogicalTypeDetail, SchemaNode}
import org.apache.parquet.schema.{GroupType, LogicalTypeAnnotation, MessageType, Type}
import org.apache.parquet.schema.LogicalTypeAnnotation.*
import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*

/**
 * Translates parquet-java schema types (annotations, groups, the whole tree)
 * into the report models. Pure: works on an in-memory [[MessageType]].
 */
private[repositories] object SchemaTypeReader {

  /** Full logical type annotation with every parameter the footer carries. */
  def describeLogicalType(annotation: LogicalTypeAnnotation): LogicalTypeDetail =
    annotation match {
      case ts: TimestampLogicalTypeAnnotation =>
        LogicalTypeDetail(
          "TIMESTAMP",
          List("unit" -> ts.getUnit.name, "isAdjustedToUTC" -> ts.isAdjustedToUTC.toString)
        )
      case t: TimeLogicalTypeAnnotation =>
        LogicalTypeDetail(
          "TIME",
          List("unit" -> t.getUnit.name, "isAdjustedToUTC" -> t.isAdjustedToUTC.toString)
        )
      case i: IntLogicalTypeAnnotation =>
        LogicalTypeDetail(
          "INTEGER",
          List("bitWidth" -> i.getBitWidth.toString, "isSigned" -> i.isSigned.toString)
        )
      case d: DecimalLogicalTypeAnnotation =>
        LogicalTypeDetail(
          "DECIMAL",
          List("precision" -> d.getPrecision.toString, "scale" -> d.getScale.toString)
        )
      case _: MapKeyValueTypeAnnotation => LogicalTypeDetail("MAP_KEY_VALUE")
      case other =>
        LogicalTypeDetail(
          other.getClass.getSimpleName.stripSuffix("LogicalTypeAnnotation").toUpperCase
        )
    }

  private def logicalOf(t: Type): Option[LogicalTypeDetail] =
    Option(t.getLogicalTypeAnnotation).map(describeLogicalType)

  // OriginalType is deprecated upstream, but the legacy converted type is still
  // reported deliberately (files written before LogicalType carry only this).
  @nowarn("cat=deprecation")
  private[repositories] def convertedOf(t: Type): Option[String] =
    Option(t.getLogicalTypeAnnotation).flatMap(a => Option(a.toOriginalType)).map(_.name)

  private def groupInfo(t: Type): GroupInfo =
    GroupInfo(
      name = t.getName,
      repetition = t.getRepetition.name,
      logicalType = logicalOf(t),
      convertedType = convertedOf(t),
      fieldId = Option(t.getId).map(_.intValue)
    )

  /** The groups above a leaf, outermost first (empty for a top-level column). */
  def groupsAbove(root: MessageType, path: Array[String]): List[GroupInfo] =
    path.init
      .foldLeft((root: GroupType, List.empty[GroupInfo])) { case ((parent, acc), name) =>
        val child = parent.getType(name)
        (child.asGroupType, groupInfo(child) :: acc)
      }
      ._2
      .reverse

  def tree(root: MessageType): SchemaNode = {
    def node(t: Type): SchemaNode =
      if t.isPrimitive then {
        val p = t.asPrimitiveType
        SchemaNode(
          name = t.getName,
          repetition = Some(t.getRepetition.name),
          physicalType = Some(p.getPrimitiveTypeName.name),
          typeLength = Option(p.getTypeLength).filter(_ > 0),
          logicalType = logicalOf(t),
          convertedType = convertedOf(t),
          fieldId = Option(t.getId).map(_.intValue),
          children = Nil
        )
      } else
        SchemaNode(
          name = t.getName,
          repetition = Some(t.getRepetition.name),
          physicalType = None,
          typeLength = None,
          logicalType = logicalOf(t),
          convertedType = convertedOf(t),
          fieldId = Option(t.getId).map(_.intValue),
          children = t.asGroupType.getFields.asScala.toList.map(node)
        )
    SchemaNode(
      name = root.getName,
      repetition = None,
      physicalType = None,
      typeLength = None,
      logicalType = None,
      convertedType = None,
      fieldId = None,
      children = root.getFields.asScala.toList.map(node)
    )
  }
}
