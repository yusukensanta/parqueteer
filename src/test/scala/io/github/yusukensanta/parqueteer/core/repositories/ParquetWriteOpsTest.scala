package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.{
  CellValue,
  CompressionType
}
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.schema.{MessageType, MessageTypeParser}
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ParquetWriteOpsTest extends AnyFlatSpec with Matchers {

  // ── convertCompressionType ───────────────────────────────────────────────

  "ParquetWriteOps.convertCompressionType" should "convert Uncompressed to UNCOMPRESSED" in {
    ParquetWriteOps.convertCompressionType(
      CompressionType.Uncompressed
    ) shouldBe CompressionCodecName.UNCOMPRESSED
  }

  it should "convert Snappy to SNAPPY" in {
    ParquetWriteOps.convertCompressionType(
      CompressionType.Snappy
    ) shouldBe CompressionCodecName.SNAPPY
  }

  it should "convert Gzip to GZIP" in {
    ParquetWriteOps.convertCompressionType(
      CompressionType.Gzip
    ) shouldBe CompressionCodecName.GZIP
  }

  it should "convert Lzo to LZO" in {
    ParquetWriteOps.convertCompressionType(
      CompressionType.Lzo
    ) shouldBe CompressionCodecName.LZO
  }

  it should "convert Brotli to BROTLI" in {
    ParquetWriteOps.convertCompressionType(
      CompressionType.Brotli
    ) shouldBe CompressionCodecName.BROTLI
  }

  it should "convert Lz4 to LZ4" in {
    ParquetWriteOps.convertCompressionType(
      CompressionType.Lz4
    ) shouldBe CompressionCodecName.LZ4
  }

  it should "convert Zstd to ZSTD" in {
    ParquetWriteOps.convertCompressionType(
      CompressionType.Zstd
    ) shouldBe CompressionCodecName.ZSTD
  }

  it should "cover all CompressionType enum cases" in {
    val allTypes = CompressionType.values
    allTypes.foreach { ct =>
      noException should be thrownBy ParquetWriteOps.convertCompressionType(ct)
    }
  }

  // ── writeRowToGroup ──────────────────────────────────────────────────────

  private def schema(schemaStr: String): MessageType =
    MessageTypeParser.parseMessageType(schemaStr)

  "ParquetWriteOps.writeRowToGroup" should "write a CellValue.I32 to INT32 column" in {
    val mt = schema("message root { required int32 age; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("age" -> CellValue.I32(42)), mt)
    group.getInteger("age", 0) shouldBe 42
  }

  it should "write a CellValue.I64 to INT64 column" in {
    val mt = schema("message root { required int64 id; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("id" -> CellValue.I64(7L)), mt)
    group.getLong("id", 0) shouldBe 7L
  }

  it should "write a CellValue.F64 to DOUBLE column" in {
    val mt = schema("message root { required double score; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("score" -> CellValue.F64(3.0)),
      mt
    )
    group.getDouble("score", 0) shouldBe 3.0
  }

  it should "write a CellValue.F32 to FLOAT column" in {
    val mt = schema("message root { required float ratio; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("ratio" -> CellValue.F32(2.0f)),
      mt
    )
    group.getFloat("ratio", 0) shouldBe 2.0f +- 0.001f
  }

  it should "write a Long CellValue.I64 to INT64 column" in {
    val mt = schema("message root { required int64 big; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("big" -> CellValue.I64(123456789012345L)),
      mt
    )
    group.getLong("big", 0) shouldBe 123456789012345L
  }

  it should "write a CellValue.F64 double to DOUBLE column" in {
    val mt = schema("message root { required double val; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("val" -> CellValue.F64(100.0)),
      mt
    )
    group.getDouble("val", 0) shouldBe 100.0
  }

  it should "write a CellValue.F32 float to FLOAT column" in {
    val mt = schema("message root { required float measurement; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    val v = 123456789L
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("measurement" -> CellValue.F32(v.toFloat)),
      mt
    )
    group.getFloat("measurement", 0) shouldBe v.toFloat +- math.abs(
      v.toFloat * 1e-5f
    )
  }

  it should "write a Double field" in {
    val mt = schema("message root { required double score; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("score" -> CellValue.F64(9.99)),
      mt
    )
    group.getDouble("score", 0) shouldBe 9.99 +- 1e-9
  }

  it should "write a Float field" in {
    val mt = schema("message root { required float ratio; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("ratio" -> CellValue.F32(0.5f)),
      mt
    )
    group.getFloat("ratio", 0) shouldBe 0.5f +- 0.001f
  }

  it should "write a Boolean field" in {
    val mt = schema("message root { required boolean active; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("active" -> CellValue.Bool(true)),
      mt
    )
    group.getBoolean("active", 0) shouldBe true
  }

  it should "write a String field" in {
    val mt = schema("message root { required binary name (UTF8); }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("name" -> CellValue.Str("Alice")),
      mt
    )
    group.getString("name", 0) shouldBe "Alice"
  }

  it should "write a LocalDate field as INT32 epoch day" in {
    val mt = schema("message root { required int32 dob (DATE); }")
    val group = new SimpleGroupFactory(mt).newGroup()
    val date = java.time.LocalDate.of(1990, 6, 15)
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("dob" -> CellValue.Date(date)),
      mt
    )
    group.getInteger("dob", 0) shouldBe date.toEpochDay.toInt
  }

  it should "write an Instant field as INT64 epoch millis" in {
    val mt = schema("message root { required int64 created_at; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    val instant = java.time.Instant.parse("2024-01-01T00:00:00Z")
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("created_at" -> CellValue.Ts(instant)),
      mt
    )
    group.getLong("created_at", 0) shouldBe instant.toEpochMilli
  }

  it should "write CellValue.Bytes as raw binary without UTF-8 encoding" in {
    val mt = schema("message root { required binary data; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    val rawBytes =
      Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte)
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("data" -> CellValue.Bytes(rawBytes)),
      mt
    )
    group.getBinary("data", 0).getBytes shouldBe rawBytes
  }

  it should "write CellValue.Dec as double to DOUBLE column" in {
    val mt = schema("message root { required double x; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("x" -> CellValue.Dec(BigDecimal("9.99"))),
      mt
    )
    group.getDouble("x", 0) shouldBe 9.99
  }

  it should "skip fields with CellValue.Null values" in {
    val mt = schema("message root { optional int32 age; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("age" -> CellValue.Null),
      mt
    )
    group.getFieldRepetitionCount("age") shouldBe 0
  }

  it should "throw RowSchemaMismatchException with actionable message when a row key is absent from the schema" in {
    import io.github.yusukensanta.parqueteer.core.models.ParqueteerError
    val mt =
      MessageTypeParser.parseMessageType("message root { required int32 age; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    val ex = intercept[ParqueteerError.RowSchemaMismatchException] {
      ParquetWriteOps.writeRowToGroup(
        group,
        Map("age" -> CellValue.I32(30), "unknown" -> CellValue.Str("x")),
        mt
      )
    }
    ex.getMessage should include("unknown")
    ex.getMessage should include("schema")
  }

  it should "throw ArithmeticException for extreme Date epoch day that overflows Int" in {
    val mt = MessageTypeParser.parseMessageType(
      "message root { required int32 d (DATE); }"
    )
    val group = new SimpleGroupFactory(mt).newGroup()
    val extremeDate = java.time.LocalDate.of(999999999, 1, 1)
    an[ArithmeticException] should be thrownBy {
      ParquetWriteOps.writeRowToGroup(
        group,
        Map("d" -> CellValue.Date(extremeDate)),
        mt
      )
    }
  }

  it should "write multiple known fields in a single group" in {
    val mt = MessageTypeParser.parseMessageType(
      "message root { required int32 age; required binary name (STRING); }"
    )
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(
      group,
      Map("age" -> CellValue.I32(30), "name" -> CellValue.Str("Carol")),
      mt
    )
    group.getInteger("age", 0) shouldBe 30
    group.getBinary("name", 0).toStringUsingUTF8 shouldBe "Carol"
  }

  it should "write multiple fields in a single row" in {
    val mt = schema(
      """message root {
        |  required int64 id;
        |  required binary name (UTF8);
        |  required double score;
        |}""".stripMargin
    )
    val group = new SimpleGroupFactory(mt).newGroup()
    val row = Map[String, CellValue](
      "id" -> CellValue.I64(1L),
      "name" -> CellValue.Str("Bob"),
      "score" -> CellValue.F64(7.5)
    )
    ParquetWriteOps.writeRowToGroup(group, row, mt)
    group.getLong("id", 0) shouldBe 1L
    group.getString("name", 0) shouldBe "Bob"
    group.getDouble("score", 0) shouldBe 7.5 +- 1e-9
  }
}
