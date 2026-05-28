package io.github.yusukensanta.parqueteer.core.util

import io.circe.Json
import io.github.yusukensanta.parqueteer.core.models.CellValue

// NaN and Infinity float/double values are serialized as JSON strings ("NaN", "Infinity", "-Infinity")
// because RFC 8259 disallows them as number literals. Downstream consumers must handle string fallback.
object JsonEncoder {
  def encode(value: CellValue): Json = value match {
    case CellValue.Null   => Json.Null
    case CellValue.Str(s) => Json.fromString(s)
    case CellValue.I32(i) => Json.fromInt(i)
    case CellValue.I64(l) => Json.fromLong(l)
    case CellValue.F64(d) =>
      if (d.isNaN) Json.fromString("NaN")
      else if (d.isPosInfinity) Json.fromString("Infinity")
      else if (d.isNegInfinity) Json.fromString("-Infinity")
      else Json.fromDoubleOrNull(d)
    case CellValue.F32(f) =>
      if (f.isNaN) Json.fromString("NaN")
      else if (f.isPosInfinity) Json.fromString("Infinity")
      else if (f.isNegInfinity) Json.fromString("-Infinity")
      else Json.fromFloatOrNull(f)
    case CellValue.Bool(b) => Json.fromBoolean(b)
    case CellValue.Dec(bd) => Json.fromBigDecimal(bd)
    case CellValue.Date(d) => Json.fromString(d.toString)
    case CellValue.Ts(i)   => Json.fromString(i.toString)
    case CellValue.Bytes(b) =>
      Json.fromString(java.util.Base64.getEncoder.encodeToString(b))
  }
}
