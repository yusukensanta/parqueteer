package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*

/** Terminal capability detection. */
private[cli] object Terminal {

  // Since JDK 22, System.console() always returns a non-null Console even when
  // streams are redirected/piped (JLine became the default provider) — the old
  // `System.console() != null` check would leak ANSI colors into redirected
  // output on 22+. Console.isTerminal() (also added in 22) gives the accurate
  // answer, but this project compiles against JDK 17/21 stdlib, so it can't be
  // called directly; reflection lets it work correctly at runtime on any JDK.
  // On JDK <22, `console() != null` already meant a real terminal, so a missing
  // isTerminal() method (NoSuchMethodException) safely falls back to `true`.
  private[cli] def isStdoutTTY: Boolean = {
    val console = System.console()
    console != null && {
      try console.getClass.getMethod("isTerminal").invoke(console).asInstanceOf[Boolean]
      catch case _: NoSuchMethodException => true
    }
  }

  private[cli] def showStatus(opts: GlobalOptions): Boolean =
    !opts.quiet && isStdoutTTY
}
