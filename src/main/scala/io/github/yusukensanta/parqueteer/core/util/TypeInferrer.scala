package io.github.yusukensanta.parqueteer.core.util

import io.github.yusukensanta.parqueteer.core.models.CellValue
import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Try

object TypeInferrer {
  private val spaceTsWarnOnce = new AtomicBoolean(false)

  private val IntPattern = raw"-?\d+".r.pattern

  private val DecimalPattern =
    raw"-?\d+\.\d+([eE][+-]?\d+)?|-?\d+[eE][+-]?\d+".r.pattern
  private val DatePattern = raw"\d{4}-\d{2}-\d{2}".r.pattern

  private val TsPattern =
    raw"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}.*".r.pattern

  /**
   * Infer a typed CellValue from a raw CSV string. Order: Boolean > Date >
   * Timestamp > Decimal > Long > String.
   */
  def inferCsvValue(raw: String): CellValue = {
    val s = raw.trim
    if s.isEmpty then CellValue.Null
    else if s.equalsIgnoreCase("true") then CellValue.Bool(true)
    else if s.equalsIgnoreCase("false") then CellValue.Bool(false)
    else if DatePattern.matcher(s).matches() then
      Try(LocalDate.parse(s))
        .map(CellValue.Date.apply)
        .getOrElse(CellValue.Str(s))
    else if TsPattern.matcher(s).matches() then
      tryTimestamp(s)
        .map(CellValue.Ts.apply)
        .getOrElse(CellValue.Str(s))
    else if DecimalPattern.matcher(s).matches() then
      Try(new java.math.BigDecimal(s))
        .map(bd => CellValue.Dec(scala.math.BigDecimal(bd)))
        .getOrElse(CellValue.Str(s))
    else if IntPattern.matcher(s).matches() then
      Try(s.toLong)
        .filter(
          _.toString == s
        ) // rejects leading-zero strings ("007" → Str, not I64)
        .map(CellValue.I64.apply)
        .getOrElse(CellValue.Str(s))
    else CellValue.Str(s)
  }

  /**
   * Infer date/timestamp from a JSON string value. JSON numbers are already
   * typed; only guess temporal types.
   */
  def inferJsonString(s: String): CellValue =
    if DatePattern.matcher(s).matches() then
      Try(LocalDate.parse(s))
        .map(CellValue.Date.apply)
        .getOrElse(CellValue.Str(s))
    else if TsPattern.matcher(s).matches() then
      tryTimestamp(s)
        .map(CellValue.Ts.apply)
        .getOrElse(CellValue.Str(s))
    else CellValue.Str(s)

  private def tryTimestamp(s: String): Try[Instant] =
    Try(Instant.parse(s))
      .orElse(Try(LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)))
      .orElse(Try {
        val inst =
          LocalDateTime.parse(s.replace(" ", "T")).toInstant(ZoneOffset.UTC)
        if spaceTsWarnOnce.compareAndSet(false, true) then
          Warnings.emit(
            s"space-delimited datetime '$s' treated as UTC; use ISO-8601 ('T' separator) for unambiguous timestamps"
          )
        inst
      })
}
