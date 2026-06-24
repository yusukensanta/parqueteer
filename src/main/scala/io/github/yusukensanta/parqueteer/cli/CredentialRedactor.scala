package io.github.yusukensanta.parqueteer.cli

private[parqueteer] object CredentialRedactor {

  def redact(s: String): String =
    io.github.yusukensanta.parqueteer.core.util.CredentialRedactor.redact(s)

  def redactThrowable(t: Throwable): String =
    io.github.yusukensanta.parqueteer.core.util.CredentialRedactor.redactThrowable(t)
}
