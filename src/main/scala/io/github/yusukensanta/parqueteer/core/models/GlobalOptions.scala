package io.github.yusukensanta.parqueteer.core.models

enum ColorMode:
  case Auto, Always, Never

object ColorMode:

  def fromString(s: String): Option[ColorMode] = s.toLowerCase match
    case "always" => Some(Always)
    case "never"  => Some(Never)
    case "auto"   => Some(Auto)
    case _        => None

case class GlobalOptions(
    verbose: Boolean = false,
    quiet: Boolean = false,
    configPath: Option[String] = None,
    profile: Option[String] = None,
    region: Option[String] = None,
    colorMode: ColorMode = ColorMode.Auto
)
