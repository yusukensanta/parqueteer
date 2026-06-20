package io.github.yusukensanta.parqueteer.cloud

import io.github.yusukensanta.parqueteer.core.models.{AzureLocation, StorageLocation}
import org.apache.hadoop.conf.Configuration
import scala.util.{Failure, Try}

class AzureCredentialManager extends CloudCredentialManager {

  override def configureHadoop(
      location: StorageLocation
  ): Try[Configuration] =
    location match {
      case azureLocation: AzureLocation =>
        Try {
          val conf = new Configuration()

          // Azure Data Lake Storage Gen2 configuration
          conf.set("fs.azure.account.auth.type", "OAuth")
          conf.set(
            "fs.azure.account.oauth.provider.type",
            "org.apache.hadoop.fs.azurebfs.oauth2.ClientCredsTokenProvider"
          )

          // Configure based on authentication method
          val authMethod =
            sys.env.get("AZURE_AUTH_METHOD").getOrElse("managed_identity")

          authMethod match {
            case "managed_identity" =>
              configureManagedIdentity(conf, azureLocation)
            case "service_principal" =>
              configureServicePrincipal(conf, azureLocation)
            case "shared_key" =>
              configureSharedKey(conf, azureLocation)
            case "sas_token" =>
              configureSasToken(conf, azureLocation)
            case unknown =>
              throw new RuntimeException(s"Unknown Azure auth method: $unknown")
          }

          // Performance and reliability settings
          conf.set("fs.azure.io.retry.max.retries", "3")
          conf.set("fs.azure.io.retry.backoff.interval", "3000")
          conf.set("fs.azure.read.request.size", "8388608")  // 8MB
          conf.set("fs.azure.write.request.size", "8388608") // 8MB

          conf
        }
      case _ =>
        Failure(new IllegalArgumentException("Expected AzureLocation"))
    }

  private def configureManagedIdentity(
      conf: Configuration,
      location: AzureLocation
  ): Unit = {
    conf.set(
      s"fs.azure.account.auth.type.${location.account}.dfs.core.windows.net",
      "OAuth"
    )
    conf.set(
      s"fs.azure.account.oauth.provider.type.${location.account}.dfs.core.windows.net",
      "org.apache.hadoop.fs.azurebfs.oauth2.MsiTokenProvider"
    )

    // Optional: Set tenant ID if provided
    sys.env.get("AZURE_TENANT_ID").foreach { tenantId =>
      conf.set(
        s"fs.azure.account.oauth2.msi.tenant.${location.account}.dfs.core.windows.net",
        tenantId
      )
    }

    // Optional: Set client ID for user-assigned managed identity
    sys.env.get("AZURE_CLIENT_ID").foreach { clientId =>
      conf.set(
        s"fs.azure.account.oauth2.client.id.${location.account}.dfs.core.windows.net",
        clientId
      )
    }
  }

  private def configureServicePrincipal(
      conf: Configuration,
      location: AzureLocation
  ): Unit = {
    val clientId     = CloudCredentialManager.requiredEnv("AZURE_CLIENT_ID")
    val clientSecret = CloudCredentialManager.requiredEnv("AZURE_CLIENT_SECRET")
    val tenantId     = CloudCredentialManager.requiredEnv("AZURE_TENANT_ID")

    conf.set(
      s"fs.azure.account.auth.type.${location.account}.dfs.core.windows.net",
      "OAuth"
    )
    conf.set(
      s"fs.azure.account.oauth.provider.type.${location.account}.dfs.core.windows.net",
      "org.apache.hadoop.fs.azurebfs.oauth2.ClientCredsTokenProvider"
    )
    conf.set(
      s"fs.azure.account.oauth2.client.id.${location.account}.dfs.core.windows.net",
      clientId
    )
    conf.set(
      s"fs.azure.account.oauth2.client.secret.${location.account}.dfs.core.windows.net",
      clientSecret
    )
    conf.set(
      s"fs.azure.account.oauth2.client.endpoint.${location.account}.dfs.core.windows.net",
      s"https://login.microsoftonline.com/$tenantId/oauth2/token"
    )
  }

  private def configureSharedKey(
      conf: Configuration,
      location: AzureLocation
  ): Unit = {
    val accountKey = CloudCredentialManager.requiredEnv("AZURE_STORAGE_KEY")

    // Explicit per-account auth type overrides the global OAuth default set above.
    conf.set(
      s"fs.azure.account.auth.type.${location.account}.dfs.core.windows.net",
      "SharedKey"
    )
    conf.set(
      s"fs.azure.account.key.${location.account}.dfs.core.windows.net",
      accountKey
    )
  }

  private def configureSasToken(
      conf: Configuration,
      location: AzureLocation
  ): Unit = {
    val sasToken = CloudCredentialManager.requiredEnv("AZURE_STORAGE_SAS_TOKEN")

    // Explicit per-account auth type overrides the global OAuth default set above.
    conf.set(
      s"fs.azure.account.auth.type.${location.account}.dfs.core.windows.net",
      "SAS"
    )
    // ABFS FixedSASTokenProvider reads the token from this key — NOT the legacy
    // WASB form fs.azure.sas.<container>.<account>.dfs.core.windows.net.
    conf.set(
      s"fs.azure.sas.fixed.token.${location.container}.${location.account}.dfs.core.windows.net",
      sasToken
    )
  }
}
