package io.github.yusukensanta.parqueteer.cli

private[cli] object CredentialRedactor {
  private val patterns: Seq[scala.util.matching.Regex] = Seq(
    "(?i)(Authorization\\s*[:=]\\s*)\\S[^&\\n\\r]*".r,
    "(?i)(X-Amz-[A-Za-z-]+\\s*[:=]\\s*)\\S[^&\\n\\r]*".r,
    "(?i)(AWSAccessKeyId=)[^&\\s]+".r,
    "(?i)(Signature=)[^&\\s]+".r,
    "(?i)(aws_secret_access_key\\s*=\\s*)\\S+".r,
    "(?i)((?:\\?|&)sig=)[^&\\s]+".r,
    "(-----BEGIN [A-Z ]+-----)[^-]+".r
  )

  def redact(s: String): String =
    patterns.foldLeft(s) { (acc, pattern) =>
      pattern.replaceAllIn(acc, m => m.group(1) + "[REDACTED]")
    }

  def redactThrowable(t: Throwable): String = {
    val sb = new StringBuilder
    val seen = new java.util.IdentityHashMap[Throwable, Boolean]()
    var current: Throwable = t
    while (current != null && !seen.containsKey(current)) {
      seen.put(current, java.lang.Boolean.TRUE)
      val msg =
        Option(current.getMessage).getOrElse(current.getClass.getSimpleName)
      sb.append(redact(msg))
      current = current.getCause
      if (current != null && !seen.containsKey(current))
        sb.append("\nCaused by: ")
    }
    sb.toString
  }
}
