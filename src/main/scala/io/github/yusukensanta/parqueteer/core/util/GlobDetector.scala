package io.github.yusukensanta.parqueteer.core.util

/** Detects Hadoop-style glob metacharacters (*, ?, [, {) in a path string. */
object GlobDetector {
  private val globChars = Set('*', '?', '[', '{')

  def hasGlobChars(path: String): Boolean = path.exists(globChars.contains)
}
