package io.github.yusukensanta.parqueteer.cli

private[cli] object CredentialRedactor {
  private val patterns: Seq[scala.util.matching.Regex] = Seq(
    "(?i)(Authorization\\s*[:=]\\s*)\\S[^&\\n\\r]*".r,
    "(?i)(X-Amz-[A-Za-z-]+\\s*[:=]\\s*)\\S[^&\\n\\r]*".r,
    "(?i)(AWSAccessKeyId=)[^&\\s]+".r,
    "(?i)(Signature=)[^&\\s]+".r,
    "(?i)(aws_secret_access_key\\s*=\\s*)\\S+".r,
    "(?i)((?:\\?|&)sig=)[^&\\s]+".r,
    "(-----BEGIN [A-Z ]+-----)[^-]+".r,
    "()\\bA(?:KIA|SIA)[A-Z0-9]{16}\\b".r
  )

  def redact(s: String): String = {
    if (s == null) return ""
    patterns.foldLeft(s) { (acc, pattern) =>
      pattern.replaceAllIn(
        acc,
        m => java.util.regex.Matcher.quoteReplacement(m.group(1) + "[REDACTED]")
      )
    }
  }

  private val MaxCauseChain = 20

  def redactThrowable(t: Throwable): String = {
    val sb = new StringBuilder
    val seen = new java.util.IdentityHashMap[Throwable, Boolean]()
    var current: Throwable = t
    var depth = 0
    while (
      current != null && !seen.containsKey(current) && depth < MaxCauseChain
    ) {
      seen.put(current, java.lang.Boolean.TRUE)
      val msg =
        Option(current.getMessage).getOrElse(current.getClass.getSimpleName)
      sb.append(redact(msg))
      current = current.getCause
      depth += 1
      if (
        current != null && !seen.containsKey(current) && depth < MaxCauseChain
      )
        sb.append("\nCaused by: ")
    }
    if (current != null && depth >= MaxCauseChain)
      sb.append("\n[cause chain truncated]")
    sb.toString
  }
}
