package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.{ColumnInfo, ParquetSchema}
import org.apache.parquet.schema.{MessageType, MessageTypeParser}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.schema.LogicalTypeAnnotation
import org.apache.parquet.schema.Type.Repetition
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters._

class ParquetSchemaBuilderTest extends AnyFlatSpec with Matchers {

  // Helper to get a field by name from a MessageType (avoids Scala 3 overload ambiguity)
  private def fieldByName(mt: MessageType, name: String) =
    mt.getFields.asScala.find(_.getName == name).get.asPrimitiveType()

  private def containsField(mt: MessageType, name: String): Boolean =
    mt.getFields.asScala.exists(_.getName == name)

  // ── projectSchema ─────────────────────────────────────────────────────────

  "ParquetSchemaBuilder.projectSchema" should "return a MessageType with only the requested columns" in {
    val fileSchema = MessageTypeParser.parseMessageType(
      """message root {
        |  required int64 id;
        |  required binary name (UTF8);
        |  required double score;
        |}""".stripMargin
    )
    val result = ParquetSchemaBuilder.projectSchema(fileSchema, List("id", "score"))
    result.getFieldCount shouldBe 2
    result.getFields.get(0).getName shouldBe "id"
    result.getFields.get(1).getName shouldBe "score"
  }

  it should "preserve the original field types in the projected schema" in {
    val fileSchema = MessageTypeParser.parseMessageType(
      """message root {
        |  required int32 age;
        |  required binary name (UTF8);
        |}""".stripMargin
    )
    val result = ParquetSchemaBuilder.projectSchema(fileSchema, List("age"))
    result.getFieldCount shouldBe 1
    fieldByName(result, "age").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
  }

  it should "project a single column from a wide schema" in {
    val fileSchema = MessageTypeParser.parseMessageType(
      """message root {
        |  required int64 id;
        |  optional binary name (UTF8);
        |  optional double score;
        |  optional boolean active;
        |}""".stripMargin
    )
    val result = ParquetSchemaBuilder.projectSchema(fileSchema, List("name"))
    result.getFieldCount shouldBe 1
    fieldByName(result, "name").getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
  }

  it should "throw IllegalArgumentException when none of the requested columns exist" in {
    val fileSchema = MessageTypeParser.parseMessageType(
      """message root {
        |  required int64 id;
        |  required binary name (UTF8);
        |}""".stripMargin
    )
    an[IllegalArgumentException] should be thrownBy {
      ParquetSchemaBuilder.projectSchema(fileSchema, List("nonexistent", "also_missing"))
    }
  }

  it should "ignore unknown column names and project the ones that exist" in {
    val fileSchema = MessageTypeParser.parseMessageType(
      """message root {
        |  required int64 id;
        |  required binary name (UTF8);
        |}""".stripMargin
    )
    val result = ParquetSchemaBuilder.projectSchema(fileSchema, List("id", "nonexistent"))
    result.getFieldCount shouldBe 1
    fieldByName(result, "id").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
  }

  it should "name the resulting message type 'root'" in {
    val fileSchema = MessageTypeParser.parseMessageType(
      "message schema { required int32 x; }"
    )
    val result = ParquetSchemaBuilder.projectSchema(fileSchema, List("x"))
    result.getName shouldBe "root"
  }

  // ── buildMessageType ───────────────────────────────────────────────────────

  private def columnInfo(
      name: String,
      dataType: String,
      isOptional: Boolean = true
  ): ColumnInfo =
    ColumnInfo(
      name = name,
      dataType = dataType,
      isOptional = isOptional,
      maxDefinitionLevel = 0,
      maxRepetitionLevel = 0,
      compressionType = "SNAPPY"
    )

  private def schema(cols: ColumnInfo*): ParquetSchema =
    ParquetSchema(columns = cols.toList, rowGroupCount = 1L, totalRowCount = 0L)

  "ParquetSchemaBuilder.buildMessageType" should "build an INT32 field for INT32 data type" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("age", "INT32")))
    fieldByName(mt, "age").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
  }

  it should "build an INT32 field for INT data type alias" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("age", "INT")))
    fieldByName(mt, "age").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
  }

  it should "build an INT64 field for INT64 data type" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("big", "INT64")))
    fieldByName(mt, "big").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
  }

  it should "build an INT64 field for LONG data type alias" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("big", "LONG")))
    fieldByName(mt, "big").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
  }

  it should "build a DOUBLE field" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("score", "DOUBLE")))
    fieldByName(mt, "score").getPrimitiveTypeName shouldBe PrimitiveTypeName.DOUBLE
  }

  it should "build a FLOAT field" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("ratio", "FLOAT")))
    fieldByName(mt, "ratio").getPrimitiveTypeName shouldBe PrimitiveTypeName.FLOAT
  }

  it should "build a BOOLEAN field" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("active", "BOOLEAN")))
    fieldByName(mt, "active").getPrimitiveTypeName shouldBe PrimitiveTypeName.BOOLEAN
  }

  it should "build a DATE field as INT32 with date logical type annotation" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("dob", "DATE")))
    val field = fieldByName(mt, "dob")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
    field.getLogicalTypeAnnotation shouldBe LogicalTypeAnnotation.dateType()
  }

  it should "build a TIMESTAMP field as INT64 with timestamp logical type" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("created_at", "TIMESTAMP")))
    val field = fieldByName(mt, "created_at")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
    field.getLogicalTypeAnnotation shouldBe a[LogicalTypeAnnotation.TimestampLogicalTypeAnnotation]
  }

  it should "build a TIMESTAMP_MILLIS field as INT64 with timestamp logical type" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("ts", "TIMESTAMP_MILLIS")))
    val field = fieldByName(mt, "ts")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
    field.getLogicalTypeAnnotation shouldBe a[LogicalTypeAnnotation.TimestampLogicalTypeAnnotation]
  }

  it should "build a BINARY field with string logical type for STRING data type" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("name", "STRING")))
    val field = fieldByName(mt, "name")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
    field.getLogicalTypeAnnotation shouldBe LogicalTypeAnnotation.stringType()
  }

  it should "build a BINARY field with string logical type for BINARY data type" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("data", "BINARY")))
    val field = fieldByName(mt, "data")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
    field.getLogicalTypeAnnotation shouldBe LogicalTypeAnnotation.stringType()
  }

  it should "fall back to BINARY for unknown data type" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("weird", "WEIRDTYPE")))
    fieldByName(mt, "weird").getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
  }

  it should "respect REQUIRED repetition when isOptional is false" in {
    val mt = ParquetSchemaBuilder.buildMessageType(
      schema(columnInfo("id", "INT64", isOptional = false))
    )
    mt.getFields.get(0).getRepetition shouldBe Repetition.REQUIRED
  }

  it should "respect OPTIONAL repetition when isOptional is true" in {
    val mt = ParquetSchemaBuilder.buildMessageType(
      schema(columnInfo("id", "INT64", isOptional = true))
    )
    mt.getFields.get(0).getRepetition shouldBe Repetition.OPTIONAL
  }

  it should "build a multi-column schema correctly" in {
    val ps = schema(
      columnInfo("id", "INT64", isOptional = false),
      columnInfo("name", "STRING"),
      columnInfo("score", "DOUBLE")
    )
    val mt = ParquetSchemaBuilder.buildMessageType(ps)
    mt.getFieldCount shouldBe 3
    fieldByName(mt, "id").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
    fieldByName(mt, "name").getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
    fieldByName(mt, "score").getPrimitiveTypeName shouldBe PrimitiveTypeName.DOUBLE
  }

  it should "name the resulting message type 'root'" in {
    val mt = ParquetSchemaBuilder.buildMessageType(schema(columnInfo("x", "INT32")))
    mt.getName shouldBe "root"
  }

  // ── inferSchemaFromData ────────────────────────────────────────────────────

  "ParquetSchemaBuilder.inferSchemaFromData" should "throw on empty data" in {
    an[IllegalArgumentException] should be thrownBy {
      ParquetSchemaBuilder.inferSchemaFromData(List.empty)
    }
  }

  it should "infer INT32 from Int values" in {
    val data = List(Map[String, Any]("count" -> 42))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "count").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
  }

  it should "infer INT64 from Long values" in {
    val data = List(Map[String, Any]("id" -> 123456789012345L))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "id").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
  }

  it should "infer DOUBLE from Double values" in {
    val data = List(Map[String, Any]("score" -> 9.5))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "score").getPrimitiveTypeName shouldBe PrimitiveTypeName.DOUBLE
  }

  it should "infer FLOAT from Float values" in {
    val data = List(Map[String, Any]("ratio" -> 0.5f))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "ratio").getPrimitiveTypeName shouldBe PrimitiveTypeName.FLOAT
  }

  it should "infer BOOLEAN from Boolean values" in {
    val data = List(Map[String, Any]("active" -> true))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "active").getPrimitiveTypeName shouldBe PrimitiveTypeName.BOOLEAN
  }

  it should "infer DATE (INT32) from LocalDate values" in {
    val data = List(Map[String, Any]("dob" -> java.time.LocalDate.of(1990, 1, 1)))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    val field = fieldByName(mt, "dob")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
    field.getLogicalTypeAnnotation shouldBe LogicalTypeAnnotation.dateType()
  }

  it should "infer TIMESTAMP (INT64) from Instant values" in {
    val data = List(Map[String, Any]("created_at" -> java.time.Instant.now()))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    val field = fieldByName(mt, "created_at")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
    field.getLogicalTypeAnnotation shouldBe a[LogicalTypeAnnotation.TimestampLogicalTypeAnnotation]
  }

  it should "infer BINARY with string annotation from String values" in {
    val data = List(Map[String, Any]("name" -> "Alice"))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    val field = fieldByName(mt, "name")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
    field.getLogicalTypeAnnotation shouldBe LogicalTypeAnnotation.stringType()
  }

  it should "fall back to BINARY for unknown value types" in {
    val data = List(Map[String, Any]("x" -> BigInt(42)))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "x").getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
  }

  it should "use OPTIONAL repetition for all inferred fields" in {
    val data = List(Map[String, Any]("id" -> 1L, "name" -> "Bob"))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    mt.getFields.asScala.foreach { f =>
      f.getRepetition shouldBe Repetition.OPTIONAL
    }
  }

  it should "collect keys from all rows in the data list" in {
    val data = List(
      Map[String, Any]("id" -> 1L),
      Map[String, Any]("name" -> "Alice")
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    mt.getFieldCount shouldBe 2
    containsField(mt, "id") shouldBe true
    containsField(mt, "name") shouldBe true
  }

  it should "skip null values when sampling for type inference" in {
    val data = List(
      Map[String, Any]("x" -> null),
      Map[String, Any]("x" -> 42)
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "x").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
  }

  it should "name the resulting message type 'root'" in {
    val data = List(Map[String, Any]("col" -> "val"))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    mt.getName shouldBe "root"
  }
}
