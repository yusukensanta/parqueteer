package io.github.yusukensanta.parqueteer.core.models

import io.github.yusukensanta.parqueteer.core.util.CredentialRedactor
import scala.util.Try

/**
 * Application error ADT with stable exit codes and user-facing messages.
 * Companion object also holds `RuntimeException` subclasses that bridge
 * the throwing layer (`Try`) to the result layer (`Either`).
 */
sealed trait ParqueteerError:
  def userMessage: String
  def exitCode: Int

object ParqueteerError:

  case class FileNotFound(path: String) extends ParqueteerError:
    val exitCode = 3

    val userMessage =
      s"File not found: $path\nCheck the path exists and you have read permission."

  case class NoGlobMatch(pattern: String) extends ParqueteerError:
    val exitCode = 3

    val userMessage =
      s"No files matched glob pattern: $pattern\nCheck the pattern and that the bucket/directory path is correct."

  /** Input schemas can't be combined (strict mismatch, union type conflict, duplicate columns). */
  case class SchemaMismatch(path: String, detail: String) extends ParqueteerError:
    val exitCode = 4

    val userMessage =
      s"Schema mismatch at '$path': ${redact(detail)}"

  case class FilterParseError(expression: String, message: String) extends ParqueteerError:
    val exitCode = 7

    val userMessage =
      s"""Invalid filter expression: "$expression"\n$message\nRun with --help to see supported filter syntax."""

  private def redact(s: String): String =
    CredentialRedactor.redact(s)

  case class CloudAuthError(provider: String, message: String) extends ParqueteerError:
    val exitCode = 5

    val userMessage =
      s"Cloud authentication failed ($provider): ${redact(message)}\nCheck your credentials and environment variables."

  case class InvalidFormat(format: String, message: String) extends ParqueteerError:
    val exitCode = 6

    val userMessage =
      s"""Unsupported format: "$format"\n${redact(
          message
        )}\nRun with --help to see supported formats."""

  case class IOError(cause: Throwable) extends ParqueteerError:
    val exitCode = 1

    val userMessage =
      s"I/O error: ${redact(Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName))}"

  case class ParseError(format: String, message: String) extends ParqueteerError:
    val exitCode    = 2
    val userMessage = s"Parse error ($format): ${redact(message)}"

  /**
   * A path/URL that isn't a usable storage location: an unsupported scheme
   * (ftp://), a malformed URL (s3:/bucket), or wasb[s]://. The parser's
   * message already says what is wrong and, where it can, what to use instead.
   */
  case class InvalidLocation(path: String, message: String) extends ParqueteerError:
    val exitCode    = 10
    val userMessage = s"Invalid location \"$path\": ${redact(message)}"

  /** The output path already exists; parqueteer never overwrites an existing file. */
  case class OutputExists(path: String) extends ParqueteerError:
    val exitCode = 8

    val userMessage =
      s"Output file already exists: $path\nRemove it first or choose a different output path."

  /**
   * A well-formed request the tool can't carry out (e.g. merging nested
   * columns, reading parquet from stdin). Distinct from InvalidFormat, whose
   * message points the user at the list of supported formats.
   */
  case class UnsupportedOperation(subject: String, message: String) extends ParqueteerError:
    val exitCode    = 9
    val userMessage = s"Unsupported operation ($subject): ${redact(message)}"

  class CloudAuthException(
      val provider: String,
      message: String,
      cause: Throwable = null
  ) extends RuntimeException(message, cause)

  class FilterParseException(val expression: String, message: String)
      extends RuntimeException(message)

  /**
   * Thrown when a row field is missing from the write schema — maps to
   * ParseError rather than the generic InvalidFormat to produce a clear "Parse
   * error (input): ..." message.
   */
  class RowSchemaMismatchException(message: String) extends IllegalArgumentException(message)

  extension [A](t: Try[A])

    def toParqueteerError: Either[ParqueteerError, A] =
      t.toEither.left.map {
        case e: FilterParseException =>
          FilterParseError(e.expression, e.getMessage)
        case e: CloudAuthException => CloudAuthError(e.provider, e.getMessage)
        // RowSchemaMismatchException extends IllegalArgumentException — must precede it.
        case e: RowSchemaMismatchException =>
          ParseError(
            "input",
            Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
          )
        case e: IllegalArgumentException =>
          ParseError(
            "data",
            Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
          )
        case e: java.io.FileNotFoundException =>
          val raw = Option(e.getMessage).getOrElse("unknown")
          // getMessage is typically "path (No such file or directory)" — extract just the path
          val path = raw.indexOf(" (") match {
            case -1 => raw
            case i  => raw.substring(0, i)
          }
          FileNotFound(path)
        case e => IOError(e)
      }
