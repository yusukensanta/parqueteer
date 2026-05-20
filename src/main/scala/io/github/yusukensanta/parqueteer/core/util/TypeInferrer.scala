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
    if (s.isEmpty) return null
    if (s.equalsIgnoreCase("true")) return true
    if (s.equalsIgnoreCase("false")) return false
    if (DatePattern.matcher(s).matches())
      return Try(LocalDate.parse(s)).getOrElse(s)
    if (TsPattern.matcher(s).matches())
      return tryTimestamp(s).getOrElse(s)
    if (DecimalPattern.matcher(s).matches())
      return Try(s.toDouble).getOrElse(s)
    if (IntPattern.matcher(s).matches())
      // Guard: preserve leading zeros ("007" must stay a String, not become 7L)
      return Try(s.toLong).filter(_.toString == s).getOrElse(s)
    s
  }

  /** Infer date/timestamp from a JSON string value. JSON numbers are already
    * typed; only guess temporal types.
    */
  def inferJsonString(s: String): Any = {
    if (DatePattern.matcher(s).matches())
      return Try(LocalDate.parse(s)).getOrElse(s)
    if (TsPattern.matcher(s).matches())
      return tryTimestamp(s).getOrElse(s)
    s
  }

  private def tryTimestamp(s: String): Try[Instant] =
    Try(Instant.parse(s))
      .orElse(Try(LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)))
      .orElse(
        Try(LocalDateTime.parse(s.replace(" ", "T")).toInstant(ZoneOffset.UTC))
      )
}
