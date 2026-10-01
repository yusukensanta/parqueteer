package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.util.CredentialRedactor

/** Error reporting and output-path checks shared by every command handler. */
private[cli] object CommandSupport {

  private[cli] val cloudUriPattern = "^(s3a?|gs|abfss?|wasbs?)://".r

  private[cli] def reportError(
      prefix: String,
      opts: GlobalOptions,
      hint: Option[String] = None
  )(error: ParqueteerError): Int = {
    System.err.println(
      s"$prefix: ${CredentialRedactor.redact(error.userMessage)}"
    )
    hint.foreach(System.err.println)
    if opts.verbose then
      error match {
        case ParqueteerError.IOError(cause) =>
          System.err.println(
            s"[verbose] ${CredentialRedactor.redactThrowable(cause)}"
          )
          cause.getStackTrace.foreach(f =>
            System.err.println(s"\tat ${CredentialRedactor.redact(f.toString)}")
          )
        case _ => ()
      }
    error.exitCode
  }

  private[cli] def warnIfSchemaModeNoop(
      schemaMode: SchemaMode,
      globalOptions: GlobalOptions
  ): Unit =
    if schemaMode != SchemaMode.Strict && !globalOptions.quiet then
      System.err.println(
        "[parqueteer] warning: --schema-mode has no effect — the path resolved to a single file."
      )

  private[cli] def checkOutputWritable(
      outputPath: String
  ): Either[ParqueteerError, Unit] =
    if cloudUriPattern.findFirstIn(outputPath).isDefined then Right(())
    else {
      val parent = java.nio.file.Paths.get(outputPath).toAbsolutePath.getParent
      // Files.isWritable performs a real access() check via the filesystem
      // provider and respects POSIX ACLs; java.io.File#canWrite does not.
      // This is still check-then-act (the real write can still fail after
      // this passes) — it's a fail-fast UX nicety, not a security boundary.
      if parent != null && java.nio.file.Files.exists(parent) &&
        !java.nio.file.Files.isWritable(parent)
      then
        Left(
          ParqueteerError.IOError(
            new java.io.IOException(
              s"Output directory is not writable: $parent"
            )
          )
        )
      else Right(())
    }
}
