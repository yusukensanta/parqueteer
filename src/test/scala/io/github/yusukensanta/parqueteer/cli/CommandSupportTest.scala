package io.github.yusukensanta.parqueteer.cli

import io.github.yusukensanta.parqueteer.core.models.*
import org.scalatest.flatspec.AnyFlatSpec

class CommandSupportTest extends CliTestSupport {

  // ── reportError ────────────────────────────────────────────────────────

  "reportError" should "return the error's exit code" in {
    val (code, _) = captureStderr {
      CommandSupport.reportError("Test", quietOpts)(
        ParqueteerError.FileNotFound("/missing")
      )
    }
    code shouldBe 3
  }

  it should "print prefix and error message to stderr" in {
    val (_, stderr) = captureStderr {
      CommandSupport.reportError("Failed", defaultOpts)(
        ParqueteerError.FileNotFound("/x")
      )
    }
    stderr should include("Failed:")
    stderr should include("/x")
  }

  it should "redact credentials in error messages" in {
    val (_, stderr) = captureStderr {
      CommandSupport.reportError("Err", defaultOpts)(
        ParqueteerError.IOError(
          new java.io.IOException("AccessKey=AKIAIOSFODNN7EXAMPLE leaked")
        )
      )
    }
    stderr should not include "AKIAIOSFODNN7EXAMPLE"
  }

  it should "print hint when provided" in {
    val (_, stderr) = captureStderr {
      CommandSupport.reportError("Err", defaultOpts, Some("Try --help"))(
        ParqueteerError.InvalidFormat("f", "bad")
      )
    }
    stderr should include("Try --help")
  }

  // ── checkOutputWritable ────────────────────────────────────────────────

  "checkOutputWritable" should "pass cloud URIs without filesystem check" in {
    CommandSupport.checkOutputWritable("s3://bucket/key") shouldBe Right(())
    CommandSupport.checkOutputWritable("gs://bucket/key") shouldBe Right(())
    CommandSupport.checkOutputWritable("abfss://container@account/path") shouldBe Right(())
  }

  it should "pass for writable local paths" in {
    val tmpDir = java.nio.file.Files.createTempDirectory("pqt-test")
    try
      CommandSupport.checkOutputWritable(
        tmpDir.resolve("out.parquet").toString
      ) shouldBe Right(())
    finally
      java.nio.file.Files.delete(tmpDir)
  }

  // ── reportError branch coverage ────────────────────────────────────────

  it should "suppress error output when quiet" in {
    val (code, stderr) = captureStderr {
      CommandSupport.reportError("Err", quietOpts)(
        ParqueteerError.FileNotFound("/x")
      )
    }
    code shouldBe 3
    stderr should include("/x")
  }

  "reportError" should "show stack trace for verbose non-quiet" in {
    val verboseOpts = GlobalOptions(verbose = true, quiet = false)
    val cause       = new RuntimeException("deep cause")
    val (code, stderr) = captureStderr {
      CommandSupport.reportError("Err", verboseOpts)(
        ParqueteerError.IOError(cause)
      )
    }
    code should not be 0
    stderr should include("deep cause")
  }

  // ── warnIfSchemaModeNoop ─────────────────────────────────────────────────

  "warnIfSchemaModeNoop" should "warn when schema-mode is Union" in {
    val (_, err) = captureStderr {
      CommandSupport.warnIfSchemaModeNoop(SchemaMode.Union, defaultOpts)
    }
    err should include("--schema-mode has no effect")
  }

  it should "stay silent when schema-mode is the default Strict" in {
    val (_, err) = captureStderr {
      CommandSupport.warnIfSchemaModeNoop(SchemaMode.Strict, defaultOpts)
    }
    err shouldBe empty
  }

  it should "stay silent in quiet mode even for Union" in {
    val (_, err) = captureStderr {
      CommandSupport.warnIfSchemaModeNoop(SchemaMode.Union, quietOpts)
    }
    err shouldBe empty
  }

  "execute" should "warn on a single-match ReadCommand when --schema-mode is a no-op" in {
    val (_, err) = captureStderr {
      CommandExecutor.execute(
        ReadCommand("/tmp/test.parquet", schemaMode = SchemaMode.Union),
        newService(),
        defaultOpts
      )
    }
    err should include("--schema-mode has no effect")
  }

  it should "warn on a single-match WriteCommand when --schema-mode is a no-op" in {
    val (_, err) = captureStderr {
      CommandExecutor.execute(
        WriteCommand("in.json", "s3://b/o.parquet", schemaMode = SchemaMode.Union),
        newService(),
        defaultOpts
      )
    }
    err should include("--schema-mode has no effect")
  }

  it should "warn on a single-match ConvertCommand when --schema-mode is a no-op" in {
    val (_, err) = captureStderr {
      CommandExecutor.execute(
        ConvertCommand("input.csv", "output.parquet", schemaMode = SchemaMode.Union),
        newService(),
        defaultOpts
      )
    }
    err should include("--schema-mode has no effect")
  }
}
