package io.github.yusukensanta.parqueteer.core.filters

import com.github.mjakubowski84.parquet4s.{Col, Filter, ValueCodecConfiguration}
import io.github.yusukensanta.parqueteer.core.models.ParqueteerError
import org.apache.parquet.filter2.predicate.{FilterApi, FilterPredicate}
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import scala.jdk.CollectionConverters._
import scala.util.Try

type FilterValue = Long | Double | String | Boolean
private type NumericValue = Long | Double

object FilterParser {

  def parse(
      filterExpr: String
  ): Either[ParqueteerError.FilterParseError, Filter] =
    new FilterParserImpl(None).parse(filterExpr) match {
      case Right(f)  => Right(f)
      case Left(msg) => Left(ParqueteerError.FilterParseError(filterExpr, msg))
    }

  def parseWithSchema(
      filterExpr: String,
      schema: MessageType
  ): Either[ParqueteerError.FilterParseError, Filter] =
    new FilterParserImpl(Some(schema)).parse(filterExpr) match {
      case Right(f)  => Right(f)
      case Left(msg) => Left(ParqueteerError.FilterParseError(filterExpr, msg))
    }
}

private class FilterParserImpl(schema: Option[MessageType]) {

  // ── Tokens ────────────────────────────────────────────────────────────────

  private enum Token:
    case Kw(name: String)
    case Id(name: String)
    case Str(value: String)
    case Lng(value: scala.Long)
    case Dbl(value: scala.Double)
    case Op(sym: String)
    case LParen, RParen, Comma, Dot, Eof

  private val keywords =
    Set("AND", "OR", "NOT", "BETWEEN", "IN", "IS", "NULL", "TRUE", "FALSE")

  private def tokenize(input: String): Either[String, Vector[Token]] = Try {
    val buf = scala.collection.mutable.ListBuffer.empty[Token]
    var i = 0
    val n = input.length

    def skipWS(): Unit = while (i < n && input(i).isWhitespace) i += 1

    skipWS()
    while (i < n) {
      input(i) match {
        case '(' => buf += Token.LParen; i += 1
        case ')' => buf += Token.RParen; i += 1
        case ',' => buf += Token.Comma; i += 1
        case '.' => buf += Token.Dot; i += 1
        case '"' =>
          val start = i
          i += 1
          val sb = new StringBuilder
          while (i < n && input(i) != '"') {
            if (input(i) == '\\' && i + 1 < n) {
              sb.append(input(i + 1)); i += 2
            } else { sb.append(input(i)); i += 1 }
          }
          if (i >= n)
            throw new IllegalArgumentException(
              s"Unterminated string literal starting at position $start in filter expression"
            )
          i += 1
          buf += Token.Str(sb.toString)
        case '!' if i + 1 < n && input(i + 1) == '=' =>
          buf += Token.Op("!="); i += 2
        case '>' if i + 1 < n && input(i + 1) == '=' =>
          buf += Token.Op(">="); i += 2
        case '<' if i + 1 < n && input(i + 1) == '=' =>
          buf += Token.Op("<="); i += 2
        case '>' => buf += Token.Op(">"); i += 1
        case '<' => buf += Token.Op("<"); i += 1
        case '=' => buf += Token.Op("="); i += 1
        case c if c.isLetter || c == '_' =>
          val start = i
          while (i < n && (input(i).isLetterOrDigit || input(i) == '_')) i += 1
          val word = input.substring(start, i)
          if (keywords.contains(word.toUpperCase))
            buf += Token.Kw(word.toUpperCase)
          else buf += Token.Id(word)
        case c
            if c.isDigit || (c == '-' && i + 1 < n && input(i + 1).isDigit &&
              buf.lastOption.forall {
                case _: Token.Op | _: Token.Kw | Token.LParen | Token.Comma =>
                  true
                case _ => false
              }) =>
          val start = i
          if (c == '-') i += 1
          while (i < n && input(i).isDigit) i += 1
          if (i < n && input(i) == '.' && i + 1 < n && input(i + 1).isDigit) {
            i += 1
            while (i < n && input(i).isDigit) i += 1
            if (i < n && (input(i) == 'e' || input(i) == 'E')) {
              i += 1
              if (i < n && (input(i) == '+' || input(i) == '-')) i += 1
              while (i < n && input(i).isDigit) i += 1
            }
            buf += Token.Dbl(input.substring(start, i).toDouble)
          } else {
            val raw = input.substring(start, i)
            val l =
              try raw.toLong
              catch {
                case _: NumberFormatException =>
                  throw new IllegalArgumentException(
                    s"numeric literal '$raw' is out of Long range; add a decimal point to use Double (e.g. ${raw}.0)"
                  )
              }
            buf += Token.Lng(l)
          }
        case c =>
          throw new IllegalArgumentException(
            s"unexpected character '${c}' at position $i — use AND/OR (not &/|), or check for smart quotes"
          )
      }
      skipWS()
    }
    buf += Token.Eof
    buf.toVector
  }.toEither.left.map(_.getMessage)

  // ── Parser state ──────────────────────────────────────────────────────────

  private var tokens: Vector[Token] = Vector.empty
  private var pos: Int = 0

  private def peek: Token = if (pos < tokens.length) tokens(pos) else Token.Eof
  private def advance(): Token = { val t = peek; pos += 1; t }

  // ── Entry point ───────────────────────────────────────────────────────────

  def parse(input: String): Either[String, Filter] =
    if (input.trim.isEmpty) Left("Filter parse error: empty input")
    else
      tokenize(input) match {
        case Left(err) => Left(s"Filter parse error: $err")
        case Right(toks) =>
          tokens = toks; pos = 0
          parseExpression().flatMap { f =>
            if (peek == Token.Eof) Right(f)
            else Left(s"Filter parse error: unexpected token after expression")
          }
      }

  // ── Grammar ───────────────────────────────────────────────────────────────

  private def parseExpression(): Either[String, Filter] = parseOr()

  private def parseOr(): Either[String, Filter] = {
    var result = parseAnd()
    while (peek == Token.Kw("OR") && result.isRight) {
      advance()
      result = for { l <- result; r <- parseAnd() } yield l || r
    }
    result
  }

  private def parseAnd(): Either[String, Filter] = {
    var result = parseNot()
    while (peek == Token.Kw("AND") && result.isRight) {
      advance()
      result = for { l <- result; r <- parseNot() } yield l && r
    }
    result
  }

  private def parseNot(): Either[String, Filter] =
    if (peek == Token.Kw("NOT")) { advance(); parsePrimary().map(!_) }
    else parsePrimary()

  private def parsePrimary(): Either[String, Filter] =
    peek match {
      case Token.LParen =>
        advance()
        parseExpression().flatMap { f =>
          if (peek == Token.RParen) { advance(); Right(f) }
          else Left("Filter parse error: missing closing ')'")
        }
      case Token.Id(_) => parseAtom()
      case other       => Left(s"Filter parse error: unexpected token '$other'")
    }

  private def parseAtom(): Either[String, Filter] =
    parseColumnName().flatMap { col =>
      peek match {
        case Token.Kw("BETWEEN") => parseBetween(col)
        case Token.Kw("IN")      => parseIn(col)
        case Token.Kw("IS")      => parseIsNull(col)
        case Token.Op(op) =>
          advance()
          parseValue().flatMap(v => buildComparison(col, op, v))
        case other =>
          Left(
            s"Filter parse error: expected operator after column '$col', got '$other'"
          )
      }
    }

  private def parseColumnName(): Either[String, String] =
    peek match {
      case Token.Id(name) =>
        advance()
        var parts = List(name)
        var err: Option[String] = None
        while (peek == Token.Dot && err.isEmpty) {
          advance()
          peek match {
            case Token.Id(n) => advance(); parts = parts :+ n
            case other =>
              err = Some(
                s"Filter parse error: expected identifier after '.', got '$other'"
              )
          }
        }
        err.toLeft(parts.mkString("."))
      case other =>
        Left(s"Filter parse error: expected column name, got '$other'")
    }

  private def parseValue(): Either[String, FilterValue] =
    peek match {
      case Token.Str(s)      => advance(); Right(s)
      case Token.Kw("TRUE")  => advance(); Right(true)
      case Token.Kw("FALSE") => advance(); Right(false)
      case Token.Lng(l)      => advance(); Right(l)
      case Token.Dbl(d)      => advance(); Right(d)
      case other => Left(s"Filter parse error: expected value, got '$other'")
    }

  private def parseNumericValue(): Either[String, NumericValue] =
    peek match {
      case Token.Lng(l) => advance(); Right(l)
      case Token.Dbl(d) => advance(); Right(d)
      case other =>
        Left(s"Filter parse error: expected numeric value, got '$other'")
    }

  private def parseBetween(col: String): Either[String, Filter] = {
    advance() // consume BETWEEN
    for {
      low <- parseNumericValue()
      _ <-
        if (peek == Token.Kw("AND")) Right(advance())
        else Left(s"Filter parse error: expected AND in BETWEEN, got '$peek'")
      high <- parseNumericValue()
      f <- buildBetween(col, low, high)
    } yield f
  }

  private def parseIn(col: String): Either[String, Filter] = {
    advance() // consume IN
    if (peek != Token.LParen)
      Left(s"Filter parse error: expected '(' after IN, got '$peek'")
    else {
      advance()
      val vals = scala.collection.mutable.ListBuffer.empty[FilterValue]
      var err: Option[String] = None
      parseValue() match {
        case Left(e)  => err = Some(e)
        case Right(v) => vals += v
      }
      while (err.isEmpty && peek == Token.Comma) {
        advance()
        parseValue() match {
          case Left(e)  => err = Some(e)
          case Right(v) => vals += v
        }
      }
      err match {
        case Some(e) => Left(e)
        case None =>
          if (peek != Token.RParen)
            Left(
              s"Filter parse error: expected ')' after IN list, got '$peek'"
            )
          else { advance(); buildIn(col, vals.toList) }
      }
    }
  }

  private def parseIsNull(col: String): Either[String, Filter] = {
    advance() // consume IS
    val isNull = if (peek == Token.Kw("NOT")) { advance(); false }
    else true
    if (peek == Token.Kw("NULL")) {
      advance(); Right(buildIsNullFilter(col, isNull))
    } else Left(s"Filter parse error: expected NULL after IS, got '$peek'")
  }

  // ── Filter construction ───────────────────────────────────────────────────

  private def buildComparison(
      col: String,
      op: String,
      v: FilterValue
  ): Either[String, Filter] = {
    val c = Col(col)

    // Equality / inequality work for all value types — strings, numbers, bools.
    def equality(eq: Boolean): Filter = v match {
      case s: String  => if (eq) c === s else c !== s
      case l: Long    => if (eq) c === l else c !== l
      case d: Double  => if (eq) c === d else c !== d
      case b: Boolean => if (eq) c === b else c !== b
    }

    // Ordered comparisons only make sense for numeric values; everything else
    // produces a typed error message that names the operator and value type.
    def numericOnly(
        opName: String,
        onLong: Long => Filter,
        onDouble: Double => Filter
    ): Either[String, Filter] = v match {
      case l: Long   => Right(onLong(l))
      case d: Double => Right(onDouble(d))
      case _ =>
        Left(
          s"Operator '$opName' requires a numeric value, got: ${v.getClass.getSimpleName} '$v'"
        )
    }

    op match {
      case "="  => Right(equality(eq = true))
      case "!=" => Right(equality(eq = false))
      case ">"  => numericOnly(">", c > _, c > _)
      case ">=" => numericOnly(">=", c >= _, c >= _)
      case "<"  => numericOnly("<", c < _, c < _)
      case "<=" => numericOnly("<=", c <= _, c <= _)
      case _    => Left(s"Unknown operator: $op")
    }
  }

  private def buildBetween(
      col: String,
      low: NumericValue,
      high: NumericValue
  ): Either[String, Filter] = {
    val c = Col(col)
    (low, high) match {
      case (l: Long, h: Long) if l > h =>
        Left(
          s"Filter parse error: BETWEEN range is empty ($l > $h). Write 'col BETWEEN $h AND $l' for the [$h, $l] range."
        )
      case (l: Double, h: Double) if l > h =>
        Left(
          s"Filter parse error: BETWEEN range is empty ($l > $h)."
        )
      case (l: Long, h: Double) if l.toDouble > h =>
        Left(
          s"Filter parse error: BETWEEN range is empty ($l > $h)."
        )
      case (l: Double, h: Long) if l > h.toDouble =>
        Left(
          s"Filter parse error: BETWEEN range is empty ($l > $h)."
        )
      case (l: Long, h: Long)     => Right((c >= l) && (c <= h))
      case (l: Double, h: Double) => Right((c >= l) && (c <= h))
      case (l: Long, h: Double)   => Right((c >= l.toDouble) && (c <= h))
      case (l: Double, h: Long)   => Right((c >= l) && (c <= h.toDouble))
    }
  }

  private def buildIn(
      col: String,
      values: List[FilterValue]
  ): Either[String, Filter] = {
    val c = Col(col)
    val strings = values.collect { case s: String => s }
    val longs = values.collect { case l: Long => l }
    val doubles = values.collect { case d: Double => d }
    val booleans = values.collect { case b: Boolean => b }

    if (strings.length == values.length) Right(c.in(strings))
    else if (longs.length == values.length) Right(c.in(longs))
    else if (doubles.length == values.length) Right(c.in(doubles))
    else if (booleans.length == values.length)
      Right(booleans.distinct.map(b => c === b).reduce(_ || _))
    else if (longs.length + doubles.length == values.length) {
      val asDoubles = values.collect {
        case l: Long   => l.toDouble
        case d: Double => d
      }
      Right(c.in(asDoubles))
    } else
      Left(
        "IN list must contain values of the same type (all strings, all numbers, or all booleans)"
      )
  }

  private def buildIsNullFilter(col: String, isNull: Boolean): Filter =
    new Filter {
      def toPredicate(vcc: ValueCodecConfiguration): FilterPredicate = {
        // Build a typed null-comparison predicate. The column factory and the
        // boxed null literal must match the physical Parquet type, so we
        // resolve the type from the schema (defaulting to BINARY) and dispatch.
        def nullEq[C <: org.apache.parquet.filter2.predicate.Operators.Column[
          T
        ] & org.apache.parquet.filter2.predicate.Operators.SupportsEqNotEq, T <: Comparable[
          T
        ]](colRef: C, nullLit: T): FilterPredicate =
          if (isNull) FilterApi.eq(colRef, nullLit)
          else FilterApi.notEq(colRef, nullLit)

        schema
          .flatMap { s =>
            val resolved = resolveColumnType(s, col)
            if (resolved.isEmpty)
              Console.err.println(
                s"[parqueteer] warning: IS NULL predicate on column '$col' not found in schema; defaulting to BINARY"
              )
            resolved
          }
          .getOrElse(PrimitiveTypeName.BINARY) match {
          case PrimitiveTypeName.INT32 =>
            nullEq(
              FilterApi.intColumn(col),
              null.asInstanceOf[java.lang.Integer]
            )
          case PrimitiveTypeName.INT64 =>
            nullEq(FilterApi.longColumn(col), null.asInstanceOf[java.lang.Long])
          case PrimitiveTypeName.FLOAT =>
            nullEq(
              FilterApi.floatColumn(col),
              null.asInstanceOf[java.lang.Float]
            )
          case PrimitiveTypeName.DOUBLE =>
            nullEq(
              FilterApi.doubleColumn(col),
              null.asInstanceOf[java.lang.Double]
            )
          case PrimitiveTypeName.BOOLEAN =>
            nullEq(
              FilterApi.booleanColumn(col),
              null.asInstanceOf[java.lang.Boolean]
            )
          case _ =>
            nullEq(FilterApi.binaryColumn(col), null.asInstanceOf[Binary])
        }
      }
    }

  private def resolveColumnType(
      messageType: MessageType,
      column: String
  ): Option[PrimitiveTypeName] = Try {
    val parts = column.split("\\.")
    var current: Option[org.apache.parquet.schema.Type] = None
    var idx = 0
    var found = true
    while (idx < parts.length && found) {
      val group =
        current.map(_.asGroupType()).getOrElse(messageType.asGroupType())
      current = group.getFields.asScala.find(_.getName == parts(idx))
      found = current.isDefined
      idx += 1
    }
    current.collect {
      case f if f.isPrimitive => f.asPrimitiveType().getPrimitiveTypeName
    }
  }.getOrElse(None)
}
