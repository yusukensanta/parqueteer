package scopt

/**
 * Read-only access to the parts of scopt's option metadata that scopt keeps
 * `private[scopt]` (which command an option belongs to, whether it is an
 * option/command/argument, whether it takes a value). Parqueteer renders its
 * help and shell completions from the parser definitions (cli.CliSpec), and
 * this structure isn't recoverable from the public API: `children` reorders
 * nested subcommands' options ahead of their parent's.
 *
 * Lives in package scopt only to reach those members; CliSpecTest pins the
 * structure it yields, so a scopt upgrade that changes it fails the build.
 */
object ParserIntrospection {

  enum Kind:
    case Option, Command, Argument, Other

  def kind(d: OptionDef[?, ?]): Kind = d.kind match {
    case OptionDefKind.Opt | OptionDefKind.OptHelp | OptionDefKind.OptVersion => Kind.Option
    case OptionDefKind.Cmd                                                    => Kind.Command
    case OptionDefKind.Arg                                                    => Kind.Argument
    case _                                                                    => Kind.Other
  }

  def id(d: OptionDef[?, ?]): Int = d.id

  def parentId(d: OptionDef[?, ?]): scala.Option[Int] = d.getParentId

  def takesValue(d: OptionDef[?, ?]): Boolean = d.read.arity > 0
}
