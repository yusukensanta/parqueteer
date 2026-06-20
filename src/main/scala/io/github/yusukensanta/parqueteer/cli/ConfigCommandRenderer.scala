package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.config.{ConfigurationManager, EnvConfig}

private[cli] object ConfigCommandRenderer {

  def render(
      cmd: ConfigCommand,
      globalOptions: GlobalOptions
  ): Int = {
    val configManager = new ConfigurationManager()
    val configPath    = globalOptions.configPath
    if cmd.validate then renderValidation(configManager, configPath, globalOptions)
    else renderStatus(configManager, configPath, globalOptions)
  }

  private def renderValidation(
      configManager: ConfigurationManager,
      configPath: Option[String],
      globalOptions: GlobalOptions
  ): Int =
    configManager.validate(configPath) match {
      case scala.util.Success(Nil) =>
        if !globalOptions.quiet then println(s"✓ Configuration is valid")
        0
      case scala.util.Success(issues) =>
        issues.foreach(i => println(s"  $i"))
        0
      case scala.util.Failure(ex) =>
        System.err.println(s"Config error: ${ex.getMessage}")
        1
    }

  private def renderStatus(
      configManager: ConfigurationManager,
      configPath: Option[String],
      globalOptions: GlobalOptions
  ): Int = {
    if !globalOptions.quiet then {
      val resolvedPath = configManager.resolvedConfigPath(configPath)
      val fileExists   = better.files.File(resolvedPath).exists
      println(
        s"Config file: $resolvedPath [${if fileExists then "exists" else "not found"}]"
      )
      if fileExists then
        println(
          "  Note: config file is parsed for validation only; settings are not yet applied."
        )
      println()

      val envVars = EnvConfig.allSet
      if envVars.isEmpty then {
        println("Environment variables: (none set)")
      } else {
        println("Environment variables:")
        EnvConfig.SupportedVars.foreach { key =>
          envVars.get(key) match {
            case Some(v) => println(s"  $key=$v")
            case None    => ()
          }
        }
      }
      println()

      println("Resolved settings:")
      val fmt = EnvConfig.parsedDefaultFormat
        .map(_.toString.toLowerCase + " [env]")
        .getOrElse("table [default]")
      println(s"  format:   $fmt")
      val color = {
        val cm = globalOptions.colorMode
        val src =
          if sys.env.get("NO_COLOR").exists(_.nonEmpty) then "[env: NO_COLOR]"
          else if sys.env.contains("PARQUETEER_COLOR") then "[env]"
          else "[default]"
        s"${cm.toString.toLowerCase} $src"
      }
      println(s"  color:    $color")
      val verboseFlag =
        if globalOptions.verbose then "true [cli/env]" else "false [default]"
      println(s"  verbose:  $verboseFlag")
      val quiet =
        if globalOptions.quiet then "true [cli]" else "false [default]"
      println(s"  quiet:    $quiet")
      EnvConfig.parsedMaxRows.foreach { n =>
        println(s"  limit: $n [env: PARQUETEER_MAX_ROWS]")
      }
    }
    0
  }
}
