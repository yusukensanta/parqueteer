package io.github.yusukensanta.parqueteer.core.util

import io.github.yusukensanta.parqueteer.core.models.CellValue
import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}
import scala.util.Try

object TypeInferrer {

  private val IntPattern = raw"-?\d+".r.pattern
  private val DecimalPattern = raw"-?\d+\.\d+".r.pattern
  private val DatePattern = raw"\d{4}-\d{2}-\d{2}".r.pattern
  private val TsPattern =
    raw"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}.*".r.pattern

  /** Infer a typed CellValue from a raw CSV string. Order: Boolean > Date >
    * Timestamp > Decimal > Long > String.
    */
  def inferCsvValue(raw: String): CellValue = {
    val s = raw.trim
    if (s.isEmpty) CellValue.Null
    else if (s.equalsIgnoreCase("true")) CellValue.Bool(true)
    else if (s.equalsIgnoreCase("false")) CellValue.Bool(false)
    else if (DatePattern.matcher(s).matches())
      Try(LocalDate.parse(s))
        .map(CellValue.Date.apply)
        .getOrElse(CellValue.Str(s))
    else if (TsPattern.matcher(s).matches())
      tryTimestamp(s)
        .map(CellValue.Ts.apply)
        .getOrElse(CellValue.Str(s))
    else if (DecimalPattern.matcher(s).matches())
      Try(s.toDouble).map(CellValue.F64.apply).getOrElse(CellValue.Str(s))
    else if (IntPattern.matcher(s).matches())
      Try(s.toLong)
        .filter(_.toString == s)
        .map(CellValue.I64.apply)
        .getOrElse(CellValue.Str(s))
    else CellValue.Str(s)
  }

  /** Infer date/timestamp from a JSON string value. JSON numbers are already
    * typed; only guess temporal types.
    */
  def inferJsonString(s: String): CellValue = {
    if (DatePattern.matcher(s).matches())
      Try(LocalDate.parse(s))
        .map(CellValue.Date.apply)
        .getOrElse(CellValue.Str(s))
    else if (TsPattern.matcher(s).matches())
      tryTimestamp(s)
        .map(CellValue.Ts.apply)
        .getOrElse(CellValue.Str(s))
    else CellValue.Str(s)
  }

  private def tryTimestamp(s: String): Try[Instant] =
    Try(Instant.parse(s))
      .orElse(Try(LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)))
      .orElse(
        Try(LocalDateTime.parse(s.replace(" ", "T")).toInstant(ZoneOffset.UTC))
      )
}
