package io.github.yusukensanta.parqueteer.core.models

/** Row-oriented text formats parqueteer can read and write to parquet. */
enum InputFormat(val name: String):
  case Json   extends InputFormat("json")
  case NDJson extends InputFormat("ndjson")
  case Csv    extends InputFormat("csv")
  case Ltsv   extends InputFormat("ltsv")

  /**
   * True for line-oriented formats that can be re-opened and streamed row by
   * row (two-pass schema inference + write). JSON arrays need a whole-tree
   * parse, so they are always buffered.
   */
  def isStreamable: Boolean = this != Json

object InputFormat:

  def fromString(s: String): Option[InputFormat] =
    values.find(_.name == s.toLowerCase)

  /** Pattern-match helper: `case InputFormat.FromName(fmt) => ...` on a file extension. */
  object FromName:
    def unapply(s: String): Option[InputFormat] = fromString(s)
