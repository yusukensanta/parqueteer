package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.services.ParquetService
import io.github.yusukensanta.parqueteer.core.repositories.HadoopParquetRepository
import io.github.yusukensanta.parqueteer.config.{AppConfig, ConfigurationManager, EnvConfig}
import scopt.OParser
import org.slf4j.LoggerFactory

object CliApp {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Route JUL (used by GCS Hadoop connector) through SLF4J so
    // simplelogger.properties filters apply (com.google.cloud=error).
    org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger()
    org.slf4j.bridge.SLF4JBridgeHandler.install()

    // Force UTF-8 for stdout/stderr regardless of platform locale.
    // The launcher scripts also pass -Dfile.encoding=UTF-8 / -Dstdout.encoding=UTF-8
    // so this backstop only matters when the JAR is invoked directly via `java -jar`.
    val utf8    = java.nio.charset.StandardCharsets.UTF_8
    val utf8Out = new java.io.PrintStream(System.out, true, utf8)
    val utf8Err = new java.io.PrintStream(System.err, true, utf8)
    System.setOut(utf8Out)
    System.setErr(utf8Err)
    Console.withOut(utf8Out) {
      Console.withErr(utf8Err) {
        mainImpl(args)
      }
    }
  }

  private def mainImpl(args: Array[String]): Unit = {
    if shouldShowVersion(args) then {
      showVersion()
      System.exit(0)
    }

    // Show custom top-level help only when no subcommand is present
    if shouldShowTopLevelHelp(args) then {
      println(HelpFormatter.topLevelHelp())
      System.exit(0)
    }

    // Show subcommand-specific help when subcommand + --help is present
    detectSubcommandHelp(args).foreach { cmd =>
      HelpFormatter.subcommandHelp(cmd).foreach(println)
      System.exit(0)
    }

    val initialConfig = ArgumentParser.Config(
      globalOptions = EnvConfig.buildInitialGlobalOptions
    )
    OParser.parse(ArgumentParser.parser, args, initialConfig) match {
      case Some(config) =>
        val exitCode = run(config)
        System.exit(exitCode)
      case None =>
        val exitCode =
          if args.contains("--help") || args.contains("-h") then 0 else 2
        System.exit(exitCode)
    }
  }

  private[cli] def shouldShowVersion(args: Array[String]): Boolean =
    args.contains("--version") || args.contains("-V")

  private def showVersion(): Unit = {
    import io.github.yusukensanta.parqueteer.BuildInfo
    val javaVersion  = System.getProperty("java.version")
    val javaVendor   = System.getProperty("java.vendor", "")
    val vendorSuffix = if javaVendor.nonEmpty then s" ($javaVendor)" else ""
    println(s"parqueteer ${BuildInfo.version}")
    println(s"Scala ${BuildInfo.scalaVersion} · Java $javaVersion$vendorSuffix")
  }

  private[cli] val knownCommands = Set(
    "read",
    "info",
    "write",
    "validate",
    "convert",
    "merge",
    "schema",
    "schema diff",
    "stats",
    "count",
    "config",
    "completions"
  )

  private def commandMatches(cmd: String, args: Array[String]): Boolean =
    cmd.split(' ').forall(args.contains)

  private[cli] def shouldShowTopLevelHelp(args: Array[String]): Boolean = {
    val hasHelp    = args.contains("--help") || args.contains("-h")
    val hasCommand = knownCommands.exists(commandMatches(_, args))
    hasHelp && !hasCommand
  }

  private[cli] def detectSubcommandHelp(args: Array[String]): Option[String] = {
    val hasHelp = args.contains("--help") || args.contains("-h")
    if !hasHelp then None
    else
      knownCommands
        .filter(commandMatches(_, args))
        .maxByOption(_.length)
  }

  private def run(config: ArgumentParser.Config): Int =
    config.command match {
      case None =>
        println(HelpFormatter.topLevelHelp())
        1
      case Some(cmd) =>
        try {
          val appConfig = new ConfigurationManager()
            .loadConfig(config.globalOptions.configPath) match {
            case scala.util.Success(c) => c
            case scala.util.Failure(e) =>
              if !config.globalOptions.quiet then
                System.err.println(
                  s"[parqueteer] warning: could not load config: ${e.getMessage}; using defaults"
                )
              AppConfig()
          }
          val opts         = applyAppConfig(config.globalOptions, appConfig)
          val effectiveCmd = applyAppConfigToCommand(cmd, appConfig)
          val repository = new HadoopParquetRepository(
            profile = opts.profile,
            region = opts.region
          )
          val service = new ParquetService(repository)
          CommandExecutor.execute(effectiveCmd, service, opts)
        } catch {
          case e: Exception =>
            logger.error(
              s"Unexpected error: ${CredentialRedactor
                  .redact(Option(e.getMessage).getOrElse(e.getClass.getName))}"
            )
            System.err.println(
              s"Error: ${CredentialRedactor.redact(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))}"
            )
            if config.globalOptions.verbose then
              System.err.println(CredentialRedactor.redactThrowable(e))
            1
        }
    }

  private def applyAppConfig(
      opts: GlobalOptions,
      appConfig: AppConfig
  ): GlobalOptions = {
    val s3 = appConfig.cloud.s3
    opts.copy(
      profile = opts.profile.orElse(s3.profile),
      region = opts.region.orElse(s3.defaultRegion)
    )
  }

  private def applyAppConfigToCommand(
      cmd: Command,
      appConfig: AppConfig
  ): Command = {
    val outCfg = appConfig.output
    cmd match {
      case r: ReadCommand =>
        r.copy(maxRows = r.maxRows.orElse(outCfg.maxRows))
      case c: ConvertCommand =>
        c.copy(maxRows = c.maxRows.orElse(outCfg.maxRows))
      case other => other
    }
  }

}
