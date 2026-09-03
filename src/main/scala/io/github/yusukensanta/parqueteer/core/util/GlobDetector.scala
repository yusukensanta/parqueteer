package io.github.yusukensanta.parqueteer.core.util

/**
 * Detects Hadoop-style glob metacharacters (*, ?, [, {) in a path string.
 *
 * A '?' immediately followed by a `key=value`-shaped suffix (e.g.
 * `?versionId=abc123`) is treated as the start of a cloud-URI query string,
 * not a single-character wildcard — this codebase already anticipates such
 * query strings (see FileExtension.of) and a literal '?' in the query
 * portion must not be misdetected as a glob trigger. A bare '?' with no
 * such suffix (e.g. `file?.parquet`) is still a wildcard.
 */
object GlobDetector {
  private val globChars        = Set('*', '[', '{')
  private val queryValuePrefix = """[\w.\-]+=""".r

  def hasGlobChars(path: String): Boolean = {
    var i = 0
    while i < path.length do {
      val c = path.charAt(i)
      if globChars.contains(c) then return true
      if c == '?' then return queryValuePrefix.findPrefixOf(path.substring(i + 1)).isEmpty
      i += 1
    }
    false
  }
}
