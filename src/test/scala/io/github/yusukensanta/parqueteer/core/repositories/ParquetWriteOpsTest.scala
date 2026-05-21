package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.CompressionType
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.schema.{MessageType, MessageTypeParser}
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ParquetWriteOpsTest extends AnyFlatSpec with Matchers {

  // ── convertCompressionType ───────────────────────────────────────────────

  "ParquetWriteOps.convertCompressionType" should "convert Uncompressed to UNCOMPRESSED" in {
    ParquetWriteOps.convertCompressionType(CompressionType.Uncompressed) shouldBe CompressionCodecName.UNCOMPRESSED
  }

  it should "convert Snappy to SNAPPY" in {
    ParquetWriteOps.convertCompressionType(CompressionType.Snappy) shouldBe CompressionCodecName.SNAPPY
  }

  it should "convert Gzip to GZIP" in {
    ParquetWriteOps.convertCompressionType(CompressionType.Gzip) shouldBe CompressionCodecName.GZIP
  }

  it should "convert Lzo to LZO" in {
    ParquetWriteOps.convertCompressionType(CompressionType.Lzo) shouldBe CompressionCodecName.LZO
  }

  it should "convert Brotli to BROTLI" in {
    ParquetWriteOps.convertCompressionType(CompressionType.Brotli) shouldBe CompressionCodecName.BROTLI
  }

  it should "convert Lz4 to LZ4" in {
    ParquetWriteOps.convertCompressionType(CompressionType.Lz4) shouldBe CompressionCodecName.LZ4
  }

  it should "convert Zstd to ZSTD" in {
    ParquetWriteOps.convertCompressionType(CompressionType.Zstd) shouldBe CompressionCodecName.ZSTD
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

  "ParquetWriteOps.writeRowToGroup" should "write an Int field to an INT32 column" in {
    val mt = schema("message root { required int32 age; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("age" -> 42), mt)
    group.getInteger("age", 0) shouldBe 42
  }

  it should "write an Int to INT64 column with widening" in {
    val mt = schema("message root { required int64 id; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("id" -> 7), mt)
    group.getLong("id", 0) shouldBe 7L
  }

  it should "write an Int to DOUBLE column with widening" in {
    val mt = schema("message root { required double score; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("score" -> 3), mt)
    group.getDouble("score", 0) shouldBe 3.0
  }

  it should "write an Int to FLOAT column with widening" in {
    val mt = schema("message root { required float ratio; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("ratio" -> 2), mt)
    group.getFloat("ratio", 0) shouldBe 2.0f +- 0.001f
  }

  it should "write a Long to INT64 column" in {
    val mt = schema("message root { required int64 big; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("big" -> 123456789012345L), mt)
    group.getLong("big", 0) shouldBe 123456789012345L
  }

  it should "write a Long to DOUBLE column with widening" in {
    val mt = schema("message root { required double val; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("val" -> 100L), mt)
    group.getDouble("val", 0) shouldBe 100.0
  }

  it should "write a Long to FLOAT column with widening" in {
    val mt = schema("message root { required float measurement; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    val v = 123456789L
    ParquetWriteOps.writeRowToGroup(group, Map("measurement" -> v), mt)
    group.getFloat("measurement", 0) shouldBe v.toFloat +- math.abs(v.toFloat * 1e-5f)
  }

  it should "write a Double field" in {
    val mt = schema("message root { required double score; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("score" -> 9.99), mt)
    group.getDouble("score", 0) shouldBe 9.99 +- 1e-9
  }

  it should "write a Float field" in {
    val mt = schema("message root { required float ratio; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("ratio" -> 0.5f), mt)
    group.getFloat("ratio", 0) shouldBe 0.5f +- 0.001f
  }

  it should "write a Boolean field" in {
    val mt = schema("message root { required boolean active; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("active" -> true), mt)
    group.getBoolean("active", 0) shouldBe true
  }

  it should "write a String field" in {
    val mt = schema("message root { required binary name (UTF8); }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("name" -> "Alice"), mt)
    group.getString("name", 0) shouldBe "Alice"
  }

  it should "write a LocalDate field as INT32 epoch day" in {
    val mt = schema("message root { required int32 dob (DATE); }")
    val group = new SimpleGroupFactory(mt).newGroup()
    val date = java.time.LocalDate.of(1990, 6, 15)
    ParquetWriteOps.writeRowToGroup(group, Map("dob" -> date), mt)
    group.getInteger("dob", 0) shouldBe date.toEpochDay.toInt
  }

  it should "write an Instant field as INT64 epoch millis" in {
    val mt = schema("message root { required int64 created_at; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    val instant = java.time.Instant.parse("2024-01-01T00:00:00Z")
    ParquetWriteOps.writeRowToGroup(group, Map("created_at" -> instant), mt)
    group.getLong("created_at", 0) shouldBe instant.toEpochMilli
  }

  it should "fall back to toString for unknown value types" in {
    val mt = schema("message root { required binary x (UTF8); }")
    val group = new SimpleGroupFactory(mt).newGroup()
    // BigInt is not explicitly handled, so it should be stringified
    ParquetWriteOps.writeRowToGroup(group, Map("x" -> BigInt(99)), mt)
    group.getString("x", 0) shouldBe "99"
  }

  it should "skip fields with null values" in {
    val mt = schema("message root { optional int32 age; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("age" -> null), mt)
    group.getFieldRepetitionCount("age") shouldBe 0
  }

  it should "throw InvalidRecordException when a row key is absent from the schema" in {
    val mt = MessageTypeParser.parseMessageType("message root { required int32 age; }")
    val group = new SimpleGroupFactory(mt).newGroup()
    an[org.apache.parquet.io.InvalidRecordException] should be thrownBy {
      ParquetWriteOps.writeRowToGroup(group, Map("age" -> 30, "unknown" -> "x"), mt)
    }
  }

  it should "write multiple known fields in a single group" in {
    val mt = MessageTypeParser.parseMessageType(
      "message root { required int32 age; required binary name (STRING); }"
    )
    val group = new SimpleGroupFactory(mt).newGroup()
    ParquetWriteOps.writeRowToGroup(group, Map("age" -> 30, "name" -> "Carol"), mt)
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
    val row = Map[String, Any]("id" -> 1L, "name" -> "Bob", "score" -> 7.5)
    ParquetWriteOps.writeRowToGroup(group, row, mt)
    group.getLong("id", 0) shouldBe 1L
    group.getString("name", 0) shouldBe "Bob"
    group.getDouble("score", 0) shouldBe 7.5 +- 1e-9
  }
}
