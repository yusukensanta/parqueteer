package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.CellValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.github.mjakubowski84.parquet4s.{
  NullValue,
  BooleanValue,
  IntValue,
  LongValue,
  FloatValue,
  DoubleValue,
  BinaryValue,
  DateTimeValue,
  DecimalValue,
  TimestampFormat
}
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.{MessageTypeParser, MessageType}
import org.apache.parquet.example.data.simple.SimpleGroupFactory

class ParquetRecordDecoderTest extends AnyFlatSpec with Matchers {

  // ── decodeValue ──────────────────────────────────────────────────────────

  "ParquetRecordDecoder.decodeValue" should "return CellValue.Null for NullValue" in {
    ParquetRecordDecoder.decodeValue(NullValue) shouldBe CellValue.Null
  }

  it should "decode BooleanValue true" in {
    ParquetRecordDecoder.decodeValue(BooleanValue(true)) shouldBe CellValue
      .Bool(true)
  }

  it should "decode BooleanValue false" in {
    ParquetRecordDecoder.decodeValue(BooleanValue(false)) shouldBe CellValue
      .Bool(false)
  }

  it should "decode IntValue" in {
    ParquetRecordDecoder.decodeValue(IntValue(42)) shouldBe CellValue.I32(42)
  }

  it should "decode negative IntValue" in {
    ParquetRecordDecoder.decodeValue(IntValue(-100)) shouldBe CellValue.I32(
      -100
    )
  }

  it should "decode LongValue" in {
    ParquetRecordDecoder.decodeValue(
      LongValue(123456789012345L)
    ) shouldBe CellValue.I64(123456789012345L)
  }

  it should "decode FloatValue" in {
    ParquetRecordDecoder.decodeValue(FloatValue(3.14f)) match {
      case CellValue.F32(f) => f shouldBe 3.14f +- 0.001f
      case v                => fail(s"Expected CellValue.F32, got $v")
    }
  }

  it should "decode DoubleValue" in {
    ParquetRecordDecoder.decodeValue(DoubleValue(2.718281828)) match {
      case CellValue.F64(d) => d shouldBe 2.718281828 +- 1e-9
      case v                => fail(s"Expected CellValue.F64, got $v")
    }
  }

  it should "decode BinaryValue as UTF-8 string" in {
    val binary = Binary.fromString("hello world")
    ParquetRecordDecoder.decodeValue(BinaryValue(binary)) shouldBe CellValue
      .Str("hello world")
  }

  it should "decode BinaryValue with empty string" in {
    val binary = Binary.fromString("")
    ParquetRecordDecoder.decodeValue(BinaryValue(binary)) shouldBe CellValue
      .Str("")
  }

  it should "decode DateTimeValue as raw Long (postProcessTemporalFields handles unit conversion)" in {
    val epochMillis = 0L
    val result = ParquetRecordDecoder.decodeValue(
      DateTimeValue(epochMillis, TimestampFormat.Int64Millis)
    )
    result shouldBe CellValue.I64(0L)
  }

  it should "decode DateTimeValue for a known timestamp as raw Long" in {
    val ts = java.time.Instant.parse("2024-06-01T12:00:00Z")
    val result = ParquetRecordDecoder.decodeValue(
      DateTimeValue(ts.toEpochMilli, TimestampFormat.Int64Millis)
    )
    result shouldBe CellValue.I64(ts.toEpochMilli)
  }

  it should "decode DecimalValue as BigDecimal (long format)" in {
    // DecimalFormat.longFormat(scale, precision, rescaleOnRead)
    val fmt =
      com.github.mjakubowski84.parquet4s.DecimalFormat.longFormat(2, 10, false)
    val bigInt = new java.math.BigInteger("12345")
    val result = ParquetRecordDecoder.decodeValue(DecimalValue(bigInt, fmt))
    // scale=2 → 12345 becomes 123.45
    result shouldBe CellValue.Dec(
      scala.math.BigDecimal(new java.math.BigDecimal(bigInt, 2))
    )
  }

  it should "decode DecimalValue with zero amount" in {
    val fmt =
      com.github.mjakubowski84.parquet4s.DecimalFormat.longFormat(2, 10, false)
    val bigInt = java.math.BigInteger.ZERO
    val result = ParquetRecordDecoder.decodeValue(DecimalValue(bigInt, fmt))
    result shouldBe CellValue.Dec(
      scala.math.BigDecimal(new java.math.BigDecimal(bigInt, 2))
    )
  }

  // ── postProcessTemporalFields ──────────────────────────────────────────────

  "ParquetRecordDecoder.postProcessTemporalFields" should "convert INT32 DATE field from epoch-day Int to LocalDate" in {
    val schema: MessageType = MessageTypeParser.parseMessageType(
      """message root {
        |  optional int32 dob (DATE);
        |}""".stripMargin
    )
    // epoch day 0 = 1970-01-01
    val row = Map[String, CellValue]("dob" -> CellValue.I32(0))
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    result("dob") shouldBe CellValue.Date(java.time.LocalDate.of(1970, 1, 1))
  }

  it should "convert a known epoch day to correct LocalDate" in {
    val schema: MessageType = MessageTypeParser.parseMessageType(
      """message root {
        |  optional int32 birth (DATE);
        |}""".stripMargin
    )
    val epochDay = java.time.LocalDate.of(1990, 6, 15).toEpochDay.toInt
    val row = Map[String, CellValue]("birth" -> CellValue.I32(epochDay))
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    result("birth") shouldBe CellValue.Date(java.time.LocalDate.of(1990, 6, 15))
  }

  it should "leave non-DATE fields unchanged" in {
    val schema: MessageType = MessageTypeParser.parseMessageType(
      """message root {
        |  optional int64 id;
        |  optional binary name (UTF8);
        |}""".stripMargin
    )
    val row = Map[String, CellValue](
      "id" -> CellValue.I64(42L),
      "name" -> CellValue.Str("Alice")
    )
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    result shouldBe row
  }

  it should "leave a DATE field unchanged when value is not CellValue.I32" in {
    val schema: MessageType = MessageTypeParser.parseMessageType(
      """message root {
        |  optional int32 dob (DATE);
        |}""".stripMargin
    )
    // value is already a Str (already post-processed or unexpected type)
    val row = Map[String, CellValue]("dob" -> CellValue.Str("2001-12-01"))
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    result shouldBe row
  }

  it should "handle a row with no fields matching schema" in {
    val schema: MessageType = MessageTypeParser.parseMessageType(
      """message root {
        |  optional int32 dob (DATE);
        |}""".stripMargin
    )
    val row = Map.empty[String, CellValue]
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    result shouldBe Map.empty[String, CellValue]
  }

  it should "process multiple DATE fields independently" in {
    val schema: MessageType = MessageTypeParser.parseMessageType(
      """message root {
        |  optional int32 start_date (DATE);
        |  optional int32 end_date (DATE);
        |}""".stripMargin
    )
    val startDay = java.time.LocalDate.of(2020, 1, 1).toEpochDay.toInt
    val endDay = java.time.LocalDate.of(2020, 12, 31).toEpochDay.toInt
    val row = Map[String, CellValue](
      "start_date" -> CellValue.I32(startDay),
      "end_date" -> CellValue.I32(endDay)
    )
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    result("start_date") shouldBe CellValue.Date(
      java.time.LocalDate.of(2020, 1, 1)
    )
    result("end_date") shouldBe CellValue.Date(
      java.time.LocalDate.of(2020, 12, 31)
    )
  }

  // ── decodeGroup ──────────────────────────────────────────────────────────

  "ParquetRecordDecoder.decodeGroup" should "decode plain INT32 field" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { required int32 age; }"
    )
    val group = new SimpleGroupFactory(schema).newGroup().append("age", 42)
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result("age") shouldBe CellValue.I32(42)
  }

  it should "decode plain INT64 field" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { required int64 big_num; }"
    )
    val group =
      new SimpleGroupFactory(schema).newGroup().append("big_num", 9876543210L)
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result("big_num") shouldBe CellValue.I64(9876543210L)
  }

  it should "decode BOOLEAN field" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { required boolean active; }"
    )
    val group = new SimpleGroupFactory(schema).newGroup().append("active", true)
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result("active") shouldBe CellValue.Bool(true)
  }

  it should "decode BINARY field as UTF-8 string" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { required binary name (UTF8); }"
    )
    val group = new SimpleGroupFactory(schema)
      .newGroup()
      .append("name", org.apache.parquet.io.api.Binary.fromString("Alice"))
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result("name") shouldBe CellValue.Str("Alice")
  }

  it should "decode unannotated BINARY field as CellValue.Bytes (not Str)" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { required binary raw_data; }"
    )
    val bytes = Array[Byte](0x00.toByte, 0xff.toByte, 0xfe.toByte)
    val bin = org.apache.parquet.io.api.Binary.fromConstantByteArray(bytes)
    val group =
      new SimpleGroupFactory(schema).newGroup().append("raw_data", bin)
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result("raw_data") match {
      case CellValue.Bytes(actual) => actual.toSeq shouldBe bytes.toSeq
      case other => fail(s"Expected CellValue.Bytes, got $other")
    }
  }

  it should "decode STRING-annotated BINARY as Str, not Bytes" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { required binary label (STRING); }"
    )
    val group = new SimpleGroupFactory(schema)
      .newGroup()
      .append("label", org.apache.parquet.io.api.Binary.fromString("hello"))
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result("label") shouldBe CellValue.Str("hello")
  }

  it should "decode INT32 DATE field as LocalDate" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { required int32 dob (DATE); }"
    )
    // epoch day 0 = 1970-01-01
    val group = new SimpleGroupFactory(schema).newGroup().append("dob", 0)
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result("dob") shouldBe CellValue.Date(java.time.LocalDate.of(1970, 1, 1))
  }

  it should "decode INT32 DATE field for a known date" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { required int32 birth (DATE); }"
    )
    val epochDay = java.time.LocalDate.of(1990, 6, 15).toEpochDay.toInt
    val group =
      new SimpleGroupFactory(schema).newGroup().append("birth", epochDay)
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result("birth") shouldBe CellValue.Date(java.time.LocalDate.of(1990, 6, 15))
  }

  it should "emit CellValue.Null for absent optional field (not omit it)" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { optional int32 maybe_field; }"
    )
    // Create group without appending the field — repetition count stays 0
    val group = new SimpleGroupFactory(schema).newGroup()
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result.contains("maybe_field") shouldBe true
    result("maybe_field") shouldBe CellValue.Null
  }

  it should "skip non-primitive (GROUP) field without throwing ClassCastException" in {
    val schema = MessageTypeParser.parseMessageType(
      """message root {
        |  required int32 id;
        |  optional group tags {
        |    repeated binary item (UTF8);
        |  }
        |}""".stripMargin
    )
    val group = new SimpleGroupFactory(schema).newGroup().append("id", 1)
    // Do not add any 'tags' sub-group — non-primitive field must be silently skipped
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result("id") shouldBe CellValue.I32(1)
    result.contains("tags") shouldBe false
  }

  it should "convert negative TIMESTAMP_MICROS correctly (pre-epoch timestamp)" in {
    val schema: MessageType = MessageTypeParser.parseMessageType(
      "message root { optional int64 ts (TIMESTAMP_MICROS); }"
    )
    // -1 microsecond = 1 microsecond before epoch = 1969-12-31T23:59:59.999999Z
    val row = Map[String, CellValue]("ts" -> CellValue.I64(-1L))
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    result("ts") shouldBe CellValue.Ts(
      java.time.Instant.EPOCH.minus(1L, java.time.temporal.ChronoUnit.MICROS)
    )
  }

  it should "convert extreme negative TIMESTAMP_MICROS without arithmetic overflow" in {
    val schema: MessageType = MessageTypeParser.parseMessageType(
      "message root { optional int64 ts (TIMESTAMP_MICROS); }"
    )
    val micros = Long.MinValue / 2
    val row = Map[String, CellValue]("ts" -> CellValue.I64(micros))
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    val expectedInstant = java.time.Instant.ofEpochSecond(
      Math.floorDiv(micros, 1_000_000L),
      Math.floorMod(micros, 1_000_000L) * 1000L
    )
    result("ts") shouldBe CellValue.Ts(expectedInstant)
  }

  it should "convert TIMESTAMP_MICROS in decodeGroup (positive)" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { required int64 ts (TIMESTAMP_MICROS); }"
    )
    val micros =
      java.time.Instant.parse("2024-01-01T00:00:00Z").toEpochMilli * 1000L
    val group = new SimpleGroupFactory(schema).newGroup().append("ts", micros)
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result("ts") shouldBe CellValue.Ts(
      java.time.Instant.parse("2024-01-01T00:00:00Z")
    )
  }

  it should "convert negative TIMESTAMP_MICROS in decodeGroup (pre-epoch)" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { required int64 ts (TIMESTAMP_MICROS); }"
    )
    val group = new SimpleGroupFactory(schema).newGroup().append("ts", -1L)
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result("ts") shouldBe CellValue.Ts(
      java.time.Instant.EPOCH.minus(1L, java.time.temporal.ChronoUnit.MICROS)
    )
  }

  // ── null/absent fields ────────────────────────────────────────────────────

  it should "emit CellValue.Null for absent optional INT32 field" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { optional int32 age; required binary name (UTF8); }"
    )
    // only 'name' is set; 'age' is absent (repetition count == 0)
    val group = new SimpleGroupFactory(schema)
      .newGroup()
      .append("name", org.apache.parquet.io.api.Binary.fromString("Alice"))
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result.contains("age") shouldBe true
    result("age") shouldBe CellValue.Null
    result("name") shouldBe CellValue.Str("Alice")
  }

  it should "include all column keys even when all optional fields are absent" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { optional int32 x; optional int64 y; }"
    )
    val group = new SimpleGroupFactory(schema).newGroup()
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result.keys.toSet shouldBe Set("x", "y")
    result("x") shouldBe CellValue.Null
    result("y") shouldBe CellValue.Null
  }

  it should "preserve insertion order (schema field order) when emitting Null" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { optional int32 z; optional int32 a; optional int32 m; }"
    )
    val group = new SimpleGroupFactory(schema).newGroup()
    val result = ParquetRecordDecoder.decodeGroup(group, schema)
    result.keys.toList shouldBe List("z", "a", "m")
  }

  // ── buildTemporalTransformer / applyTemporalTransformer ───────────────────

  "ParquetRecordDecoder.buildTemporalTransformer" should "return empty map for schema with no temporal annotations" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { required int32 age; required binary name (UTF8); }"
    )
    ParquetRecordDecoder.buildTemporalTransformer(schema) shouldBe Map.empty
  }

  it should "return DATE transformer for DATE-annotated INT32 column" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { optional int32 dob (DATE); }"
    )
    val transformer = ParquetRecordDecoder.buildTemporalTransformer(schema)
    transformer.keySet shouldBe Set("dob")
    transformer("dob")(CellValue.I32(0)) shouldBe CellValue.Date(
      java.time.LocalDate.of(1970, 1, 1)
    )
  }

  it should "return MILLIS transformer for TIMESTAMP_MILLIS INT64 column" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { optional int64 ts (TIMESTAMP_MILLIS); }"
    )
    val transformer = ParquetRecordDecoder.buildTemporalTransformer(schema)
    transformer.keySet shouldBe Set("ts")
    transformer("ts")(CellValue.I64(0L)) shouldBe CellValue.Ts(
      java.time.Instant.EPOCH
    )
  }

  it should "return MICROS transformer for TIMESTAMP_MICROS INT64 column" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { optional int64 ts (TIMESTAMP_MICROS); }"
    )
    val transformer = ParquetRecordDecoder.buildTemporalTransformer(schema)
    transformer.keySet shouldBe Set("ts")
    val micros = 1_000_000L
    transformer("ts")(CellValue.I64(micros)) shouldBe CellValue.Ts(
      java.time.Instant.ofEpochSecond(1L)
    )
  }

  it should "include all temporal columns in the transformer map" in {
    val schema = MessageTypeParser.parseMessageType(
      """message root {
        |  optional int32 d (DATE);
        |  optional int64 ts_ms (TIMESTAMP_MILLIS);
        |  optional int64 ts_us (TIMESTAMP_MICROS);
        |  optional int64 plain_id;
        |}""".stripMargin
    )
    val transformer = ParquetRecordDecoder.buildTemporalTransformer(schema)
    transformer.keySet shouldBe Set("d", "ts_ms", "ts_us")
  }

  "ParquetRecordDecoder.applyTemporalTransformer" should "return row unchanged when transformer is empty" in {
    val row = Map[String, CellValue]("age" -> CellValue.I32(30))
    ParquetRecordDecoder.applyTemporalTransformer(
      row,
      Map.empty
    ) shouldBe row
  }

  it should "transform matching fields and leave others unchanged" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { optional int32 dob (DATE); optional int64 id; }"
    )
    val transformer = ParquetRecordDecoder.buildTemporalTransformer(schema)
    val row = Map[String, CellValue](
      "dob" -> CellValue.I32(0),
      "id" -> CellValue.I64(42L)
    )
    val result = ParquetRecordDecoder.applyTemporalTransformer(row, transformer)
    result("dob") shouldBe CellValue.Date(java.time.LocalDate.of(1970, 1, 1))
    result("id") shouldBe CellValue.I64(42L)
  }

  it should "leave a field unchanged when value type does not match the transformer expectation" in {
    val schema = MessageTypeParser.parseMessageType(
      "message root { optional int32 dob (DATE); }"
    )
    val transformer = ParquetRecordDecoder.buildTemporalTransformer(schema)
    val row =
      Map[String, CellValue]("dob" -> CellValue.Str("already-processed"))
    val result = ParquetRecordDecoder.applyTemporalTransformer(row, transformer)
    result("dob") shouldBe CellValue.Str("already-processed")
  }

  it should "produce the same result as postProcessTemporalFields" in {
    val schema = MessageTypeParser.parseMessageType(
      """message root {
        |  optional int32 d (DATE);
        |  optional int64 ts (TIMESTAMP_MILLIS);
        |  optional int64 id;
        |}""".stripMargin
    )
    val row = Map[String, CellValue](
      "d" -> CellValue.I32(18_262),
      "ts" -> CellValue.I64(1_704_067_200_000L),
      "id" -> CellValue.I64(99L)
    )
    val viaTransformer = ParquetRecordDecoder.applyTemporalTransformer(
      row,
      ParquetRecordDecoder.buildTemporalTransformer(schema)
    )
    val viaLegacy =
      ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    viaTransformer shouldBe viaLegacy
  }
}
