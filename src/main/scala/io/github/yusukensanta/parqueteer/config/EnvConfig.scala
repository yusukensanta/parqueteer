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
      s.toLowerCase match {
        case "table"    => Some(OutputFormat.Table)
        case "json"     => Some(OutputFormat.JSON)
        case "csv"      => Some(OutputFormat.CSV)
        case "pretty"   => Some(OutputFormat.Pretty)
        case "markdown" => Some(OutputFormat.Markdown)
        case "ndjson"   => Some(OutputFormat.NDJSON)
        case _          => None
      }
    }

  def parsedColorMode: ColorMode =
    if (sys.env.get("NO_COLOR").exists(_.nonEmpty)) ColorMode.Never
    else
      sys.env
        .get("PARQUETEER_COLOR")
        .map(ColorMode.fromString)
        .getOrElse(ColorMode.Auto)

  def verbose: Boolean =
    sys.env.get("PARQUETEER_VERBOSE").exists(_.toLowerCase == "true")

  def parsedMaxRows: Option[Long] =
    sys.env.get("PARQUETEER_MAX_ROWS").flatMap(_.toLongOption)

  def allSet: Map[String, String] =
    SupportedVars.flatMap(k => sys.env.get(k).map(k -> _)).toMap

  def buildInitialGlobalOptions: GlobalOptions =
    GlobalOptions(
      verbose = verbose,
      colorMode = parsedColorMode
    )
}
