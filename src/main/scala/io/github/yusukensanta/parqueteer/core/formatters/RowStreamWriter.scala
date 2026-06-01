package io.github.yusukensanta.parqueteer.core.formatters

import io.github.yusukensanta.parqueteer.core.models.{CellValue, OutputFormat}
import io.circe.Json
import java.io.PrintStream

trait RowStreamWriter {
  def begin(): Unit = ()
  def writeRow(row: Map[String, CellValue]): Unit
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
      case OutputFormat.LTSV     => new LTSVRowStreamWriter(out)
    }

  private def rowToJson(row: Map[String, CellValue]): String = {
    import io.github.yusukensanta.parqueteer.core.util.JsonEncoder
    Json.obj(row.view.mapValues(JsonEncoder.encode).toSeq*).noSpaces
  }

  private class NDJSONRowStreamWriter(out: PrintStream)
      extends RowStreamWriter {
    override def writeRow(row: Map[String, CellValue]): Unit =
      out.println(rowToJson(row))
  }

  private class JSONRowStreamWriter(out: PrintStream) extends RowStreamWriter {
    private var first = true
    override def begin(): Unit = out.print("[")
    override def writeRow(row: Map[String, CellValue]): Unit = {
      if (!first) out.print(",")
      out.print(rowToJson(row))
      first = false
    }
    override def end(): Unit = out.println("]")
  }

  private class CSVRowStreamWriter(out: PrintStream) extends RowStreamWriter {
    private var columns: List[String] = Nil
    private var columnsSet: Set[String] = Set.empty
    private var warnedUnseen = false
    private def escape(s: String): String = {
      if (
        s.contains(",") || s.contains("\"") || s.contains("\n") || s
          .contains("\r")
      )
        "\"" + s.replace("\"", "\"\"") + "\""
      else s
    }
    override def writeRow(row: Map[String, CellValue]): Unit = {
      if (columns.isEmpty) {
        val seen = scala.collection.mutable.LinkedHashSet.empty[String]
        row.keysIterator.foreach(seen += _)
        columns = seen.toList
        columnsSet = columns.toSet
        out.println(columns.map(escape).mkString(","))
      } else if (!warnedUnseen) {
        val unseen = row.keySet -- columnsSet
        if (unseen.nonEmpty) {
          Console.err.println(
            s"[parqueteer] warning: CSV writer dropping unseen column keys: ${unseen.mkString(", ")}"
          )
          warnedUnseen = true
        }
      }
      out.println(
        columns
          .map(c => escape(row.get(c).fold("")(_.display)))
          .mkString(",")
      )
    }
  }

  private class TableRowStreamWriter(out: PrintStream) extends RowStreamWriter {
    private val tf = new TableFormatter()
    private val sample
        : scala.collection.mutable.ListBuffer[Map[String, CellValue]] =
      scala.collection.mutable.ListBuffer.empty
    private var columns: List[String] = Nil
    private var columnsSet: Set[String] = Set.empty
    private var widths: List[Int] = Nil
    private var flushed = false
    private var warnedUnseen = false

    private def flushSample(): Unit = {
      columns = {
        val seen = scala.collection.mutable.LinkedHashSet.empty[String]
        sample.foreach(_.keysIterator.foreach(seen += _))
        seen.toList
      }
      columnsSet = columns.toSet
      widths = tf.calculateColumnWidths(columns, sample.toList)
      out.println(tf.drawTopBorder(widths))
      out.println(tf.drawRow(columns, widths))
      out.println(tf.drawSeparator(widths))
      sample.foreach(r =>
        out.println(
          tf.drawRow(
            columns.map(c => r.getOrElse(c, CellValue.Null).display),
            widths
          )
        )
      )
      flushed = true
    }

    override def writeRow(row: Map[String, CellValue]): Unit = {
      if (!flushed) {
        sample += row
        if (sample.size >= SampleSize) flushSample()
      } else {
        if (!warnedUnseen) {
          val unseen = row.keySet -- columnsSet
          if (unseen.nonEmpty) {
            Console.err.println(
              s"[parqueteer] warning: table writer dropping unseen column keys: ${unseen.mkString(", ")}"
            )
            warnedUnseen = true
          }
        }
        out.println(
          tf.drawRow(
            columns.map(c => row.getOrElse(c, CellValue.Null).display),
            widths
          )
        )
      }
    }

    override def end(): Unit = {
      if (!flushed) flushSample()
      if (widths.nonEmpty) out.println(tf.drawBottomBorder(widths))
    }
  }

  private class LTSVRowStreamWriter(out: PrintStream) extends RowStreamWriter {
    private val fmt = new LTSVFormatter()
    override def writeRow(row: Map[String, CellValue]): Unit =
      out.println(fmt.rowToLtsv(row))
  }

  private class MarkdownRowStreamWriter(out: PrintStream)
      extends RowStreamWriter {
    private val sample
        : scala.collection.mutable.ListBuffer[Map[String, CellValue]] =
      scala.collection.mutable.ListBuffer.empty
    private var columns: List[String] = Nil
    private var columnsSet: Set[String] = Set.empty
    private var flushed = false
    private var warnedUnseen = false

    private def cell(v: CellValue): String =
      v.display.replace("|", "\\|").replace("\n", " ")

    private def flushSample(): Unit = {
      columns = {
        val seen = scala.collection.mutable.LinkedHashSet.empty[String]
        sample.foreach(_.keysIterator.foreach(seen += _))
        seen.toList
      }
      columnsSet = columns.toSet
      out.println("| " + columns.mkString(" | ") + " |")
      out.println("| " + columns.map(_ => "---").mkString(" | ") + " |")
      sample.foreach(r =>
        out.println(
          "| " + columns
            .map(c => cell(r.getOrElse(c, CellValue.Null)))
            .mkString(" | ") + " |"
        )
      )
      flushed = true
    }

    override def writeRow(row: Map[String, CellValue]): Unit = {
      if (!flushed) {
        sample += row
        if (sample.size >= SampleSize) flushSample()
      } else {
        if (!warnedUnseen) {
          val unseen = row.keySet -- columnsSet
          if (unseen.nonEmpty) {
            Console.err.println(
              s"[parqueteer] warning: markdown writer dropping unseen column keys: ${unseen.mkString(", ")}"
            )
            warnedUnseen = true
          }
        }
        out.println(
          "| " + columns
            .map(c => cell(row.getOrElse(c, CellValue.Null)))
            .mkString(" | ") + " |"
        )
      }
    }

    override def end(): Unit = {
      if (!flushed) flushSample()
    }
  }
}
