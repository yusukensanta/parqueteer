package com.github.yusukensanta.parqueteer.core.filters

import com.github.mjakubowski84.parquet4s.{Filter, Col}
import scala.util.{Try, Success => TrySuccess, Failure => TryFailure}
import scala.util.parsing.combinator._

/** Parser for filter expressions
  *
  * Supported Syntax:
  *   - Comparisons: column = value, column > value, column >= value, column <
  *     value, column <= value
  *   - Logical: expr AND expr, expr OR expr, NOT expr
  *   - Parentheses: (expr)
  *   - Values: strings (quoted), numbers, booleans
  *
  * Examples:
  *   - age > 25
  *   - name = "John Doe"
  *   - age > 25 AND salary >= 50000
  *   - (age > 25 OR age < 18) AND active = true
  */
class FilterParser extends JavaTokenParsers {

  /** Main entry point: parse filter expression
    */
  def parseFilter(input: String): Try[Filter] = {
    parseAll(expression, input) match {
      case Success(filter, _) => TrySuccess(filter.asInstanceOf[Filter])
      case NoSuccess(msg, _) =>
        TryFailure(
          new IllegalArgumentException(s"Filter parse error: $msg")
        )
      case _ => TryFailure(new IllegalArgumentException("Unknown parse error"))
    }
  }

  // ==================== Grammar Rules ====================

  /** expression := orExpression
    */
  private def expression: Parser[Filter] = orExpression

  /** orExpression := andExpression (OR andExpression)*
    */
  private def orExpression: Parser[Filter] = {
    andExpression ~ rep("OR" ~> andExpression) ^^ { case first ~ rest =>
      rest.foldLeft(first) { (acc, filter) =>
        acc || filter
      }
    }
  }

  /** andExpression := notExpression (AND notExpression)*
    */
  private def andExpression: Parser[Filter] = {
    notExpression ~ rep("AND" ~> notExpression) ^^ { case first ~ rest =>
      rest.foldLeft(first) { (acc, filter) =>
        acc && filter
      }
    }
  }

  /** notExpression := NOT primaryExpression | primaryExpression
    */
  private def notExpression: Parser[Filter] = {
    ("NOT" ~> primaryExpression ^^ { filter =>
      !filter
    }) |
      primaryExpression
  }

  /** primaryExpression := comparison | "(" expression ")"
    */
  private def primaryExpression: Parser[Filter] = {
    comparison | ("(" ~> expression <~ ")")
  }

  /** comparison := column operator value
    */
  private def comparison: Parser[Filter] = {
    columnName ~ operator ~ value ^^ { case col ~ op ~ v =>
      buildComparison(col, op, v)
    }
  }

  /** columnName := identifier
    */
  private def columnName: Parser[String] = ident

  /** operator := "=" | ">" | ">=" | "<" | "<=" | "!="
    */
  private def operator: Parser[String] = {
    "=" | ">=" | ">" | "<=" | "<" | "!="
  }

  /** value := string | number | boolean
    */
  private def value: Parser[Any] = {
    stringLiteral ^^ { s => s.substring(1, s.length - 1) } | // Remove quotes
      floatingPointNumber ^^ { _.toDouble } |
      wholeNumber ^^ { _.toLong } |
      "true" ^^^ true |
      "false" ^^^ false
  }

  // ==================== Filter Construction ====================

  /** Build parquet4s Filter from parsed components using Col() API
    */
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
          case l: Long    => col === l.toInt
          case d: Double  => col === d
          case b: Boolean => col === b
          case _          => col === value.toString
        }
      case "!=" =>
        value match {
          case s: String  => col !== s
          case l: Long    => col !== l.toInt
          case d: Double  => col !== d
          case b: Boolean => col !== b
          case _          => col !== value.toString
        }
      case ">" =>
        value match {
          case l: Long   => col > l.toInt
          case d: Double => col > d
          case _         => Filter.noopFilter
        }
      case ">=" =>
        value match {
          case l: Long   => col >= l.toInt
          case d: Double => col >= d
          case _         => Filter.noopFilter
        }
      case "<" =>
        value match {
          case l: Long   => col < l.toInt
          case d: Double => col < d
          case _         => Filter.noopFilter
        }
      case "<=" =>
        value match {
          case l: Long   => col <= l.toInt
          case d: Double => col <= d
          case _         => Filter.noopFilter
        }
      case _ => Filter.noopFilter
    }
  }

  /** Convert parsed value to parquet4s-compatible type
    */
  @annotation.nowarn("msg=unused")
  private def convertValue(value: Any): Any = value match {
    case s: String  => s
    case l: Long    => l.toInt // parquet4s uses Int for integer columns
    case d: Double  => d
    case b: Boolean => b
    case other      => other
  }
}

/** Companion object with convenient parse method
  */
object FilterParser {
  private val parser = new FilterParser()

  /** Parse filter expression string into parquet4s Filter
    */
  def parse(filterExpr: String): Either[String, Filter] = {
    parser.parseFilter(filterExpr) match {
      case TrySuccess(filter) => Right(filter)
      case TryFailure(ex)     => Left(ex.getMessage)
    }
  }
}
