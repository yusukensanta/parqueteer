package io.github.yusukensanta.parqueteer.core.util

object FileExtension {

  /** Extract the lowercase file extension from a path, stripping query strings.
    *
    * Handles: local paths, cloud URIs with query params (e.g.
    * s3://b/f.parquet?versionId=x). Returns "unknown" when no extension is
    * present.
    */
  def of(path: String): String = {
    val fileName = path.split("/").last.split("\\?").head
    if (fileName.contains(".")) fileName.split("\\.").last.toLowerCase
    else "unknown"
  }
}
