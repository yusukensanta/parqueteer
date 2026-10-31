package io.github.yusukensanta.parqueteer.cloud

import com.azure.core.credential.{TokenCredential, TokenRequestContext}
import com.azure.identity.{AzureCliCredentialBuilder, DefaultAzureCredentialBuilder}
import io.github.yusukensanta.parqueteer.core.util.CredentialRedactor
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.azurebfs.extensions.CustomTokenProviderAdaptee

import java.io.IOException
import java.util.Date

/**
 * Raised when azure-identity cannot produce an access token. ABFS wraps token failures in
 * unrelated exceptions (e.g. a bogus "invalid hns.enabled" error), so callers look for this type
 * in the cause chain to report the real reason.
 */
class AzureTokenException(message: String, cause: Throwable) extends IOException(message, cause)

/**
 * Bridges azure-identity credentials to the ABFS driver.
 *
 * ABFS instantiates this class by reflection (hence the public no-arg constructor) when
 * `fs.azure.account.auth.type` is `Custom`. The credential kind comes from [[CredentialKey]]:
 *   - `default`: DefaultAzureCredential (environment, workload identity, managed identity, then
 *     developer tools such as `az login`)
 *   - `azure_cli`: only the signed-in Azure CLI session
 */
class AzureIdentityTokenProvider extends CustomTokenProviderAdaptee {
  import AzureIdentityTokenProvider.*

  private var credential: Option[TokenCredential] = None

  // Written in getAccessToken and read right after by the ABFS token adapter,
  // which serialises both calls under the provider lock.
  @volatile private var expiry: Date = new Date(0)

  override def initialize(conf: Configuration, accountName: String): Unit =
    credential = Some(conf.get(CredentialKey, KindDefault) match {
      case KindDefault  => new DefaultAzureCredentialBuilder().build()
      case KindAzureCli => new AzureCliCredentialBuilder().build()
      case other        =>
        throw new IOException(s"Unknown Azure credential kind: $other")
    })

  private[cloud] def useCredential(cred: TokenCredential): Unit =
    credential = Some(cred)

  override def getAccessToken(): String = {
    val cred = credential.getOrElse(
      throw new IOException("Azure token provider is not initialized")
    )
    try {
      val token = cred.getTokenSync(new TokenRequestContext().addScopes(StorageScope))
      expiry = Date.from(token.getExpiresAt.toInstant)
      token.getToken
    } catch {
      case e: RuntimeException =>
        throw new AzureTokenException(
          s"Could not get an Azure access token: ${CredentialRedactor
              .redact(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))}. " +
            "Run `az login`, or set AZURE_AUTH_METHOD to service_principal, shared_key or sas_token.",
          e
        )
    }
  }

  override def getExpiryTime(): Date = expiry
}

object AzureIdentityTokenProvider {
  val CredentialKey: String = "parqueteer.azure.credential"
  val KindDefault: String   = "default"
  val KindAzureCli: String  = "azure_cli"

  private val StorageScope = "https://storage.azure.com/.default"
}
