package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  ColumnInfo,
  ParquetSchema
}
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
    val result =
      ParquetSchemaBuilder.projectSchema(fileSchema, List("id", "score"))
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
    fieldByName(
      result,
      "age"
    ).getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
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
    fieldByName(
      result,
      "name"
    ).getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
  }

  it should "throw IllegalArgumentException when none of the requested columns exist" in {
    val fileSchema = MessageTypeParser.parseMessageType(
      """message root {
        |  required int64 id;
        |  required binary name (UTF8);
        |}""".stripMargin
    )
    val ex = intercept[IllegalArgumentException] {
      ParquetSchemaBuilder.projectSchema(fileSchema, List("nonexistent"))
    }
    ex.getMessage should include("nonexistent")
    ex.getMessage should include("Available columns")
    ex.getMessage should include("id")
    ex.getMessage should include("name")
  }

  it should "ignore unknown column names and project the ones that exist" in {
    val fileSchema = MessageTypeParser.parseMessageType(
      """message root {
        |  required int64 id;
        |  required binary name (UTF8);
        |}""".stripMargin
    )
    val result =
      ParquetSchemaBuilder.projectSchema(fileSchema, List("id", "nonexistent"))
    result.getFieldCount shouldBe 1
    fieldByName(
      result,
      "id"
    ).getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
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
    val mt =
      ParquetSchemaBuilder.buildMessageType(schema(columnInfo("age", "INT32")))
    fieldByName(mt, "age").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
  }

  it should "build an INT32 field for INT data type alias" in {
    val mt =
      ParquetSchemaBuilder.buildMessageType(schema(columnInfo("age", "INT")))
    fieldByName(mt, "age").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
  }

  it should "build an INT64 field for INT64 data type" in {
    val mt =
      ParquetSchemaBuilder.buildMessageType(schema(columnInfo("big", "INT64")))
    fieldByName(mt, "big").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
  }

  it should "build an INT64 field for LONG data type alias" in {
    val mt =
      ParquetSchemaBuilder.buildMessageType(schema(columnInfo("big", "LONG")))
    fieldByName(mt, "big").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
  }

  it should "build a DOUBLE field" in {
    val mt = ParquetSchemaBuilder.buildMessageType(
      schema(columnInfo("score", "DOUBLE"))
    )
    fieldByName(
      mt,
      "score"
    ).getPrimitiveTypeName shouldBe PrimitiveTypeName.DOUBLE
  }

  it should "build a FLOAT field" in {
    val mt = ParquetSchemaBuilder.buildMessageType(
      schema(columnInfo("ratio", "FLOAT"))
    )
    fieldByName(
      mt,
      "ratio"
    ).getPrimitiveTypeName shouldBe PrimitiveTypeName.FLOAT
  }

  it should "build a BOOLEAN field" in {
    val mt = ParquetSchemaBuilder.buildMessageType(
      schema(columnInfo("active", "BOOLEAN"))
    )
    fieldByName(
      mt,
      "active"
    ).getPrimitiveTypeName shouldBe PrimitiveTypeName.BOOLEAN
  }

  it should "build a DATE field as INT32 with date logical type annotation" in {
    val mt =
      ParquetSchemaBuilder.buildMessageType(schema(columnInfo("dob", "DATE")))
    val field = fieldByName(mt, "dob")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
    field.getLogicalTypeAnnotation shouldBe LogicalTypeAnnotation.dateType()
  }

  it should "build a TIMESTAMP field as INT64 with timestamp logical type" in {
    val mt = ParquetSchemaBuilder.buildMessageType(
      schema(columnInfo("created_at", "TIMESTAMP"))
    )
    val field = fieldByName(mt, "created_at")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
    field.getLogicalTypeAnnotation shouldBe a[
      LogicalTypeAnnotation.TimestampLogicalTypeAnnotation
    ]
  }

  it should "build a TIMESTAMP_MILLIS field as INT64 with timestamp logical type" in {
    val mt = ParquetSchemaBuilder.buildMessageType(
      schema(columnInfo("ts", "TIMESTAMP_MILLIS"))
    )
    val field = fieldByName(mt, "ts")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
    field.getLogicalTypeAnnotation shouldBe a[
      LogicalTypeAnnotation.TimestampLogicalTypeAnnotation
    ]
  }

  it should "build a TIMESTAMP_MICROS field as INT64 with MICROS timestamp annotation" in {
    val mt = ParquetSchemaBuilder.buildMessageType(
      schema(columnInfo("ts_us", "TIMESTAMP_MICROS"))
    )
    val field = fieldByName(mt, "ts_us")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
    val ann = field.getLogicalTypeAnnotation
      .asInstanceOf[LogicalTypeAnnotation.TimestampLogicalTypeAnnotation]
    ann.getUnit shouldBe LogicalTypeAnnotation.TimeUnit.MICROS
  }

  it should "build a BINARY field with string logical type for STRING data type" in {
    val mt = ParquetSchemaBuilder.buildMessageType(
      schema(columnInfo("name", "STRING"))
    )
    val field = fieldByName(mt, "name")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
    field.getLogicalTypeAnnotation shouldBe LogicalTypeAnnotation.stringType()
  }

  it should "build a BINARY field with no logical type annotation for BINARY data type" in {
    val mt = ParquetSchemaBuilder.buildMessageType(
      schema(columnInfo("data", "BINARY"))
    )
    val field = fieldByName(mt, "data")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
    field.getLogicalTypeAnnotation shouldBe null
  }

  it should "throw IllegalArgumentException for unknown data type" in {
    an[IllegalArgumentException] should be thrownBy {
      ParquetSchemaBuilder.buildMessageType(
        schema(columnInfo("weird", "WEIRDTYPE"))
      )
    }
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
    fieldByName(
      mt,
      "name"
    ).getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
    fieldByName(
      mt,
      "score"
    ).getPrimitiveTypeName shouldBe PrimitiveTypeName.DOUBLE
  }

  it should "name the resulting message type 'root'" in {
    val mt =
      ParquetSchemaBuilder.buildMessageType(schema(columnInfo("x", "INT32")))
    mt.getName shouldBe "root"
  }

  // ── inferSchemaFromData ────────────────────────────────────────────────────

  "ParquetSchemaBuilder.inferSchemaFromData" should "throw on empty data" in {
    an[IllegalArgumentException] should be thrownBy {
      ParquetSchemaBuilder.inferSchemaFromData(List.empty)
    }
  }

  it should "infer INT32 from CellValue.I32 values" in {
    val data = List(Map[String, CellValue]("count" -> CellValue.I32(42)))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(
      mt,
      "count"
    ).getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
  }

  it should "infer INT64 from CellValue.I64 values" in {
    val data =
      List(Map[String, CellValue]("id" -> CellValue.I64(123456789012345L)))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "id").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
  }

  it should "infer DOUBLE from CellValue.F64 values" in {
    val data = List(Map[String, CellValue]("score" -> CellValue.F64(9.5)))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(
      mt,
      "score"
    ).getPrimitiveTypeName shouldBe PrimitiveTypeName.DOUBLE
  }

  it should "infer FLOAT from CellValue.F32 values" in {
    val data = List(Map[String, CellValue]("ratio" -> CellValue.F32(0.5f)))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(
      mt,
      "ratio"
    ).getPrimitiveTypeName shouldBe PrimitiveTypeName.FLOAT
  }

  it should "infer BOOLEAN from CellValue.Bool values" in {
    val data = List(Map[String, CellValue]("active" -> CellValue.Bool(true)))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(
      mt,
      "active"
    ).getPrimitiveTypeName shouldBe PrimitiveTypeName.BOOLEAN
  }

  it should "infer DATE (INT32) from CellValue.Date values" in {
    val data = List(
      Map[String, CellValue](
        "dob" -> CellValue.Date(java.time.LocalDate.of(1990, 1, 1))
      )
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    val field = fieldByName(mt, "dob")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
    field.getLogicalTypeAnnotation shouldBe LogicalTypeAnnotation.dateType()
  }

  it should "infer TIMESTAMP (INT64) from CellValue.Ts values" in {
    val data = List(
      Map[String, CellValue](
        "created_at" -> CellValue.Ts(java.time.Instant.now())
      )
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    val field = fieldByName(mt, "created_at")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
    field.getLogicalTypeAnnotation shouldBe a[
      LogicalTypeAnnotation.TimestampLogicalTypeAnnotation
    ]
  }

  it should "infer BINARY with string annotation from CellValue.Str values" in {
    val data = List(Map[String, CellValue]("name" -> CellValue.Str("Alice")))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    val field = fieldByName(mt, "name")
    field.getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
    field.getLogicalTypeAnnotation shouldBe LogicalTypeAnnotation.stringType()
  }

  it should "infer DOUBLE for CellValue.Dec (numeric type, not STRING)" in {
    val data =
      List(Map[String, CellValue]("x" -> CellValue.Dec(BigDecimal(42))))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "x").getPrimitiveTypeName shouldBe PrimitiveTypeName.DOUBLE
  }

  it should "use OPTIONAL repetition for all inferred fields" in {
    val data = List(
      Map[String, CellValue](
        "id" -> CellValue.I64(1L),
        "name" -> CellValue.Str("Bob")
      )
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    mt.getFields.asScala.foreach { f =>
      f.getRepetition shouldBe Repetition.OPTIONAL
    }
  }

  it should "collect keys from all rows in the data list" in {
    val data = List(
      Map[String, CellValue]("id" -> CellValue.I64(1L)),
      Map[String, CellValue]("name" -> CellValue.Str("Alice"))
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    mt.getFieldCount shouldBe 2
    containsField(mt, "id") shouldBe true
    containsField(mt, "name") shouldBe true
  }

  it should "skip CellValue.Null when sampling for type inference" in {
    val data = List(
      Map[String, CellValue]("x" -> CellValue.Null),
      Map[String, CellValue]("x" -> CellValue.I32(42))
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "x").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT32
  }

  it should "infer BINARY for columns where all values are CellValue.Null" in {
    val data = List(
      Map[String, CellValue](
        "id" -> CellValue.I64(1L),
        "blob" -> CellValue.Null
      )
    )
    val schema = ParquetSchemaBuilder.inferSchemaFromData(data)
    schema.getFieldCount shouldBe 2
    val blobField = schema.getFields.asScala.find(_.getName == "blob").get
    blobField
      .asPrimitiveType()
      .getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
  }

  it should "name the resulting message type 'root'" in {
    val data = List(Map[String, CellValue]("col" -> CellValue.Str("val")))
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    mt.getName shouldBe "root"
  }

  // ── multi-row type widening (issue #125) ──────────────────────────────────

  it should "widen I32 to I64 when both appear in the same column" in {
    val data = List(
      Map[String, CellValue]("x" -> CellValue.I32(1)),
      Map[String, CellValue]("x" -> CellValue.I64(2L))
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "x").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
  }

  it should "widen I64 to F64 when both appear in the same column" in {
    val data = List(
      Map[String, CellValue]("x" -> CellValue.I64(1L)),
      Map[String, CellValue]("x" -> CellValue.F64(2.5))
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "x").getPrimitiveTypeName shouldBe PrimitiveTypeName.DOUBLE
  }

  it should "widen I32 to F64 when both appear in the same column" in {
    val data = List(
      Map[String, CellValue]("x" -> CellValue.I32(1)),
      Map[String, CellValue]("x" -> CellValue.F64(2.0))
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "x").getPrimitiveTypeName shouldBe PrimitiveTypeName.DOUBLE
  }

  it should "widen I64 and F32 to DOUBLE (Float cannot represent full Long range)" in {
    val data = List(
      Map[String, CellValue]("x" -> CellValue.I64(Long.MaxValue)),
      Map[String, CellValue]("x" -> CellValue.F32(1.5f))
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "x").getPrimitiveTypeName shouldBe PrimitiveTypeName.DOUBLE
  }

  it should "widen I32 and F32 to DOUBLE (Float cannot represent all Int values exactly)" in {
    val data = List(
      Map[String, CellValue]("x" -> CellValue.I32(42)),
      Map[String, CellValue]("x" -> CellValue.F32(1.5f))
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "x").getPrimitiveTypeName shouldBe PrimitiveTypeName.DOUBLE
  }

  it should "fall back to BINARY when Str and I64 appear in the same column" in {
    val data = List(
      Map[String, CellValue]("x" -> CellValue.Str("twenty")),
      Map[String, CellValue]("x" -> CellValue.I64(25L))
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "x").getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
  }

  it should "fall back to BINARY when Bool and I64 appear in the same column" in {
    val data = List(
      Map[String, CellValue]("x" -> CellValue.Bool(true)),
      Map[String, CellValue]("x" -> CellValue.I64(1L))
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "x").getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
  }

  it should "use widened type even when the first non-null value has a narrower type" in {
    // row 0 is null, row 1 is Str, row 2 is I64 — must NOT pick Str rank alone
    val data = List(
      Map[String, CellValue]("score" -> CellValue.Null),
      Map[String, CellValue]("score" -> CellValue.Str("twenty")),
      Map[String, CellValue]("score" -> CellValue.I64(25L))
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    // Str + I64 → BINARY (String wins as common supertype)
    fieldByName(
      mt,
      "score"
    ).getPrimitiveTypeName shouldBe PrimitiveTypeName.BINARY
  }

  it should "use the wider numeric type when first non-null is I32 but later rows have I64" in {
    val data = List(
      Map[String, CellValue]("n" -> CellValue.Null),
      Map[String, CellValue]("n" -> CellValue.I32(1)),
      Map[String, CellValue]("n" -> CellValue.I64(9999999999L))
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    fieldByName(mt, "n").getPrimitiveTypeName shouldBe PrimitiveTypeName.INT64
  }

  it should "print a warning to stderr when column has mixed incompatible types" in {
    val data = List(
      Map[String, CellValue]("x" -> CellValue.I64(1L)),
      Map[String, CellValue]("x" -> CellValue.Str("hello"))
    )
    val errCapture = new java.io.ByteArrayOutputStream()
    Console.withErr(errCapture) {
      ParquetSchemaBuilder.inferSchemaFromData(data)
    }
    val output = errCapture.toString
    output should include("warning")
    output should include("x")
    output should include("STRING")
  }

  it should "warn only once per column for mixed-type widening" in {
    val data = List(
      Map[String, CellValue]("x" -> CellValue.I64(1L)),
      Map[String, CellValue]("x" -> CellValue.Str("a")),
      Map[String, CellValue]("x" -> CellValue.Str("b"))
    )
    val errCapture = new java.io.ByteArrayOutputStream()
    Console.withErr(errCapture) {
      ParquetSchemaBuilder.inferSchemaFromData(data)
    }
    val warnCount = errCapture.toString.split('\n').count(_.contains("warning"))
    warnCount shouldBe 1
  }

  it should "infer DOUBLE schema for Dec values (not STRING)" in {
    import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
    val data = List(
      Map[String, CellValue]("price" -> CellValue.Dec(BigDecimal("9.99")))
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    val field = mt.getFields.get(0)
    field.asPrimitiveType.getPrimitiveTypeName shouldBe PrimitiveTypeName.DOUBLE
  }

  it should "widen Dec and Int64 columns together to DOUBLE" in {
    import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
    val data = List(
      Map[String, CellValue]("n" -> CellValue.Dec(BigDecimal("1.5"))),
      Map[String, CellValue]("n" -> CellValue.I64(2L))
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    val field = mt.getFields.get(0)
    field.asPrimitiveType.getPrimitiveTypeName shouldBe PrimitiveTypeName.DOUBLE
  }

  it should "preserve source insertion order (not sort alphabetically)" in {
    // Source order: z, a, m — alphabetical would give a, m, z
    val data = List(
      scala.collection.immutable.ListMap[String, CellValue](
        "z_col" -> CellValue.I64(3L),
        "a_col" -> CellValue.I64(1L),
        "m_col" -> CellValue.I64(2L)
      )
    )
    val mt = ParquetSchemaBuilder.inferSchemaFromData(data)
    val names = mt.getFields.asScala.map(_.getName).toList
    names shouldBe List("z_col", "a_col", "m_col")
  }
}
