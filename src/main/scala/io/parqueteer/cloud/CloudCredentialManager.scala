package io.parqueteer.cloud

import io.parqueteer.core.models.StorageLocation
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
      case _: io.parqueteer.core.models.S3Location =>
        Some(new S3CredentialManager)
      case _: io.parqueteer.core.models.GCSLocation =>
        Some(new GCSCredentialManager)
      case _: io.parqueteer.core.models.AzureLocation =>
        Some(new AzureCredentialManager)
      case _: io.parqueteer.core.models.LocalPath => None
    }
  }
}

sealed trait CredentialError
case class AuthenticationFailed(message: String) extends CredentialError
case class ConfigurationError(message: String) extends CredentialError
case class NetworkError(message: String) extends CredentialError

sealed trait CredentialResolutionStrategy
case object EnvironmentVariables extends CredentialResolutionStrategy
case object ProfileBased extends CredentialResolutionStrategy
case object InstanceMetadata extends CredentialResolutionStrategy
case object ServiceAccount extends CredentialResolutionStrategy
case object ManagedIdentity extends CredentialResolutionStrategy
