package io.github.yusukensanta.parqueteer.cli

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scopt.OParser
import CliSpec.Values

class CliSpecTest extends AnyFlatSpec with Matchers {

  // Pins what CliSpec reads out of scopt (including its private[scopt]
  // metadata via scopt.ParserIntrospection), so a scopt upgrade that changes
  // the structure fails here rather than silently emptying help/completions.
  "CliSpec.commands" should "list every command and subcommand in declaration order" in {
    CliSpec.commands.map(_.path) shouldBe List(
      "read",
      "info",
      "write",
      "validate",
      "convert",
      "schema",
      "schema diff",
      "merge",
      "stats",
      "count",
      "completions",
      "config"
    )
  }

  it should "attach each option to its own command, not to a parent or sibling" in {
    CliSpec.command("schema").get.options.map(_.long) shouldBe List("format")
    CliSpec.command("schema diff").get.options.map(_.long) shouldBe List("format")
    CliSpec.command("merge").get.options.map(_.long) shouldBe
      List("output", "compression", "schema-mode", "page-size", "no-dictionary", "dry-run")
    CliSpec.subcommands("schema").map(_.path) shouldBe List("schema diff")
  }

  it should "carry short flags, value names and repeatability" in {
    val read = CliSpec.command("read").get.options
    read.find(_.long == "limit").get shouldBe
      CliSpec.OptSpec("limit", Some("n"), Some("<n>"), "Maximum number of rows to display")
    read.find(_.long == "stream").get.takesValue shouldBe false
    CliSpec.command("validate").get.options.find(_.long == "assert").get.repeatable shouldBe true
  }

  "CliSpec.globalOptions" should "be the options declared outside any command" in {
    CliSpec.globalOptions.map(_.long) shouldBe List(
      "help",
      "version",
      "verbose",
      "quiet",
      "config",
      "profile",
      "region",
      "file-parallelism",
      "color"
    )
  }

  "Every option that takes a value" should "declare a value name for help" in {
    val all = CliSpec.globalOptions ++ CliSpec.commands.flatMap(_.options)
    all.filter(_.valueName.contains("<value>")).map(_.long) shouldBe Nil
  }

  // Arguments that make each command line otherwise valid.
  private val minimalArgs: Map[String, List[String]] = Map(
    "read"        -> List("read", "a.parquet"),
    "info"        -> List("info", "a.parquet"),
    "write"       -> List("write", "in.json", "out.parquet"),
    "validate"    -> List("validate", "a.parquet"),
    "convert"     -> List("convert", "in.csv", "out.parquet"),
    "schema"      -> List("schema", "a.parquet"),
    "schema diff" -> List("schema", "diff", "a.parquet", "b.parquet"),
    "merge"       -> List("merge", "a.parquet", "b.parquet", "-o", "m.parquet"),
    "stats"       -> List("stats", "a.parquet"),
    "count"       -> List("count", "a.parquet"),
    "completions" -> List("completions", "bash"),
    "config"      -> List("config")
  )

  private def parses(args: List[String]): Boolean =
    OParser.parse(ArgumentParser.parser, args, ArgumentParser.Config()).isDefined

  "Completion choices" should "be exactly values the parser accepts" in {
    minimalArgs.keySet shouldBe CliSpec.commands.map(_.path).toSet
    val silenced = new java.io.PrintStream(new java.io.ByteArrayOutputStream())
    Console.withErr(silenced) {
      for {
        cmd <- CliSpec.commands
        opt <- cmd.options
        case Values.Choice(values) <- List(CliSpec.optionValues(cmd.path, opt))
      } {
        values.foreach { v =>
          withClue(s"${cmd.path} --${opt.long} $v: ")(
            parses(minimalArgs(cmd.path) ++ List(s"--${opt.long}", v)) shouldBe true
          )
        }
        withClue(s"${cmd.path} --${opt.long} bogus: ")(
          parses(minimalArgs(cmd.path) ++ List(s"--${opt.long}", "bogus")) shouldBe false
        )
      }
      CliSpec.globalOptions.foreach { opt =>
        CliSpec.optionValues("", opt) match {
          case Values.Choice(values) =>
            values.foreach(v =>
              withClue(s"--${opt.long} $v: ")(
                parses(List("config", s"--${opt.long}", v)) shouldBe true
              )
            )
          case _ => ()
        }
      }
      CliSpec.Choices.shells.foreach(sh => parses(List("completions", sh)) shouldBe true)
    }
  }

  "CliSpec.summarize" should "keep a description's first clause" in {
    CliSpec.summarize("Output format: table, json (default: table)") shouldBe "Output format"
    CliSpec.summarize("Fully decompress all row groups (default: spot-check)") shouldBe
      "Fully decompress all row groups"
    CliSpec.summarize("Suppress non-error output") shouldBe "Suppress non-error output"
  }
}
