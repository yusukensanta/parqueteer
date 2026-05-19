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
      "schema",
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
      "schema",
      "config",
      "completions"
    )
      .foreach(cmd => script should include(cmd))
  }

  it should "complete schema command with file arguments" in {
    ShellCompletions.zsh should include("schema")
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
      "schema",
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
}
