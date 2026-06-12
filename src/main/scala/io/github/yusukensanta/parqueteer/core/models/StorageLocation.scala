package io.github.yusukensanta.parqueteer.core.models

import scala.util.matching.Regex

sealed trait StorageLocation {
  def path: String
}

case class LocalPath(path: String) extends StorageLocation

case class S3Location(
    bucket: String,
    key: String,
    region: Option[String] = None
) extends StorageLocation {
  override def path: String = s"s3a://$bucket/$key"
}

case class GCSLocation(
    bucket: String,
    key: String
) extends StorageLocation {
  override def path: String = s"gs://$bucket/$key"
}

case class AzureLocation(
    account: String,
    container: String,
    key: String
) extends StorageLocation {
  override def path: String =
    s"abfss://$container@$account.dfs.core.windows.net/$key"
}

object StorageLocationParser {
  private val s3Pattern: Regex = """s3a?://([^/]+)/(.+)""".r
  private val gcsPattern: Regex = """gs://([^/]+)/(.+)""".r
  // Matches both abfss:// (secure) and abfs:// (non-secure); both map to AzureLocation.
  private val azurePattern: Regex =
    """abfss?://([^@]+)@([^.]+)\.dfs\.core\.windows\.net/(.+)""".r
  private val wasbPattern: Regex = """wasbs?://.*""".r

  /** Parse a storage URL into the appropriate StorageLocation
    *
    * @param url
    *   The storage URL to parse
    * @return
    *   Either an error message or the parsed StorageLocation
    */
  def parse(url: String): Either[String, StorageLocation] = {
    url.trim match {
      case s3Pattern(bucket, key) =>
        Right(S3Location(bucket, key))
      case gcsPattern(bucket, key) =>
        Right(GCSLocation(bucket, key))
      case azurePattern(container, account, path) =>
        // Regex captures (container, account, path), constructor expects (account, container, path)
        Right(AzureLocation(account, container, path))
      case wasbPattern() =>
        Left(
          s"wasb:// and wasbs:// (Azure Blob Storage) are not supported. Use abfss:// for Azure Data Lake Storage Gen2."
        )
      case typo if typo.matches("[a-zA-Z][a-zA-Z0-9+.\\-]*:/[^/].*") =>
        Left(
          s"Malformed URL '$typo'. Did you mean '${typo.replaceFirst(":/", "://")}'?"
        )
      case localPath if !localPath.contains("://") =>
        Right(LocalPath(localPath))
      case unsupported =>
        Left(s"Unsupported storage location format: $unsupported")
    }
  }

}
