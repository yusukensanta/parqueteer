package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.{
  ColorMode,
  InputFormat,
  OutputFormat,
  SchemaMode
}

import scopt.ParserIntrospection as Introspect
import scopt.ParserIntrospection.Kind

/**
 * The CLI's commands and options as data, read from the scopt definitions in
 * [[ArgumentParser.parser]] — the one place an option is declared. Help
 * (HelpFormatter) and shell completions (ShellCompletions) are rendered from
 * this, so adding or renaming a flag in the parser updates both; nothing about
 * an option's name, short form, value or description is written twice.
 */
private[cli] object CliSpec {

  /** A `--long` option; `valueName` is set iff the option takes a value. */
  final case class OptSpec(
      long: String,
      short: Option[String],
      valueName: Option[String],
      text: String,
      repeatable: Boolean = false
  ) {
    def takesValue: Boolean = valueName.isDefined

    /** Help/completion summary: the description up to its first clause break. */
    def summary: String = CliSpec.summarize(text)
  }

  /** A (sub)command; `path` is e.g. "write" or "schema diff". */
  final case class CmdSpec(path: String, text: String, options: List[OptSpec]) {
    def name: String = path.split(' ').last
  }

  /** What a shell should offer after an option, or for a command's positional arguments. */
  enum Values:
    case Free
    case Choice(values: List[String])
    case Files(glob: Option[String])

  // Allowed values of each enum-valued option, shared with ArgumentParser so
  // validation, help and completions can't disagree.
  object Choices {
    val formats: List[String] = OutputFormat.values.toList.map(_.toString.toLowerCase)

    val tableOrJson: List[String] =
      List(OutputFormat.Table, OutputFormat.JSON).map(_.toString.toLowerCase)
    val compressions: List[String] = List("none", "snappy", "gzip", "lzo", "brotli", "lz4", "zstd")
    val inputFormats: List[String] = InputFormat.values.toList.map(_.name)
    val schemaModes: List[String]  = SchemaMode.values.toList.map(_.toString.toLowerCase)
    val colors: List[String]       = ColorMode.values.toList.map(_.toString.toLowerCase)
    val shells: List[String]       = Shell.values.toList.map(_.toString.toLowerCase)
  }

  private lazy val defs = ArgumentParser.parser.toList.filterNot(_.isHidden)

  private def isOption(d: scopt.OptionDef[?, ?]): Boolean  = Introspect.kind(d) == Kind.Option
  private def isCommand(d: scopt.OptionDef[?, ?]): Boolean = Introspect.kind(d) == Kind.Command

  private def optSpec(d: scopt.OptionDef[?, ?]): OptSpec =
    OptSpec(
      d.name,
      d.shortOpt,
      d.valueName.orElse(Option.when(Introspect.takesValue(d))("<value>")),
      d.desc,
      repeatable = d.getMaxOccurs > 1
    )

  /** Options that apply to every command (`--verbose`, `--config`, ...), help/version included. */
  lazy val globalOptions: List[OptSpec] =
    defs.filter(d => isOption(d) && Introspect.parentId(d).isEmpty).map(optSpec)

  /** Every command and subcommand, in declaration order. */
  lazy val commands: List[CmdSpec] = {
    val cmdDefs = defs.filter(isCommand)
    val byId    = cmdDefs.map(d => Introspect.id(d) -> d).toMap
    def path(d: scopt.OptionDef[?, ?]): String =
      Introspect.parentId(d).flatMap(byId.get).fold(d.name)(p => s"${path(p)} ${d.name}")
    cmdDefs.map { c =>
      val options =
        defs.filter(d => isOption(d) && Introspect.parentId(d).contains(Introspect.id(c)))
      CmdSpec(path(c), c.desc, options.map(optSpec))
    }
  }

  def command(path: String): Option[CmdSpec] = commands.find(_.path == path)

  /** Direct subcommands of `path` (e.g. "schema" → "schema diff"). */
  def subcommands(path: String): List[CmdSpec] =
    commands.filter(c =>
      c.path.startsWith(s"$path ") && !c.path.drop(path.length + 1).contains(' ')
    )

  def topLevelCommands: List[CmdSpec] = commands.filterNot(_.path.contains(' '))

  /** What to complete after `--<opt.long>` within `cmdPath`. */
  def optionValues(cmdPath: String, opt: OptSpec): Values =
    opt.long match {
      case "format" =>
        Values.Choice(if cmdPath == "read" then Choices.formats else Choices.tableOrJson)
      case "compression"              => Values.Choice(Choices.compressions)
      case "input-format"             => Values.Choice(Choices.inputFormats)
      case "schema-mode"              => Values.Choice(Choices.schemaModes)
      case "color"                    => Values.Choice(Choices.colors)
      case "schema" | "expect-schema" => Values.Files(Some("*.json"))
      case "output"                   => Values.Files(Some("*.parquet"))
      case "config"                   => Values.Files(None)
      case _                          => Values.Free
    }

  /** What to complete for a command's positional arguments. */
  def argumentValues(cmdPath: String): Values =
    cmdPath match {
      case "write" | "convert" => Values.Files(None)
      case "completions"       => Values.Choice(Choices.shells)
      case "config"            => Values.Free
      case _                   => Values.Files(Some("*.parquet"))
    }

  // First clause of a description, for one-line completion hints:
  // "Output format: table, json (default: table)" → "Output format".
  private[cli] def summarize(text: String): String = {
    val breaks = List(": ", " (", "; ", ". ", " — ")
    val cut    = breaks.flatMap(b => Option(text.indexOf(b)).filter(_ > 0)).minOption
    cut.fold(text)(text.take(_)).stripSuffix(".")
  }
}
