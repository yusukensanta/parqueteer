package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import org.scalatest.flatspec.AnyFlatSpec

class ReadCommandsTest extends CliTestSupport {

  // ── executeRead branch coverage ────────────────────────────────────────

  "executeRead" should "warn and fallback parallelism when filter is set with parallel > 1" in {
    val (code, stderr) = captureStderr {
      ReadCommands.executeRead(
        newService(),
        ReadCommand(
          "/tmp/test.parquet",
          maxRows = None,
          columns = None,
          filter = Some("id > 0"),
          format = OutputFormat.Table,
          parallelism = 4,
          streaming = false
        ),
        defaultOpts
      )
    }
    code shouldBe 0
    stderr should include("--filter disables parallel mode")
  }

  it should "suppress filter-parallel warning when quiet" in {
    val (code, stderr) = captureStderr {
      ReadCommands.executeRead(
        newService(),
        ReadCommand(
          "/tmp/test.parquet",
          maxRows = None,
          columns = None,
          filter = Some("id > 0"),
          format = OutputFormat.Table,
          parallelism = 4,
          streaming = false
        ),
        quietOpts
      )
    }
    code shouldBe 0
    stderr should not include "--filter disables parallel mode"
  }

  it should "use streaming for NDJSON format" in {
    ReadCommands.executeRead(
      newService(),
      ReadCommand(
        "/tmp/test.parquet",
        maxRows = None,
        columns = None,
        filter = None,
        format = OutputFormat.NDJSON,
        parallelism = 1,
        streaming = false
      ),
      quietOpts
    ) shouldBe 0
  }

  it should "use streaming when streaming flag is set" in {
    ReadCommands.executeRead(
      newService(),
      ReadCommand(
        "/tmp/test.parquet",
        maxRows = None,
        columns = None,
        filter = None,
        format = OutputFormat.CSV,
        parallelism = 1,
        streaming = true
      ),
      quietOpts
    ) shouldBe 0
  }
}
