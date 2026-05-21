package io.github.yusukensanta.parqueteer.core.util

import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}
import scala.util.Try

object TypeInferrer {

  private val IntPattern = raw"-?\d+".r.pattern
  private val DecimalPattern = raw"-?\d+\.\d+".r.pattern
  private val DatePattern = raw"\d{4}-\d{2}-\d{2}".r.pattern
  private val TsPattern =
    raw"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}.*".r.pattern

  /** Infer a typed value from a raw CSV string. Order: Boolean > Date >
    * Timestamp > Decimal > Long > String.
    */
  def inferCsvValue(raw: String): Any = {
    val s = raw.trim
    if (s.isEmpty) null
    else if (s.equalsIgnoreCase("true")) true
    else if (s.equalsIgnoreCase("false")) false
    else if (DatePattern.matcher(s).matches())
      Try(LocalDate.parse(s)).getOrElse(s)
    else if (TsPattern.matcher(s).matches())
      tryTimestamp(s).getOrElse(s)
    else if (DecimalPattern.matcher(s).matches())
      Try(s.toDouble).getOrElse(s)
    else if (IntPattern.matcher(s).matches())
      // Guard: preserve leading zeros ("007" must stay a String, not become 7L)
      Try(s.toLong).filter(_.toString == s).getOrElse(s)
    else s
  }

  /** Infer date/timestamp from a JSON string value. JSON numbers are already
    * typed; only guess temporal types.
    */
  def inferJsonString(s: String): Any = {
    if (DatePattern.matcher(s).matches())
      Try(LocalDate.parse(s)).getOrElse(s)
    else if (TsPattern.matcher(s).matches())
      tryTimestamp(s).getOrElse(s)
    else s
  }

  private def tryTimestamp(s: String): Try[Instant] =
    Try(Instant.parse(s))
      .orElse(Try(LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)))
      .orElse(
        Try(LocalDateTime.parse(s.replace(" ", "T")).toInstant(ZoneOffset.UTC))
      )
}
