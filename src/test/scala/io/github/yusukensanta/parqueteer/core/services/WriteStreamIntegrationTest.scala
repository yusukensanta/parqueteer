package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.repositories.HadoopParquetRepository
import org.scalatest.Tag
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}

object WriteStreamIntegrationTest extends Tag("IntegrationTest")

class WriteStreamIntegrationTest extends AnyFlatSpec with Matchers {

  private val repo    = new HadoopParquetRepository()
  private val service = new ParquetService(repo)

  private def tempOutputPath(): String = {
    val f = Files.createTempFile("write_stream_it_", ".parquet").toFile
    f.delete()
    f.deleteOnExit()
    f.getAbsolutePath
  }

  private def tempInput(suffix: String, content: String): String = {
    val p: Path = Files.createTempFile("write_stream_it_", suffix)
    Files.writeString(p, content)
    p.toFile.deleteOnExit()
    p.toString
  }

  // ── Round-trip equivalence with the fully-buffered path ─────────────────

  "streamWriteDataFile" should "round-trip NDJSON into parquet with the same rows as the buffered writeFile path" taggedAs WriteStreamIntegrationTest in {
    val ndjson =
      tempInput(".ndjson", "{\"id\":1,\"name\":\"Alice\"}\n{\"id\":2,\"name\":\"Bob\"}\n")

    val streamedOut = tempOutputPath()
    service.streamWriteDataFile(ndjson, "ndjson", streamedOut, WriteConfig()).isRight shouldBe true

    val bufferedData = service.readDataFile(ndjson, "ndjson").toOption.get
    val bufferedOut  = tempOutputPath()
    service.writeFile(bufferedOut, bufferedData, WriteConfig()).isRight shouldBe true

    val streamedContent = repo.readContent(ParquetFile(LocalPath(streamedOut)), ReadConfig()).get
    val bufferedContent = repo.readContent(ParquetFile(LocalPath(bufferedOut)), ReadConfig()).get
    streamedContent.rows shouldBe bufferedContent.rows
  }

  it should "round-trip LTSV into parquet with the same rows as the buffered writeFile path" taggedAs WriteStreamIntegrationTest in {
    val ltsv = tempInput(".ltsv", "id:1\tname:Alice\nid:2\tname:Bob\n")

    val streamedOut = tempOutputPath()
    service.streamWriteDataFile(ltsv, "ltsv", streamedOut, WriteConfig()).isRight shouldBe true

    val bufferedData = service.readDataFile(ltsv, "ltsv").toOption.get
    val bufferedOut  = tempOutputPath()
    service.writeFile(bufferedOut, bufferedData, WriteConfig()).isRight shouldBe true

    val streamedContent = repo.readContent(ParquetFile(LocalPath(streamedOut)), ReadConfig()).get
    val bufferedContent = repo.readContent(ParquetFile(LocalPath(bufferedOut)), ReadConfig()).get
    streamedContent.rows shouldBe bufferedContent.rows
  }

  it should "round-trip CSV into parquet with the same rows as the buffered writeFile path" taggedAs WriteStreamIntegrationTest in {
    val csv = tempInput(".csv", "id,name\n1,Alice\n2,Bob\n")

    val streamedOut = tempOutputPath()
    service.streamWriteDataFile(csv, "csv", streamedOut, WriteConfig()).isRight shouldBe true

    val bufferedData = service.readDataFile(csv, "csv").toOption.get
    val bufferedOut  = tempOutputPath()
    service.writeFile(bufferedOut, bufferedData, WriteConfig()).isRight shouldBe true

    val streamedContent = repo.readContent(ParquetFile(LocalPath(streamedOut)), ReadConfig()).get
    val bufferedContent = repo.readContent(ParquetFile(LocalPath(bufferedOut)), ReadConfig()).get
    streamedContent.rows shouldBe bufferedContent.rows
  }

  // ── Late-appearing column / type widening, end to end ────────────────────

  it should "infer schema from a column that only appears in a later row, end to end via the CLI service path" taggedAs WriteStreamIntegrationTest in {
    val lines  = (1 to 50).map(i => s"""{"id":$i}""") :+ """{"id":51,"extra":"late"}"""
    val ndjson = tempInput(".ndjson", lines.mkString("\n") + "\n")

    val out    = tempOutputPath()
    val result = service.streamWriteDataFile(ndjson, "ndjson", out, WriteConfig())
    result.isRight shouldBe true

    val content = repo.readContent(ParquetFile(LocalPath(out)), ReadConfig()).get
    content.rows should have size 51
    content.rows.last("extra") shouldBe CellValue.Str("late")
  }

  // ── Failure before any output file is created ─────────────────────────────

  it should "leave no output file behind when a later NDJSON line is malformed" taggedAs WriteStreamIntegrationTest in {
    val ndjson = tempInput(".ndjson", "{\"id\":1}\n{\"id\":2}\nNOT VALID JSON\n")
    val out    = tempOutputPath()

    val result = service.streamWriteDataFile(ndjson, "ndjson", out, WriteConfig())
    result.isLeft shouldBe true
    java.nio.file.Files.exists(java.nio.file.Paths.get(out)) shouldBe false
  }

  // ── maxRows applied identically across both passes ────────────────────────

  it should "respect maxRows for NDJSON, writing exactly that many rows" taggedAs WriteStreamIntegrationTest in {
    val ndjson = tempInput(".ndjson", (1 to 10).map(i => s"""{"id":$i}""").mkString("\n") + "\n")
    val out    = tempOutputPath()

    val result =
      service.streamWriteDataFile(ndjson, "ndjson", out, WriteConfig(), maxRows = Some(3L))
    result shouldBe Right(3L)

    val content = repo.readContent(ParquetFile(LocalPath(out)), ReadConfig()).get
    content.rows should have size 3
  }
}
