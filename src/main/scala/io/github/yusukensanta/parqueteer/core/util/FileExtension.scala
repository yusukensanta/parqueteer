package io.github.yusukensanta.parqueteer.core.util

object FileExtension {

  /** Extract the lowercase file extension from a path, stripping query strings.
    *
    * Handles: local paths, cloud URIs with query params (e.g.
    * s3://b/f.parquet?versionId=x). Returns "unknown" when no extension is
    * present.
    */
  def of(path: String): String = {
    val fn = path.split("/").last.split("\\?").head
    val dot = fn.lastIndexOf('.')
    if (dot <= 0) "unknown" else fn.substring(dot + 1).toLowerCase
  }
}
