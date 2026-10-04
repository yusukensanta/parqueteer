package io.github.yusukensanta.parqueteer.cli

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ShellCompletionsTest extends AnyFlatSpec with Matchers {

  "ShellCompletions.bash" should "register completion function" in {
    ShellCompletions.bash should include("_parqueteer")
    ShellCompletions.bash should include("complete -F _parqueteer parqueteer")
  }

  it should "cover all top-level commands" in {
    val script = ShellCompletions.bash
    Seq(
      "read",
      "info",
      "write",
      "validate",
      "convert",
      "merge",
      "schema",
      "stats",
      "config",
      "completions"
    )
      .foreach(cmd => script should include(cmd))
  }

  it should "include format values" in {
    ShellCompletions.bash should include("table")
    ShellCompletions.bash should include("json")
    ShellCompletions.bash should include("csv")
  }

  it should "include compression values" in {
    ShellCompletions.bash should include("snappy")
    ShellCompletions.bash should include("zstd")
  }

  "ShellCompletions.zsh" should "define compdef binding" in {
    ShellCompletions.zsh should include("#compdef parqueteer")
    ShellCompletions.zsh should include("_parqueteer")
  }

  it should "cover all top-level commands" in {
    val script = ShellCompletions.zsh
    Seq(
      "read",
      "info",
      "write",
      "validate",
      "convert",
      "merge",
      "schema",
      "stats",
      "config",
      "completions"
    )
      .foreach(cmd => script should include(cmd))
  }

  it should "complete schema diff subcommand with file arguments" in {
    ShellCompletions.zsh should include("diff")
    ShellCompletions.zsh should include("*.parquet")
  }

  "ShellCompletions.fish" should "use fish complete syntax" in {
    ShellCompletions.fish should include("complete -c parqueteer")
    ShellCompletions.fish should include("__fish_use_subcommand")
    ShellCompletions.fish should include("__fish_seen_subcommand_from")
  }

  it should "cover all top-level commands" in {
    val script = ShellCompletions.fish
    Seq(
      "read",
      "info",
      "write",
      "validate",
      "convert",
      "merge",
      "schema",
      "stats",
      "config",
      "completions"
    )
      .foreach(cmd => script should include(cmd))
  }

  it should "complete format values for read" in {
    ShellCompletions.fish should include("__fish_seen_subcommand_from read")
    ShellCompletions.fish should include(
      "table json csv pretty markdown ndjson"
    )
  }

  it should "complete shell names for completions subcommand" in {
    ShellCompletions.fish should include("bash zsh fish")
  }

  // ── Generated from CliSpec ─────────────────────────────────────────────

  private val scripts = Shell.values.toList.map(sh => sh -> ShellCompletions.scriptFor(sh))

  "Every completion script" should "offer every option of every command and every global option" in {
    val options = CliSpec.globalOptions ++ CliSpec.commands.flatMap(_.options)
    for {
      (shell, script) <- scripts
      o               <- options
    } withClue(s"$shell --${o.long}: ")(script should include(o.long))
  }

  it should "offer every enum value the parser accepts" in {
    for {
      (shell, script) <- scripts
      values <- List(
        CliSpec.Choices.formats,
        CliSpec.Choices.compressions,
        CliSpec.Choices.inputFormats,
        CliSpec.Choices.schemaModes,
        CliSpec.Choices.colors,
        CliSpec.Choices.shells
      )
    } withClue(s"$shell: ")(script should include(values.mkString(" ")))
  }

  "ShellCompletions.bash" should "be valid bash syntax" in {
    val bash = new java.io.File("/bin/bash")
    assume(bash.canExecute, "bash not installed")
    val file = java.nio.file.Files.createTempFile("parqueteer_completion_", ".bash")
    file.toFile.deleteOnExit()
    java.nio.file.Files.writeString(file, ShellCompletions.bash)
    val proc =
      new ProcessBuilder(bash.getPath, "-n", file.toString).redirectErrorStream(true).start()
    val out = new String(proc.getInputStream.readAllBytes())
    withClue(out)(proc.waitFor() shouldBe 0)
  }
}
