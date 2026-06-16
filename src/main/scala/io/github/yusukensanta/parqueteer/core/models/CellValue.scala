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
        if (f.isNaN || f.isInfinite) f.toString
        else
          new java.math.BigDecimal(f.toString).stripTrailingZeros.toPlainString
      case F64(d) =>
        if (d.isNaN || d.isInfinite) d.toString
        else java.math.BigDecimal.valueOf(d).stripTrailingZeros.toPlainString
      case Bool(b)  => b.toString
      case Date(d)  => d.toString
      case Ts(i)    => i.toString
      case Dec(bd)  => bd.underlying.stripTrailingZeros.toPlainString
      case Bytes(b) => java.util.Base64.getEncoder.encodeToString(b)
    // Safe for terminal output: strips ESC and other control codes that can
    // embed ANSI/OSC sequences from attacker-controlled string data.
    def safeDisplay: String = sanitizeTerminal(v.display)

  // Strips terminal-control codes from an arbitrary string (for column names etc.).
  // Keeps tab/LF/CR; removes other C0 controls (incl. ESC 0x1B), DEL (0x7F), C1 (0x80-0x9F).
  def sanitizeTerminal(s: String): String =
    if (s.exists(isControlCode)) s.filterNot(isControlCode) else s

  private def isControlCode(c: Char): Boolean =
    (c < ' ' && c != '\t' && c != '\n' && c != '\r') ||
      c == '\u007F' ||
      (c >= '\u0080' && c <= '\u009F')
