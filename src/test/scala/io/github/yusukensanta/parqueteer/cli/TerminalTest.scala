package io.github.yusukensanta.parqueteer.cli

import org.scalatest.flatspec.AnyFlatSpec

class TerminalTest extends CliTestSupport {

  // ── showStatus ─────────────────────────────────────────────────────────

  "showStatus" should "return false when quiet" in {
    Terminal.showStatus(quietOpts) shouldBe false
  }
}
