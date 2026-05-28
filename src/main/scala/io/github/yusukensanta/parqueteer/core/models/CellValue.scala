package io.github.yusukensanta.parqueteer.core.models

enum CellValue:
  case Null
  case Str(s: String)
  case I32(i: Int)
  case I64(l: Long)
  case F32(f: Float)
  case F64(d: Double)
  case Bool(b: Boolean)
  case Date(d: java.time.LocalDate)
  case Ts(i: java.time.Instant)
  case Dec(bd: BigDecimal)
  case Bytes(b: Array[Byte])

object CellValue:
  extension (v: CellValue)
    def display: String = v match
      case Null   => "null"
      case Str(s) => s
      case I32(i) => i.toString
      case I64(l) => l.toString
      case F32(f) =>
        java.math.BigDecimal
          .valueOf(f.toDouble)
          .stripTrailingZeros
          .toPlainString
      case F64(d) =>
        java.math.BigDecimal.valueOf(d).stripTrailingZeros.toPlainString
      case Bool(b)  => b.toString
      case Date(d)  => d.toString
      case Ts(i)    => i.toString
      case Dec(bd)  => bd.underlying.toPlainString
      case Bytes(b) => java.util.Base64.getEncoder.encodeToString(b)
