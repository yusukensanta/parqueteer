package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.FileStats

/**
 * A data-quality check evaluated against footer statistics only (no data
 * scan), e.g. `rows > 0`, `id.nulls == 0`, `amount.min >= 0`,
 * `created_at.max <= "2026-12-31"`.
 *
 * Subjects: `rows`, `row_groups`, and `<column>.nulls|min|max` (the column
 * may itself contain dots; the suffix after the last dot picks the stat).
 * Numbers compare numerically; a quoted value compares as text, which orders
 * ISO dates and timestamps correctly. A stat the file doesn't record fails
 * the check: a gate can't pass on missing evidence.
 */
final case class StatsAssertion(
    source: String,
    subject: StatsAssertion.Subject,
    op: StatsAssertion.Op,
    expected: StatsAssertion.Value
)

object StatsAssertion {

  enum Subject:
    case Rows
    case RowGroups
    case Nulls(column: String)
    case Min(column: String)
    case Max(column: String)

  enum Op(val symbol: String):
    case Eq extends Op("==")
    case Ne extends Op("!=")
    case Le extends Op("<=")
    case Ge extends Op(">=")
    case Lt extends Op("<")
    case Gt extends Op(">")

    def holds(cmp: Int): Boolean = this match
      case Eq => cmp == 0
      case Ne => cmp != 0
      case Le => cmp <= 0
      case Ge => cmp >= 0
      case Lt => cmp < 0
      case Gt => cmp > 0

  enum Value:
    case Num(n: BigDecimal)
    case Text(s: String)

    def render: String = this match
      case Num(n)  => n.bigDecimal.toPlainString
      case Text(s) => s"\"$s\""

  /** Outcome of one assertion: `ok` plus a one-line human explanation. */
  final case class Result(assertion: StatsAssertion, ok: Boolean, detail: String)

  // Two-character operators first so `<=` isn't read as `<`.
  private val pattern = """^\s*(.+?)\s*(==|!=|<=|>=|<|>)\s*(.+?)\s*$""".r

  def parse(source: String): Either[String, StatsAssertion] =
    source match {
      case pattern(lhs, opSym, rhs) =>
        for {
          subject  <- parseSubject(lhs)
          op       <- Op.values.find(_.symbol == opSym).toRight(s"unknown operator $opSym")
          expected <- parseValue(rhs)
          _ <- (subject, expected) match {
            case (Subject.Rows | Subject.RowGroups | Subject.Nulls(_), Value.Text(_)) =>
              Left(s"'$lhs' is a count; compare it with a number")
            case _ => Right(())
          }
        } yield StatsAssertion(source.trim, subject, op, expected)
      case _ =>
        Left(
          s"expected '<subject> <op> <value>' (e.g. 'id.nulls == 0', 'rows > 0'), got '$source'"
        )
    }

  private def parseSubject(lhs: String): Either[String, Subject] =
    lhs match {
      case "rows"       => Right(Subject.Rows)
      case "row_groups" => Right(Subject.RowGroups)
      case _ =>
        lhs.lastIndexOf('.') match {
          case i if i > 0 =>
            val column = lhs.substring(0, i)
            lhs.substring(i + 1) match {
              case "nulls" => Right(Subject.Nulls(column))
              case "min"   => Right(Subject.Min(column))
              case "max"   => Right(Subject.Max(column))
              case other =>
                Left(s"unknown statistic '.$other' in '$lhs' (use .nulls, .min or .max)")
            }
          case _ =>
            Left(s"unknown subject '$lhs' (use rows, row_groups, or <column>.nulls|min|max)")
        }
    }

  private def parseValue(rhs: String): Either[String, Value] =
    if rhs.length >= 2 && rhs.startsWith("\"") && rhs.endsWith("\"") then
      Right(Value.Text(rhs.substring(1, rhs.length - 1)))
    else
      scala.util
        .Try(BigDecimal(rhs))
        .toOption
        .map(Value.Num.apply)
        .toRight(s"value '$rhs' must be a number or a double-quoted string")

  def evaluate(assertion: StatsAssertion, stats: FileStats): Result = {
    import Subject.*
    def fail(detail: String)                  = Result(assertion, ok = false, detail)
    def column(name: String)                  = stats.columns.find(_.name == name)
    def noColumn(name: String)                = fail(s"no column '$name' in this file")
    def compare(actual: Value, shown: String) = compareValues(assertion, actual, shown)

    assertion.subject match {
      case Rows      => compare(Value.Num(stats.totalRows), stats.totalRows.toString)
      case RowGroups => compare(Value.Num(stats.rowGroupCount), stats.rowGroupCount.toString)
      case Nulls(name) =>
        column(name).fold(noColumn(name)) { c =>
          if c.nullCount < 0 then fail(s"$name has no null-count statistics")
          else compare(Value.Num(c.nullCount), c.nullCount.toString)
        }
      case Min(name) =>
        column(name).fold(noColumn(name))(c =>
          c.minValue.fold(fail(s"$name has no min statistic"))(v =>
            compare(asValue(v, assertion), v)
          )
        )
      case Max(name) =>
        column(name).fold(noColumn(name))(c =>
          c.maxValue.fold(fail(s"$name has no max statistic"))(v =>
            compare(asValue(v, assertion), v)
          )
        )
    }
  }

  // Interpret a footer stat (always a string) the same way as the expected
  // value: numerically when the assertion compares against a number.
  private def asValue(stat: String, assertion: StatsAssertion): Value =
    assertion.expected match {
      case Value.Num(_) =>
        scala.util.Try(BigDecimal(stat)).toOption.fold[Value](Value.Text(stat))(Value.Num.apply)
      case Value.Text(_) => Value.Text(stat)
    }

  private def compareValues(assertion: StatsAssertion, actual: Value, shown: String): Result = {
    val cmp: Either[String, Int] = (actual, assertion.expected) match {
      case (Value.Num(a), Value.Num(e))   => Right(a.compare(e))
      case (Value.Text(a), Value.Text(e)) => Right(a.compareTo(e))
      case (Value.Text(a), Value.Num(_)) =>
        Left(s"actual value '$a' is not numeric; quote the expected value to compare as text")
      case (Value.Num(_), Value.Text(_)) => Left("cannot compare a count with text")
    }
    cmp match {
      case Left(why) => Result(assertion, ok = false, why)
      case Right(c) =>
        Result(assertion, assertion.op.holds(c), s"actual $shown")
    }
  }
}
