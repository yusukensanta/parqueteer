package io.github.yusukensanta.parqueteer.core.util

private[parqueteer] object CredentialRedactor {

  private val patterns: Seq[scala.util.matching.Regex] = Seq(
    "(?i)(Authorization\\s*[:=]\\s*)\\S[^&\\n\\r]*".r,
    // Redact secret X-Amz headers. Non-secret debug headers (Date, Algorithm, SignedHeaders)
    // are intentionally excluded. SSE-C key material is included because it is an AES-256 secret.
    "(?i)(X-Amz-(?:Security-Token|Credential|Signature|Server-Side-Encryption-Customer-Key(?:-MD5)?|Copy-Source-Server-Side-Encryption-Customer-Key(?:-MD5)?)\\s*[:=]\\s*)\\S[^&\\n\\r]*".r,
    "(?i)(AWSAccessKeyId=)[^&\\s]+".r,
    "(?i)(Signature=)[^&\\s]+".r,
    "(?i)(aws_secret_access_key\\s*=\\s*)\\S+".r,
    "(?i)((?:\\?|&)sig=)[^&\\s]+".r,
    "(-----BEGIN [A-Z ]+-----)[\\s\\S]*?-----END [A-Z ]+-----".r,
    // AWS access key ID prefixes only: AKIA (long-term), ASIA (session). Role/instance/service
    // principal IDs (AROA, AIPA, ANPA, AGPA, AIDA) are public identifiers, not secrets.
    "()\\bA(?:KIA|SIA)[A-Z0-9]{16}\\b".r,
    // Azure storage account key embedded in Hadoop config property value
    "(?i)(fs\\.azure\\.account\\.key\\.[^=\\s]+=?)\\S+".r,
    // Azure OAuth client secret (ABFS service-principal config)
    "(?i)(fs\\.azure\\.account\\.oauth2\\.client\\.secret\\.[^=\\s]+=?)\\S+".r,
    // S3A secret key and session token (Hadoop S3A config)
    "(?i)(fs\\.s3a\\.secret\\.key=?)\\S+".r,
    "(?i)(fs\\.s3a\\.session\\.token=?)\\S+".r,
    // Bearer JWT / OAuth2 token in log lines (e.g. "Bearer eyJ...")
    "(Bearer\\s+)[A-Za-z0-9+/._-]{20,}".r,
    // Bare AWS session token / STS credential in exception messages
    "(?i)(aws_session_token\\s*[=:]\\s*)\\S[^&\\n\\r]*".r,
    "(?i)(x-amz-security-token\\s*[=:]\\s*)\\S[^&\\n\\r]*".r,
    // Azure SAS token in URI (?sig=... or &sv=...&sig=...)
    "(?i)([?&]sig=)[^&\\s]+".r,
    "(?i)(sas_token\\s*[=:]\\s*)\\S[^&\\n\\r]*".r,
    // GCP API keys (AIza...) and OAuth2 access tokens (ya29.)
    "()(AIza[A-Za-z0-9_-]{35,})".r,
    "()(ya29\\.[A-Za-z0-9_-]{20,})".r,
    // GCP service-account private_key value (JSON-escaped, single-line)
    "(?i)(\"private_key\"\\s*:\\s*\")([^\"]{20,})".r,
    // Azure connection string AccountKey
    "(?i)(AccountKey=)[^;\\s]+".r,
    // Generic password/token in key=value contexts (config dumps, error messages)
    "(?i)((?:password|secret_key|client_secret|api_key)\\s*[=:]\\s*)\\S[^&\\n\\r]*".r,
    // GCP OAuth2 refresh token (1//... pattern)
    "()(1//[A-Za-z0-9_-]{20,})".r
  )

  def redact(s: String): String =
    if s == null then ""
    else
      patterns.foldLeft(s) { (acc, pattern) =>
        pattern.replaceAllIn(
          acc,
          // group(1) is the prefix to preserve; patterns without a prefix use an empty capturing group
          m =>
            java.util.regex.Matcher.quoteReplacement(
              Option(m.group(1)).getOrElse("") + "[REDACTED]"
            )
        )
      }

  private val MaxCauseChain = 20

  def redactThrowable(t: Throwable): String = {
    val sb                 = new StringBuilder
    val seen               = new java.util.IdentityHashMap[Throwable, Boolean]()
    var current: Throwable = t
    var depth              = 0
    while current != null && !seen.containsKey(current) && depth < MaxCauseChain
    do {
      seen.put(current, java.lang.Boolean.TRUE)
      val msg =
        Option(current.getMessage).getOrElse(current.getClass.getSimpleName)
      sb.append(redact(msg))
      current = current.getCause
      depth += 1
      if current != null && !seen.containsKey(current) && depth < MaxCauseChain
      then sb.append("\nCaused by: ")
    }
    if current != null && depth >= MaxCauseChain then sb.append("\n[cause chain truncated]")
    sb.toString
  }
}
