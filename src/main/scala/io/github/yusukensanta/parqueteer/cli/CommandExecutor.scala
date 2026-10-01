package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.services.ParquetService
import io.github.yusukensanta.parqueteer.core.models.*
import CommandSupport.*
import ReadCommands.*
import InspectCommands.*
import WriteCommands.*

/** Entry point: routes a parsed Command to its handler, expanding globs first. */
private[cli] object CommandExecutor {

  def execute(
      command: Command,
      service: ParquetService,
      globalOptions: GlobalOptions
  ): Int =
    command match {
      case cmd: ReadCommand =>
        withResolvedPaths(service, cmd.filePath, "Error", globalOptions)(
          path => {
            warnIfSchemaModeNoop(cmd.schemaMode, globalOptions)
            executeRead(service, cmd.copy(filePath = path), globalOptions)
          },
          paths => executeReadMulti(service, paths, cmd, globalOptions)
        )

      case cmd: InfoCommand =>
        withResolvedPaths(service, cmd.filePath, "Failed to get file info", globalOptions)(
          path => executeInfo(service, cmd.copy(filePath = path), globalOptions),
          paths => executeInfoMulti(service, paths, cmd, globalOptions)
        )

      case cmd: WriteCommand =>
        withResolvedPaths(service, cmd.inputPath, "Failed to write file", globalOptions)(
          path => {
            warnIfSchemaModeNoop(cmd.schemaMode, globalOptions)
            executeWrite(service, cmd.copy(inputPath = path), globalOptions)
          },
          paths => executeWriteMulti(service, paths, cmd, globalOptions)
        )

      case cmd: ValidateCommand =>
        withResolvedPaths(service, cmd.filePath, "Failed to validate file", globalOptions)(
          path => executeValidate(service, cmd.copy(filePath = path), globalOptions),
          paths => executeValidateMulti(service, paths, cmd, globalOptions)
        )

      case cmd: ConvertCommand =>
        withResolvedPaths(service, cmd.inputPath, "Failed to convert file", globalOptions)(
          path => {
            warnIfSchemaModeNoop(cmd.schemaMode, globalOptions)
            executeConvert(service, cmd.copy(inputPath = path), globalOptions)
          },
          paths => executeConvertMulti(service, paths, cmd, globalOptions)
        )

      case cmd: ConfigCommand =>
        executeConfig(cmd, globalOptions)

      case cmd: SchemaCommand =>
        withResolvedPaths(service, cmd.filePath, "Failed to read schema", globalOptions)(
          path => executeSchemaInfo(service, cmd.copy(filePath = path), globalOptions),
          paths => executeSchemaInfoMulti(service, paths, cmd.format, globalOptions)
        )

      case cmd: SchemaDiffCommand =>
        executeSchemaDiff(service, cmd, globalOptions)

      case cmd: StatsCommand =>
        withResolvedPaths(service, cmd.filePath, "Failed to get stats", globalOptions)(
          path => executeStats(service, cmd.copy(filePath = path), globalOptions),
          paths => executeStatsMulti(service, paths, cmd, globalOptions)
        )

      case cmd: CountCommand =>
        withResolvedPaths(service, cmd.filePath, "Failed to count rows", globalOptions)(
          path => executeCount(service, cmd.copy(filePath = path), globalOptions),
          paths => executeCountMulti(service, paths, cmd, globalOptions)
        )

      case cmd: MergeCommand =>
        resolveAllGlobs(service, cmd.inputPaths) match {
          case Left(error) => reportError("Failed to merge", globalOptions)(error)
          case Right(expandedPaths) =>
            executeMerge(service, cmd.copy(inputPaths = expandedPaths), globalOptions)
        }

      case CompletionsCommand(shell) =>
        executeCompletions(shell, globalOptions)
    }

  /**
   * Expands a possibly-glob `path` and routes to the single-file handler for
   * exactly one match, or the multi-file handler otherwise. Resolution errors
   * (bad location, no glob match) are reported with `errorPrefix`.
   */
  private def withResolvedPaths(
      service: ParquetService,
      path: String,
      errorPrefix: String,
      globalOptions: GlobalOptions
  )(single: String => Int, multi: List[String] => Int): Int =
    service.resolveGlob(path) match {
      case Left(error)      => reportError(errorPrefix, globalOptions)(error)
      case Right(List(one)) => single(one)
      case Right(paths)     => multi(paths)
    }

  private[cli] def resolveAllGlobs(
      service: ParquetService,
      paths: List[String]
  ): Either[ParqueteerError, List[String]] =
    paths.foldLeft[Either[ParqueteerError, List[String]]](Right(Nil)) { (acc, p) =>
      acc.flatMap(resolved => service.resolveGlob(p).map(resolved ++ _))
    }

  private[cli] def executeCompletions(
      shell: String,
      globalOptions: GlobalOptions
  ): Int =
    shell.toLowerCase match {
      case "bash" =>
        if !globalOptions.quiet then println(ShellCompletions.bash)
        0
      case "zsh" =>
        if !globalOptions.quiet then println(ShellCompletions.zsh)
        0
      case "fish" =>
        if !globalOptions.quiet then println(ShellCompletions.fish)
        0
      case other =>
        System.err.println(s"[parqueteer] error: Unsupported shell: $other. Use bash, zsh, or fish")
        1
    }

  private[cli] def executeConfig(
      cmd: ConfigCommand,
      globalOptions: GlobalOptions
  ): Int = ConfigCommandRenderer.render(cmd, globalOptions)
}
