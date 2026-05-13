package io.github.yusukensanta.parqueteer.cloud

import io.github.yusukensanta.parqueteer.core.models.StorageLocation
import org.apache.hadoop.conf.Configuration
import scala.util.Try

trait CloudCredentialManager {
  def configureHadoop(location: StorageLocation): Try[Configuration]
  def validateCredentials(location: StorageLocation): Try[Unit]
  def supportsLocation(location: StorageLocation): Boolean
}

object CloudCredentialManager {

  def forLocation(location: StorageLocation): Option[CloudCredentialManager] = {
    location match {
      case _: io.github.yusukensanta.parqueteer.core.models.S3Location =>
        Some(new S3CredentialManager)
      case _: io.github.yusukensanta.parqueteer.core.models.GCSLocation =>
        Some(new GCSCredentialManager)
      case _: io.github.yusukensanta.parqueteer.core.models.AzureLocation =>
        Some(new AzureCredentialManager)
      case _: io.github.yusukensanta.parqueteer.core.models.LocalPath => None
    }
  }
}

enum CredentialError:
  case AuthenticationFailed(message: String)
  case ConfigurationError(message: String)
  case NetworkError(message: String)

enum CredentialResolutionStrategy:
  case EnvironmentVariables, ProfileBased, InstanceMetadata, ServiceAccount,
    ManagedIdentity
