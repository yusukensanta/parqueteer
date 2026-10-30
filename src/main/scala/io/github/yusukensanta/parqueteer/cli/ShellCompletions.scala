package io.github.yusukensanta.parqueteer.cli

import CliSpec.{CmdSpec, OptSpec, Values}

/**
 * bash, zsh and fish completion scripts, rendered from CliSpec (i.e. from the
 * parser), so every command and option the parser accepts is completed —
 * including the allowed values of enum-valued options — without a second,
 * hand-maintained list per shell.
 */
object ShellCompletions {

  def scriptFor(shell: Shell): String = shell match {
    case Shell.Bash => bash
    case Shell.Zsh  => zsh
    case Shell.Fish => fish
  }

  private def flagNames(o: OptSpec): List[String] =
    s"--${o.long}" :: o.short.map(c => s"-$c").toList

  // ── bash ────────────────────────────────────────────────────────────────

  lazy val bash: String = {
    // The COMPREPLY line offering `values`, or None when there is nothing to offer.
    def compgen(values: Values): Option[String] = values match {
      case Values.Choice(vs) =>
        Some(s"""COMPREPLY+=($$(compgen -W "${vs.mkString(" ")}" -- "$$cur"))""")
      case Values.Files(Some(glob)) =>
        Some(s"""COMPREPLY+=($$(compgen -f -X '!$glob' -- "$$cur"))""")
      case Values.Files(None) => Some("""COMPREPLY+=($(compgen -f -- "$cur"))""")
      case Values.Free        => None
    }

    // Completion for one command: the value of the option just typed, else
    // its flags (plus `extraWords`, e.g. subcommand names) and positionals.
    def block(path: String, options: List[OptSpec], extra: List[String], pad: String) = {
      val valueCases = options.filter(_.takesValue).map { o =>
        val offer = compgen(CliSpec.optionValues(path, o)).fold("")(c => s"$c ; ")
        s"$pad  ${flagNames(o).mkString("|")}) ${offer}return ;;"
      }
      val prevCase =
        if valueCases.isEmpty then Nil
        else (s"""${pad}case "$$prev" in""" :: valueCases) :+ s"${pad}esac"
      val words = (extra ++ options.map(o => s"--${o.long}")).mkString(" ")
      val wordsLine =
        Option.when(words.nonEmpty)(s"""${pad}COMPREPLY=($$(compgen -W "$words" -- "$$cur"))""")
      val positional = if path.isEmpty then None else compgen(CliSpec.argumentValues(path))
      (prevCase ++ wordsLine ++ positional.map(pad + _) :+ s"${pad}return ;;").mkString("\n")
    }

    val commandCases = CliSpec.topLevelCommands.map { c =>
      val subs = CliSpec.subcommands(c.path)
      if subs.isEmpty then s"    ${c.name})\n${block(c.path, c.options, Nil, "      ")}"
      else {
        val subCases =
          subs.map(s => s"        ${s.name})\n${block(s.path, s.options, Nil, "          ")}")
        val own = s"        *)\n${block(c.path, c.options, subs.map(_.name), "          ")}"
        s"""    ${c.name})
           |      case "$${words[2]}" in
           |${(subCases :+ own).mkString("\n")}
           |      esac ;;""".stripMargin
      }
    }
    // Before a command: global options and the command names.
    val global =
      block("", CliSpec.globalOptions, CliSpec.topLevelCommands.map(_.name), "      ")

    s"""# bash completion for parqueteer
       |# Install: parqueteer completions bash > /etc/bash_completion.d/parqueteer
       |#      or: eval "$$(parqueteer completions bash)"
       |_parqueteer() {
       |  local cur prev words cword
       |  _init_completion || return
       |
       |  case "$${words[1]}" in
       |${commandCases.mkString("\n")}
       |    *)
       |$global
       |  esac
       |}
       |complete -F _parqueteer parqueteer""".stripMargin
  }

  // ── zsh ─────────────────────────────────────────────────────────────────

  lazy val zsh: String = {
    def describe(text: String): String =
      text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("'", "'\\''")

    def action(values: Values): String = values match {
      case Values.Choice(vs)        => s"(${vs.mkString(" ")})"
      case Values.Files(Some(glob)) => s"""_files -g "$glob""""
      case Values.Files(None)       => "_files"
      case Values.Free              => ""
    }

    def optSpec(path: String, o: OptSpec): String = {
      val star = if o.repeatable then "*" else ""
      val value = o.valueName.fold("") { v =>
        s":${v.stripPrefix("<").stripSuffix(">")}:${action(CliSpec.optionValues(path, o))}"
      }
      val body = s"[${describe(o.summary)}]$value'"
      o.short match {
        case Some(c) => s"'$star(-$c --${o.long})'{-$c,--${o.long}}'$body"
        case None    => s"'$star--${o.long}$body"
      }
    }

    def positional(path: String): List[String] = CliSpec.argumentValues(path) match {
      case Values.Free => Nil
      case v           => List(s"'*:argument:${action(v)}'")
    }

    def arguments(specs: List[String], pad: String): String =
      if specs.isEmpty then s"${pad}:"
      else (s"${pad}_arguments" :: specs.map(spec => s"$pad  $spec")).mkString(" \\\n")

    val commandCases = CliSpec.topLevelCommands.map { c =>
      val own  = c.options.map(optSpec(c.path, _))
      val subs = CliSpec.subcommands(c.path)
      if subs.isEmpty then
        s"        ${c.name})\n${arguments(own ++ positional(c.path), "          ")} ;;"
      else {
        val subCases = subs.map(s =>
          s"            ${s.name})\n${arguments(s.options.map(optSpec(s.path, _)) ++ positional(s.path), "              ")} ;;"
        )
        val subChoice = s"'1:subcommand or file:(${subs.map(_.name).mkString(" ")})'"
        val ownCase =
          s"            *)\n${arguments((own :+ subChoice) ++ positional(c.path), "              ")} ;;"
        s"""        ${c.name})
           |          case $${words[3]} in
           |${(subCases :+ ownCase).mkString("\n")}
           |          esac ;;""".stripMargin
      }
    }

    val commandList = CliSpec.topLevelCommands
      .map(c => s"    '${c.name}:${CliSpec.summarize(c.text).replace("'", "'\\''")}'")
      .mkString("\n")
    val globalSpecs = CliSpec.globalOptions.map(o => s"    ${optSpec("", o)} \\").mkString("\n")

    s"""#compdef parqueteer
       |# zsh completion for parqueteer
       |# Install: parqueteer completions zsh > "$${fpath[1]}/_parqueteer"
       |#      or: parqueteer completions zsh > ~/.zfunc/_parqueteer  (add ~/.zfunc to fpath)
       |_parqueteer() {
       |  local state
       |  local -a commands
       |
       |  commands=(
       |$commandList
       |  )
       |
       |  _arguments -C \\
       |$globalSpecs
       |    '1: :->command' \\
       |    '*: :->args' && return 0
       |
       |  case $$state in
       |    command)
       |      _describe 'command' commands ;;
       |    args)
       |      case $${words[2]} in
       |${commandCases.mkString("\n")}
       |      esac ;;
       |  esac
       |}
       |_parqueteer "$$@"""".stripMargin
  }

  // ── fish ────────────────────────────────────────────────────────────────

  lazy val fish: String = {
    def quote(text: String): String = s"'${text.replace("\\", "\\\\").replace("'", "\\'")}'"

    def optLine(condition: Option[String], path: String, o: OptSpec): String = {
      val cond  = condition.fold("")(c => s" -n ${quote(c)}")
      val short = o.short.fold("")(c => s" -s $c")
      val value =
        if !o.takesValue then " -f"
        else
          CliSpec.optionValues(path, o) match {
            case Values.Choice(vs) => s" -x -a ${quote(vs.mkString(" "))}"
            case Values.Files(_)   => " -r -F"
            case Values.Free       => " -x"
          }
      s"complete -c parqueteer$cond -l ${o.long}$short$value -d ${quote(o.summary)}"
    }

    def commandLines(c: CmdSpec): List[String] = {
      val parent = c.path.split(' ').init.lastOption
      val condition =
        parent.fold(s"__fish_seen_subcommand_from ${c.name}")(_ =>
          s"__fish_seen_subcommand_from ${c.name}"
        )
      val subcommandOffers = CliSpec.subcommands(c.path).map { s =>
        val cond =
          s"__fish_seen_subcommand_from ${c.name}; and not __fish_seen_subcommand_from ${s.name}"
        s"complete -c parqueteer -n ${quote(cond)} -f -a ${s.name} -d ${quote(CliSpec.summarize(s.text))}"
      }
      val positional = CliSpec.argumentValues(c.path) match {
        case Values.Choice(vs) =>
          List(s"complete -c parqueteer -n ${quote(condition)} -f -a ${quote(vs.mkString(" "))}")
        case _ => Nil
      }
      s"# ${c.path}" :: (c.options.map(
        optLine(Some(condition), c.path, _)
      ) ++ subcommandOffers ++ positional)
    }

    val commands = CliSpec.topLevelCommands.map(c =>
      s"complete -c parqueteer -n '__fish_use_subcommand' -f -a ${c.name} -d ${quote(CliSpec.summarize(c.text))}"
    )
    val sections = CliSpec.commands.map(commandLines(_).mkString("\n"))

    s"""# fish completion for parqueteer
       |# Install: parqueteer completions fish > ~/.config/fish/completions/parqueteer.fish
       |
       |# Commands
       |${commands.mkString("\n")}
       |
       |# Global options
       |${CliSpec.globalOptions.map(optLine(None, "", _)).mkString("\n")}
       |
       |${sections.mkString("\n\n")}""".stripMargin
  }
}
