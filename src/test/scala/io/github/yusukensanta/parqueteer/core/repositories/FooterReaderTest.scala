package io.github.yusukensanta.parqueteer.core.repositories

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.schema.LogicalTypeAnnotation

class FooterReaderTest extends AnyFlatSpec with Matchers {

  "logicalTypeName" should "return primitive name when annotation is null" in {
    FooterReader.logicalTypeName(PrimitiveTypeName.INT32, null) shouldBe "INT32"
  }

  it should "return DATE for DateLogicalTypeAnnotation" in {
    FooterReader.logicalTypeName(
      PrimitiveTypeName.INT32,
      LogicalTypeAnnotation.dateType()
    ) shouldBe "DATE"
  }

  it should "return TIMESTAMP_MICROS for microsecond timestamps" in {
    FooterReader.logicalTypeName(
      PrimitiveTypeName.INT64,
      LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.MICROS)
    ) shouldBe "TIMESTAMP_MICROS"
  }

  it should "return TIMESTAMP_NANOS for nanosecond timestamps" in {
    FooterReader.logicalTypeName(
      PrimitiveTypeName.INT64,
      LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.NANOS)
    ) shouldBe "TIMESTAMP_NANOS"
  }

  it should "return TIMESTAMP_MILLIS for millisecond timestamps" in {
    FooterReader.logicalTypeName(
      PrimitiveTypeName.INT64,
      LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.MILLIS)
    ) shouldBe "TIMESTAMP_MILLIS"
  }

  it should "return STRING for StringLogicalTypeAnnotation" in {
    FooterReader.logicalTypeName(
      PrimitiveTypeName.BINARY,
      LogicalTypeAnnotation.stringType()
    ) shouldBe "STRING"
  }

  it should "return STRING for EnumLogicalTypeAnnotation" in {
    FooterReader.logicalTypeName(
      PrimitiveTypeName.BINARY,
      LogicalTypeAnnotation.enumType()
    ) shouldBe "STRING"
  }

  it should "return STRING for JsonLogicalTypeAnnotation" in {
    FooterReader.logicalTypeName(
      PrimitiveTypeName.BINARY,
      LogicalTypeAnnotation.jsonType()
    ) shouldBe "STRING"
  }

  it should "return DECIMAL(p,s) for DecimalLogicalTypeAnnotation" in {
    FooterReader.logicalTypeName(
      PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY,
      LogicalTypeAnnotation.decimalType(2, 10)
    ) shouldBe "DECIMAL(10,2)"
  }

  it should "return TIMESTAMP(INT96) for INT96 with unrecognized annotation" in {
    FooterReader.logicalTypeName(
      PrimitiveTypeName.INT96,
      LogicalTypeAnnotation.intType(32, true)
    ) shouldBe "TIMESTAMP(INT96)"
  }

  it should "return BINARY for FIXED_LEN_BYTE_ARRAY with unrecognized annotation" in {
    FooterReader.logicalTypeName(
      PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY,
      LogicalTypeAnnotation.intType(32, true)
    ) shouldBe "BINARY"
  }

  it should "return primitive name for other unrecognized annotation" in {
    FooterReader.logicalTypeName(
      PrimitiveTypeName.INT32,
      LogicalTypeAnnotation.intType(32, true)
    ) shouldBe "INT32"
  }
}
