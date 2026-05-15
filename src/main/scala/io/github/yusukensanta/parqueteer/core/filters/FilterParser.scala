package io.github.yusukensanta.parqueteer.core.filters

import com.github.mjakubowski84.parquet4s.{Filter, Col, ValueCodecConfiguration}
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError
import org.apache.parquet.filter2.predicate.{FilterApi, FilterPredicate}
import org.apache.parquet.io.api.Binary
import scala.util.{Try, Success => TrySuccess, Failure => TryFailure}
import scala.util.parsing.combinator._

/** Parser for filter expressions.
  *
  * Supported Syntax:
  *   - Comparisons: col = v, col > v, col >= v, col < v, col <= v, col != v
  *   - BETWEEN: col BETWEEN low AND high
  *   - IN: col IN (v1, v2, ...)
  *   - NULL checks: col IS NULL, col IS NOT NULL
  *   - Logical: expr AND expr, expr OR expr, NOT expr
  *   - Parentheses: (expr)
  *   - Nested columns: parent.child.field
  *   - Values: strings (quoted), numbers, booleans
  */
class FilterParser extends JavaTokenParsers {

  def parseFilter(input: String): Try[Filter] = {
    Try(parseAll(expression, input)).flatMap {
      case Success(filter, _) => TrySuccess(filter.asInstanceOf[Filter])
      case NoSuccess(msg, _) =>
        TryFailure(
          new IllegalArgumentException(s"Filter parse error: $msg")
        )
      case _ => TryFailure(new IllegalArgumentException("Unknown parse error"))
    }
  }

  // ==================== Grammar Rules ====================

  private def expression: Parser[Filter] = orExpression

  private def orExpression: Parser[Filter] = {
    andExpression ~ rep("OR" ~> andExpression) ^^ { case first ~ rest =>
      rest.foldLeft(first)(_ || _)
    }
  }

  private def andExpression: Parser[Filter] = {
    notExpression ~ rep("AND" ~> notExpression) ^^ { case first ~ rest =>
      rest.foldLeft(first)(_ && _)
    }
  }

  private def notExpression: Parser[Filter] = {
    ("NOT" ~> primaryExpression ^^ (!_)) | primaryExpression
  }

  private def primaryExpression: Parser[Filter] = {
    betweenExpr | inExpr | isNullExpr | comparison | ("(" ~> expression <~ ")")
  }

  /** BETWEEN low AND high → col >= low && col <= high */
  private def betweenExpr: Parser[Filter] = {
    columnName ~ ("BETWEEN" ~> numericValue) ~ ("AND" ~> numericValue) ^^ {
      case col ~ low ~ high => buildBetween(col, low, high)
    }
  }

  /** IN (v1, v2, ...) — values must be homogeneous (all strings or all numbers)
    */
  private def inExpr: Parser[Filter] = {
    columnName ~ ("IN" ~> "(" ~> rep1sep(value, ",") <~ ")") ^^ {
      case col ~ vals => buildIn(col, vals)
    }
  }

  /** IS NULL / IS NOT NULL — uses Binary column predicate (correct for
    * String/optional fields)
    */
  private def isNullExpr: Parser[Filter] = {
    columnName ~ ("IS" ~> ("NOT" ~> "NULL" ^^^ false | "NULL" ^^^ true)) ^^ {
      case col ~ isNull => buildIsNull(col, isNull)
    }
  }

  private def comparison: Parser[Filter] = {
    columnName ~ operator ~ value ^^ { case col ~ op ~ v =>
      buildComparison(col, op, v)
    }
  }

  /** Allow dotted paths: user.address.city */
  private def columnName: Parser[String] = {
    ident ~ rep("." ~> ident) ^^ { case head ~ tail =>
      (head :: tail).mkString(".")
    }
  }

  private def operator: Parser[String] = "=" | ">=" | ">" | "<=" | "<" | "!="

  private def value: Parser[Any] = {
    stringLiteral ^^ { s => s.substring(1, s.length - 1) } |
      floatingPointNumber ^^ { _.toDouble } |
      wholeNumber ^^ { _.toLong } |
      "true" ^^^ true |
      "false" ^^^ false
  }

  private def numericValue: Parser[AnyVal] = {
    floatingPointNumber ^^ { _.toDouble } |
      wholeNumber ^^ { _.toLong }
  }

  // ==================== Filter Construction ====================

  private def buildComparison(
      column: String,
      operator: String,
      value: Any
  ): Filter = {
    val col = Col(column)
    operator match {
      case "=" =>
        value match {
          case s: String  => col === s
          case l: Long    => col === l
          case d: Double  => col === d
          case b: Boolean => col === b
          case _          => col === value.toString
        }
      case "!=" =>
        value match {
          case s: String  => col !== s
          case l: Long    => col !== l
          case d: Double  => col !== d
          case b: Boolean => col !== b
          case _          => col !== value.toString
        }
      case ">" =>
        value match {
          case l: Long   => col > l
          case d: Double => col > d
          case _ =>
            throw new IllegalArgumentException(
              s"Operator '>' requires a numeric value, got: ${value.getClass.getSimpleName} '$value'"
            )
        }
      case ">=" =>
        value match {
          case l: Long   => col >= l
          case d: Double => col >= d
          case _ =>
            throw new IllegalArgumentException(
              s"Operator '>=' requires a numeric value, got: ${value.getClass.getSimpleName} '$value'"
            )
        }
      case "<" =>
        value match {
          case l: Long   => col < l
          case d: Double => col < d
          case _ =>
            throw new IllegalArgumentException(
              s"Operator '<' requires a numeric value, got: ${value.getClass.getSimpleName} '$value'"
            )
        }
      case "<=" =>
        value match {
          case l: Long   => col <= l
          case d: Double => col <= d
          case _ =>
            throw new IllegalArgumentException(
              s"Operator '<=' requires a numeric value, got: ${value.getClass.getSimpleName} '$value'"
            )
        }
      case _ =>
        throw new IllegalArgumentException(s"Unknown operator: $operator")
    }
  }

  private def buildBetween(
      column: String,
      low: AnyVal,
      high: AnyVal
  ): Filter = {
    val col = Col(column)
    (low, high) match {
      case (l: Long, h: Long)     => (col >= l) && (col <= h)
      case (l: Double, h: Double) => (col >= l) && (col <= h)
      case (l: Long, h: Double)   => (col >= l.toDouble) && (col <= h)
      case (l: Double, h: Long)   => (col >= l) && (col <= h.toDouble)
      case _ =>
        throw new IllegalArgumentException(
          s"BETWEEN requires numeric bounds, got: $low AND $high"
        )
    }
  }

  private def buildIn(column: String, values: List[Any]): Filter = {
    val col = Col(column)
    val strings = values.collect { case s: String => s }
    val longs = values.collect { case l: Long => l }
    val doubles = values.collect { case d: Double => d }

    if (strings.length == values.length) col.in(strings)
    else if (longs.length == values.length) col.in(longs)
    else if (doubles.length == values.length) col.in(doubles)
    else if (longs.length + doubles.length == values.length) {
      val asDoubles = values.map {
        case l: Long   => l.toDouble
        case d: Double => d
        case v => throw new IllegalArgumentException(s"Unexpected IN value: $v")
      }
      col.in(asDoubles)
    } else
      throw new IllegalArgumentException(
        "IN list must contain values of the same type (all strings or all numbers)"
      )
  }

  /** IS NULL / IS NOT NULL using Apache Parquet's binary column null predicate.
    * Works correctly for String/Binary (optional) columns.
    */
  private def buildIsNull(column: String, isNull: Boolean): Filter =
    new Filter {
      def toPredicate(vcc: ValueCodecConfiguration): FilterPredicate = {
        val binaryCol = FilterApi.binaryColumn(column)
        if (isNull) FilterApi.eq(binaryCol, null.asInstanceOf[Binary])
        else FilterApi.notEq(binaryCol, null.asInstanceOf[Binary])
      }
    }
}

object FilterParser {
  private val parser = new FilterParser()

  def parse(
      filterExpr: String
  ): Either[ParqueteerError.FilterParseError, Filter] = {
    parser.parseFilter(filterExpr) match {
      case TrySuccess(filter) => Right(filter)
      case TryFailure(ex) =>
        Left(ParqueteerError.FilterParseError(filterExpr, ex.getMessage))
    }
  }
}
