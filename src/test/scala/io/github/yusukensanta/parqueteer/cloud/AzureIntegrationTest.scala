package io.github.yusukensanta.parqueteer.cloud

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.repositories.HadoopParquetRepository
import org.scalatest.Tag
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.UUID
import scala.util.Try

object AzureIntegrationTest extends Tag("AzureIntegrationTest")

/**
 * Runs against a real Azure Data Lake Storage Gen2 account. Azurite cannot stand in: it does not
 * serve the `dfs` endpoint that the ABFS driver talks to.
 *
 * Required env vars: AZURE_E2E_ACCOUNT, AZURE_E2E_CONTAINER. Credentials come from the usual
 * AZURE_AUTH_METHOD handling (the CI job signs in with `az login` through GitHub OIDC), and the
 * identity needs a data role such as Storage Blob Data Contributor on the container.
 *
 * All tests are skipped when the account or container is absent.
 */
class AzureIntegrationTest extends AnyFlatSpec with Matchers {

  private val account   = sys.env.get("AZURE_E2E_ACCOUNT").filter(_.nonEmpty)
  private val container = sys.env.get("AZURE_E2E_CONTAINER").filter(_.nonEmpty)
  private val repo      = new HadoopParquetRepository()

  private val sampleData: List[Map[String, CellValue]] = List(
    Map("id" -> CellValue.I64(1L), "name" -> CellValue.Str("Alice")),
    Map("id" -> CellValue.I64(2L), "name" -> CellValue.Str("Bob")),
    Map("id" -> CellValue.I64(3L), "name" -> CellValue.Str("Charlie"))
  )

  private def assumeAzureAvailable(): Unit =
    assume(
      account.isDefined && container.isDefined,
      "Skipped: AZURE_E2E_ACCOUNT / AZURE_E2E_CONTAINER not set"
    )

  // A unique key per test keeps concurrent CI runs apart (ABFS write never overwrites).
  private def freshLocation(label: String): AzureLocation =
    AzureLocation(account.get, container.get, s"ci/$label-${UUID.randomUUID()}.parquet")

  // CloudAuthException keeps the per-credential reason apart from its message.
  private def describe(e: Throwable): String = e match {
    case auth: ParqueteerError.CloudAuthException =>
      s"${auth.getMessage}\n${auth.detail.getOrElse("(no detail)")}"
    case other => Option(other.getMessage).getOrElse("")
  }

  private def assertSuccess[A](result: Try[A], label: String): A =
    result.fold(
      e => fail(s"$label failed: ${e.getClass.getName}: ${describe(e)}", e),
      identity
    )

  private def withWrittenFile[A](label: String)(body: AzureLocation => A): A = {
    val loc = freshLocation(label)
    try {
      assertSuccess(repo.writeContent(loc, sampleData, None), "write")
      body(loc)
    } finally Try(repo.deleteFile(loc))
  }

  "ParquetRepository" should "write a parquet file to Azure and read it back" taggedAs AzureIntegrationTest in {
    assumeAzureAvailable()

    withWrittenFile("roundtrip") { loc =>
      val content =
        assertSuccess(repo.readContent(ParquetFile(loc), ReadConfig()), "read")
      content.rows should have length 3
      content.rows.flatMap(_.get("name")) should contain(CellValue.Str("Alice"))
    }
  }

  it should "read the schema and a non-zero file size from Azure" taggedAs AzureIntegrationTest in {
    assumeAzureAvailable()

    withWrittenFile("info") { loc =>
      val info   = assertSuccess(repo.readFileInfo(ParquetFile(loc)), "readFileInfo")
      val schema = info._1
      val meta   = info._2
      schema.columns.map(_.name) should contain allOf ("id", "name")
      schema.totalRowCount shouldBe 3L
      meta.fileSize should be > 0L
    }
  }

  it should "delete the file so that a later read fails" taggedAs AzureIntegrationTest in {
    assumeAzureAvailable()

    val loc = freshLocation("delete")
    assertSuccess(repo.writeContent(loc, sampleData, None), "write")
    assertSuccess(repo.deleteFile(loc), "delete")
    repo.readContent(ParquetFile(loc), ReadConfig()).isFailure shouldBe true
  }
}
