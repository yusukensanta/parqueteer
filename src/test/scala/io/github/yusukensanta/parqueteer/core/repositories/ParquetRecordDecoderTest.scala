package io.github.yusukensanta.parqueteer.core.repositories

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
  DecimalValue
}
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.{MessageTypeParser, MessageType}

class ParquetRecordDecoderTest extends AnyFlatSpec with Matchers {

  // ── decodeValue ──────────────────────────────────────────────────────────

  "ParquetRecordDecoder.decodeValue" should "return null for NullValue" in {
    val result: Any = ParquetRecordDecoder.decodeValue(NullValue)
    assert(result == null)
  }

  it should "decode BooleanValue true" in {
    ParquetRecordDecoder.decodeValue(BooleanValue(true)) shouldBe true
  }

  it should "decode BooleanValue false" in {
    ParquetRecordDecoder.decodeValue(BooleanValue(false)) shouldBe false
  }

  it should "decode IntValue" in {
    ParquetRecordDecoder.decodeValue(IntValue(42)) shouldBe 42
  }

  it should "decode negative IntValue" in {
    ParquetRecordDecoder.decodeValue(IntValue(-100)) shouldBe -100
  }

  it should "decode LongValue" in {
    ParquetRecordDecoder.decodeValue(LongValue(123456789012345L)) shouldBe 123456789012345L
  }

  it should "decode FloatValue" in {
    ParquetRecordDecoder.decodeValue(FloatValue(3.14f)).asInstanceOf[Float] shouldBe 3.14f +- 0.001f
  }

  it should "decode DoubleValue" in {
    ParquetRecordDecoder.decodeValue(DoubleValue(2.718281828)).asInstanceOf[Double] shouldBe 2.718281828 +- 1e-9
  }

  it should "decode BinaryValue as UTF-8 string" in {
    val binary = Binary.fromString("hello world")
    ParquetRecordDecoder.decodeValue(BinaryValue(binary)) shouldBe "hello world"
  }

  it should "decode BinaryValue with empty string" in {
    val binary = Binary.fromString("")
    ParquetRecordDecoder.decodeValue(BinaryValue(binary)) shouldBe ""
  }

  it should "decode DateTimeValue as ISO-8601 instant string" in {
    val epochMillis = 0L // 1970-01-01T00:00:00Z
    val result = ParquetRecordDecoder.decodeValue(DateTimeValue(epochMillis, null))
    result shouldBe "1970-01-01T00:00:00Z"
  }

  it should "decode DateTimeValue for a known timestamp" in {
    val ts = java.time.Instant.parse("2024-06-01T12:00:00Z")
    val result = ParquetRecordDecoder.decodeValue(DateTimeValue(ts.toEpochMilli, null))
    result shouldBe ts.toString
  }

  it should "decode DecimalValue as BigDecimal (long format)" in {
    // DecimalFormat.longFormat(scale, precision, rescaleOnRead)
    val fmt = com.github.mjakubowski84.parquet4s.DecimalFormat.longFormat(2, 10, false)
    val bigInt = new java.math.BigInteger("12345")
    val result = ParquetRecordDecoder.decodeValue(DecimalValue(bigInt, fmt))
    // scale=2 → 12345 becomes 123.45
    result shouldBe scala.math.BigDecimal(new java.math.BigDecimal(bigInt, 2))
  }

  it should "decode DecimalValue with zero amount" in {
    val fmt = com.github.mjakubowski84.parquet4s.DecimalFormat.longFormat(2, 10, false)
    val bigInt = java.math.BigInteger.ZERO
    val result = ParquetRecordDecoder.decodeValue(DecimalValue(bigInt, fmt))
    result shouldBe scala.math.BigDecimal(new java.math.BigDecimal(bigInt, 2))
  }

  // ── postProcessTemporalFields ──────────────────────────────────────────────

  "ParquetRecordDecoder.postProcessTemporalFields" should "convert INT32 DATE field from epoch-day Int to ISO date string" in {
    val schema: MessageType = MessageTypeParser.parseMessageType(
      """message root {
        |  optional int32 dob (DATE);
        |}""".stripMargin
    )
    // epoch day 0 = 1970-01-01
    val row = Map[String, Any]("dob" -> 0)
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    result("dob") shouldBe "1970-01-01"
  }

  it should "convert a known epoch day to correct date string" in {
    val schema: MessageType = MessageTypeParser.parseMessageType(
      """message root {
        |  optional int32 birth (DATE);
        |}""".stripMargin
    )
    val epochDay = java.time.LocalDate.of(1990, 6, 15).toEpochDay.toInt
    val row = Map[String, Any]("birth" -> epochDay)
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    result("birth") shouldBe "1990-06-15"
  }

  it should "leave non-DATE fields unchanged" in {
    val schema: MessageType = MessageTypeParser.parseMessageType(
      """message root {
        |  optional int64 id;
        |  optional binary name (UTF8);
        |}""".stripMargin
    )
    val row = Map[String, Any]("id" -> 42L, "name" -> "Alice")
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    result shouldBe row
  }

  it should "leave a DATE field unchanged when value is not an Int" in {
    val schema: MessageType = MessageTypeParser.parseMessageType(
      """message root {
        |  optional int32 dob (DATE);
        |}""".stripMargin
    )
    // value is already a String (already post-processed or unexpected type)
    val row = Map[String, Any]("dob" -> "2001-12-01")
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    result shouldBe row
  }

  it should "handle a row with no fields matching schema" in {
    val schema: MessageType = MessageTypeParser.parseMessageType(
      """message root {
        |  optional int32 dob (DATE);
        |}""".stripMargin
    )
    val row = Map.empty[String, Any]
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    result shouldBe Map.empty[String, Any]
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
    val row = Map[String, Any]("start_date" -> startDay, "end_date" -> endDay)
    val result = ParquetRecordDecoder.postProcessTemporalFields(row, schema)
    result("start_date") shouldBe "2020-01-01"
    result("end_date") shouldBe "2020-12-31"
  }
}
