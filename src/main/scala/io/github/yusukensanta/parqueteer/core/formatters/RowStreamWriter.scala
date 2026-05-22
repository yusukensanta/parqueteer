package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.OutputFormat
import io.circe.Json
import java.io.PrintStream

trait RowStreamWriter {
  def begin(): Unit = ()
  def writeRow(row: Map[String, Any]): Unit
  def end(): Unit = ()
}

object RowStreamWriter {
  private val SampleSize = 50

  def apply(format: OutputFormat, out: PrintStream): RowStreamWriter =
    format match {
      case OutputFormat.NDJSON | OutputFormat.Pretty =>
        new NDJSONRowStreamWriter(out)
      case OutputFormat.JSON     => new JSONRowStreamWriter(out)
      case OutputFormat.CSV      => new CSVRowStreamWriter(out)
      case OutputFormat.Table    => new TableRowStreamWriter(out)
      case OutputFormat.Markdown => new MarkdownRowStreamWriter(out)
    }

  private def encodeAny(v: Any): Json =
    io.github.yusukensanta.parqueteer.core.util.JsonEncoder.encodeAny(v)

  private def rowToJson(row: Map[String, Any]): String =
    Json.obj(row.view.mapValues(encodeAny).toSeq*).noSpaces

  private class NDJSONRowStreamWriter(out: PrintStream)
      extends RowStreamWriter {
    override def writeRow(row: Map[String, Any]): Unit =
      out.println(rowToJson(row))
  }

  private class JSONRowStreamWriter(out: PrintStream) extends RowStreamWriter {
    private var first = true
    override def begin(): Unit = out.print("[")
    override def writeRow(row: Map[String, Any]): Unit = {
      if (!first) out.print(",")
      out.print(rowToJson(row))
      first = false
    }
    override def end(): Unit = out.println("]")
  }

  private class CSVRowStreamWriter(out: PrintStream) extends RowStreamWriter {
    private var columns: List[String] = Nil
    private def escape(s: String): String = {
      val str = if (s == null) "" else s
      if (
        str.contains(",") || str.contains("\"") || str.contains("\n") || str
          .contains("\r")
      )
        "\"" + str.replace("\"", "\"\"") + "\""
      else str
    }
    override def writeRow(row: Map[String, Any]): Unit = {
      if (columns.isEmpty) {
        columns = row.keys.toList.sorted
        out.println(columns.mkString(","))
      }
      out.println(
        columns
          .map(c =>
            escape(Option(row.getOrElse(c, null)).map(_.toString).getOrElse(""))
          )
          .mkString(",")
      )
    }
  }

  private class TableRowStreamWriter(out: PrintStream) extends RowStreamWriter {
    private val tf = new TableFormatter()
    private val sample: scala.collection.mutable.ListBuffer[Map[String, Any]] =
      scala.collection.mutable.ListBuffer.empty
    private var columns: List[String] = Nil
    private var widths: List[Int] = Nil
    private var flushed = false

    private def fmtVal(v: Any): String = tf.formatValue(v)

    private def flushSample(): Unit = {
      columns = sample.flatMap(_.keys).distinct.sorted.toList
      widths = tf.calculateColumnWidths(columns, sample.toList)
      out.println(tf.drawTopBorder(widths))
      out.println(tf.drawRow(columns, widths))
      out.println(tf.drawSeparator(widths))
      sample.foreach(r =>
        out.println(
          tf.drawRow(columns.map(c => fmtVal(r.getOrElse(c, null))), widths)
        )
      )
      flushed = true
    }

    override def writeRow(row: Map[String, Any]): Unit = {
      if (!flushed) {
        sample += row
        if (sample.size >= SampleSize) flushSample()
      } else {
        out.println(
          tf.drawRow(columns.map(c => fmtVal(row.getOrElse(c, null))), widths)
        )
      }
    }

    override def end(): Unit = {
      if (!flushed) flushSample()
      if (widths.nonEmpty) out.println(tf.drawBottomBorder(widths))
    }
  }

  private class MarkdownRowStreamWriter(out: PrintStream)
      extends RowStreamWriter {
    private val sample: scala.collection.mutable.ListBuffer[Map[String, Any]] =
      scala.collection.mutable.ListBuffer.empty
    private var columns: List[String] = Nil
    private var flushed = false

    private def cell(v: Any): String =
      Option(v)
        .map(_.toString)
        .getOrElse("")
        .replace("|", "\\|")
        .replace("\n", " ")

    private def flushSample(): Unit = {
      columns = sample.flatMap(_.keys).distinct.sorted.toList
      out.println("| " + columns.mkString(" | ") + " |")
      out.println("| " + columns.map(_ => "---").mkString(" | ") + " |")
      sample.foreach(r =>
        out.println(
          "| " + columns
            .map(c => cell(r.getOrElse(c, null)))
            .mkString(" | ") + " |"
        )
      )
      flushed = true
    }

    override def writeRow(row: Map[String, Any]): Unit = {
      if (!flushed) {
        sample += row
        if (sample.size >= SampleSize) flushSample()
      } else {
        out.println(
          "| " + columns
            .map(c => cell(row.getOrElse(c, null)))
            .mkString(" | ") + " |"
        )
      }
    }

    override def end(): Unit = {
      if (!flushed) flushSample()
    }
  }
}
