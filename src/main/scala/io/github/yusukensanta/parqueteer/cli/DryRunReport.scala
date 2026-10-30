package io.github.yusukensanta.parqueteer.cli

/**
 * The "Dry run: would …" summary printed by write, convert and merge: a
 * title line, then indented lines that are either a `Label: value` field
 * or free-form detail (e.g. one line per matched file). Field values are
 * aligned on the longest label so every report lines up the same way.
 */
final private[cli] case class DryRunReport(action: String, lines: List[DryRunReport.Line]) {
  import DryRunReport.Line

  def render: String = {
    val labelWidth =
      lines.collect { case Line.Field(label, _) => label.length }.maxOption.getOrElse(0)
    val body = lines.map {
      case Line.Field(label, value) => s"  ${(label + ":").padTo(labelWidth + 2, ' ')}$value"
      case Line.Detail(text)        => s"  $text"
    }
    (s"Dry run: would $action" :: body).mkString("\n")
  }

  /** Prints the report to stdout; a dry run always exits 0. */
  def print(): Int = {
    println(render)
    0
  }
}

private[cli] object DryRunReport {

  enum Line:
    case Field(label: String, value: String)
    case Detail(text: String)

  def field(label: String, value: Any): Line = Line.Field(label, value.toString)
  def detail(text: String): Line             = Line.Detail(text)
}
