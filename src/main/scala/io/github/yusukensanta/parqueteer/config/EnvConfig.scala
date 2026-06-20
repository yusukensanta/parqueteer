package io.github.yusukensanta.parqueteer.config

import io.github.yusukensanta.parqueteer.cli.{ColorMode, GlobalOptions}
import io.github.yusukensanta.parqueteer.core.models.OutputFormat

object EnvConfig {

  val SupportedVars: List[String] = List(
    "PARQUETEER_CONFIG",
    "PARQUETEER_DEFAULT_FORMAT",
    "PARQUETEER_COLOR",
    "PARQUETEER_VERBOSE",
    "PARQUETEER_MAX_ROWS",
    "NO_COLOR"
  )

  def configPath: Option[String] = sys.env.get("PARQUETEER_CONFIG")

  def parsedDefaultFormat: Option[OutputFormat] =
    sys.env.get("PARQUETEER_DEFAULT_FORMAT").flatMap { s =>
      val parsed = s.toLowerCase match {
        case "table"    => Some(OutputFormat.Table)
        case "json"     => Some(OutputFormat.JSON)
        case "csv"      => Some(OutputFormat.CSV)
        case "pretty"   => Some(OutputFormat.Pretty)
        case "markdown" => Some(OutputFormat.Markdown)
        case "ndjson"   => Some(OutputFormat.NDJSON)
        case "ltsv"     => Some(OutputFormat.LTSV)
        case _          => None
      }
      if parsed.isEmpty then
        System.err.println(
          s"[parqueteer] warning: PARQUETEER_DEFAULT_FORMAT=$s is not a recognized format; ignoring"
        )
      parsed
    }

  def parsedColorMode: ColorMode =
    if sys.env.get("NO_COLOR").exists(_.nonEmpty) then ColorMode.Never
    else
      sys.env.get("PARQUETEER_COLOR") match {
        case None => ColorMode.Auto
        case Some(s) =>
          ColorMode.fromString(s) match {
            case Some(mode) => mode
            case None =>
              System.err.println(
                s"[parqueteer] warning: PARQUETEER_COLOR=$s is not recognized (auto/always/never); ignoring"
              )
              ColorMode.Auto
          }
      }

  def verbose: Boolean =
    sys.env.get("PARQUETEER_VERBOSE").exists { v =>
      Set("true", "1", "yes", "on").contains(v.toLowerCase)
    }

  def parsedMaxRows: Option[Long] =
    sys.env.get("PARQUETEER_MAX_ROWS").flatMap { raw =>
      val parsed = raw.toLongOption.filter(_ > 0)
      if parsed.isEmpty then
        System.err.println(
          s"[parqueteer] warning: PARQUETEER_MAX_ROWS=$raw is not a positive integer; ignoring"
        )
      parsed
    }

  def allSet: Map[String, String] =
    SupportedVars.flatMap(k => sys.env.get(k).map(k -> _)).toMap

  def buildInitialGlobalOptions: GlobalOptions =
    GlobalOptions(
      verbose = verbose,
      colorMode = parsedColorMode
    )
}
