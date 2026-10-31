package io.github.yusukensanta.parqueteer.core.util

import scala.annotation.tailrec

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
    @tailrec def scan(i: Int): Boolean =
      if i >= path.length then false
      else {
        val c = path.charAt(i)
        if globChars.contains(c) then true
        else if c == '?' then queryValuePrefix.findPrefixOf(path.substring(i + 1)).isEmpty
        else scan(i + 1)
      }
    scan(0)
  }
}
