package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.ParquetSchema
import org.apache.parquet.schema.MessageTypeParser

/** A schema covering the logical types whose parameters `dataType` loses. */
object LogicalTypeFixtures {

  private val message = MessageTypeParser.parseMessageType(
    """message m {
      |  required int64 created_at (TIMESTAMP(MICROS,true));
      |  optional int32 local_time (TIME(MILLIS,false));
      |  optional int32 small_id (INTEGER(16,false));
      |  optional binary status (ENUM);
      |  optional binary payload (JSON);
      |  optional fixed_len_byte_array(16) id (UUID);
      |  optional fixed_len_byte_array(16) amount (DECIMAL(38,9));
      |  optional int32 day (DATE);
      |  optional binary plain;
      |}""".stripMargin
  )

  val schema: ParquetSchema = FooterReader.buildParquetSchema(message, Nil)
}
