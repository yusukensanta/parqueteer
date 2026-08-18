package io.github.yusukensanta.parqueteer.core.util

object FileExtension {

  /**
   * Extract the lowercase file extension from a path, stripping query strings.
   *
   * Handles: local paths, cloud URIs with query params (e.g.
   * s3://b/f.parquet?versionId=x). Returns "unknown" when no extension is
   * present.
   */
  def of(path: String): String = {
    // Strip query string before finding the last path segment, to handle URIs
    // like s3://bucket/file.parquet?prefix=a/b where '/' appears inside the query.
    val noQuery = path.indexOf('?') match {
      case -1  => path
      case idx => path.substring(0, idx)
    }
    val fn  = noQuery.substring(noQuery.lastIndexOf('/') + 1)
    val dot = fn.lastIndexOf('.')
    if dot <= 0 || dot == fn.length - 1 then "unknown"
    else fn.substring(dot + 1).toLowerCase
  }
}
