package io.github.yusukensanta.parqueteer.cloud

import io.github.yusukensanta.parqueteer.core.models._
import io.github.yusukensanta.parqueteer.core.repositories.ParquetRepository
import org.scalatest.{BeforeAndAfterAll, Tag}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  StaticCredentialsProvider
}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  CreateBucketRequest,
  DeleteBucketRequest,
  DeleteObjectRequest,
  ListObjectsV2Request
}
import java.net.URI
import scala.jdk.CollectionConverters._
import scala.util.Try

object S3IntegrationTest extends Tag("S3IntegrationTest")

/** Runs against a local S3-compatible server (RustFS).
  *
  * Required env vars: AWS_ENDPOINT_URL — e.g. http://localhost:9000
  * AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
  *
  * All tests are skipped when these vars are absent.
  */
class S3IntegrationTest
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll {

  private val endpoint = sys.env.get("AWS_ENDPOINT_URL")
  private val accessKey = sys.env.getOrElse("AWS_ACCESS_KEY_ID", "")
  private val secretKey = sys.env.getOrElse("AWS_SECRET_ACCESS_KEY", "")
  private val testBucket = "parqueteer-test"
  private val repo = new ParquetRepository()

  private lazy val s3Client: S3Client = S3Client
    .builder()
    .endpointOverride(URI.create(endpoint.get))
    .region(Region.US_EAST_1)
    .forcePathStyle(true)
    .credentialsProvider(
      StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKey, secretKey)
      )
    )
    .build()

  private val sampleData: List[Map[String, CellValue]] = List(
    Map(
      "id" -> CellValue.I64(1L),
      "name" -> CellValue.Str("Alice"),
      "score" -> CellValue.F64(95.5)
    ),
    Map(
      "id" -> CellValue.I64(2L),
      "name" -> CellValue.Str("Bob"),
      "score" -> CellValue.F64(87.3)
    ),
    Map(
      "id" -> CellValue.I64(3L),
      "name" -> CellValue.Str("Charlie"),
      "score" -> CellValue.F64(92.1)
    )
  )

  override def beforeAll(): Unit = {
    if (endpoint.isDefined) {
      Try(
        s3Client.createBucket(
          CreateBucketRequest.builder().bucket(testBucket).build()
        )
      )
    }
  }

  override def afterAll(): Unit = {
    if (endpoint.isDefined) {
      Try {
        val objects = s3Client
          .listObjectsV2(
            ListObjectsV2Request.builder().bucket(testBucket).build()
          )
          .contents()
          .asScala
        objects.foreach { obj =>
          s3Client.deleteObject(
            DeleteObjectRequest
              .builder()
              .bucket(testBucket)
              .key(obj.key())
              .build()
          )
        }
        s3Client.deleteBucket(
          DeleteBucketRequest.builder().bucket(testBucket).build()
        )
      }
    }
  }

  private def assumeS3Available(): Unit =
    assume(
      endpoint.isDefined,
      "Skipped: AWS_ENDPOINT_URL not set — start RustFS and set env vars to run S3 integration tests"
    )

  // ── Write + Read roundtrip ───────────────────────────────────────────────

  private def assertSuccess[A](result: scala.util.Try[A], label: String): A =
    result.fold(
      e => fail(s"$label failed: ${e.getClass.getName}: ${e.getMessage}", e),
      identity
    )

  "ParquetRepository" should "write a parquet file to S3 and read it back" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/test.parquet")
    assertSuccess(repo.writeContent(loc, sampleData, None), "write")

    val content =
      assertSuccess(repo.readContent(ParquetFile(loc), ReadConfig()), "read")
    content.rows should have length 3
    content.totalRows shouldBe 3L
  }

  it should "read correct column values from S3" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/cols.parquet")
    assertSuccess(repo.writeContent(loc, sampleData, None), "write")

    val content =
      assertSuccess(repo.readContent(ParquetFile(loc), ReadConfig()), "read")
    content.rows.head.keys should contain allOf ("id", "name", "score")
    val names = content.rows.flatMap(_.get("name"))
    names should contain(CellValue.Str("Alice"))
  }

  // ── Column projection ───────────────────────────────────────────────────

  it should "project specific columns when reading from S3" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/proj.parquet")
    assertSuccess(repo.writeContent(loc, sampleData, None), "write")

    val config = ReadConfig(columns = Some(List("id", "name")))
    val content =
      assertSuccess(repo.readContent(ParquetFile(loc), config), "read")
    content.rows.head.keys should contain allOf ("id", "name")
    content.rows.head.keys should not contain "score"
  }

  // ── Schema read ─────────────────────────────────────────────────────────

  it should "read schema from an S3 parquet file" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/schema.parquet")
    assertSuccess(repo.writeContent(loc, sampleData, None), "write")

    val schema = assertSuccess(repo.readSchema(ParquetFile(loc)), "readSchema")
    schema.columns.map(_.name) should contain allOf ("id", "name", "score")
    schema.totalRowCount shouldBe 3L
  }

  // ── Metadata / info ─────────────────────────────────────────────────────

  it should "read metadata with non-zero file size from S3" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/meta.parquet")
    assertSuccess(repo.writeContent(loc, sampleData, None), "write")

    val meta =
      assertSuccess(repo.readMetadata(ParquetFile(loc)), "readMetadata")
    meta.fileSize should be > 0L
  }

  // ── Validate ────────────────────────────────────────────────────────────

  it should "validate a well-formed parquet file on S3 with no issues" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/validate.parquet")
    assertSuccess(repo.writeContent(loc, sampleData, None), "write")

    val issues =
      assertSuccess(repo.validateFile(ParquetFile(loc)), "validateFile")
    issues shouldBe empty
  }

  // ── Row limit ───────────────────────────────────────────────────────────

  it should "respect maxRows when reading from S3" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/limit.parquet")
    assertSuccess(repo.writeContent(loc, sampleData, None), "write")

    val content = assertSuccess(
      repo.readContent(ParquetFile(loc), ReadConfig(maxRows = Some(2L))),
      "read"
    )
    content.rows should have length 2
    content.isPartial shouldBe true
  }
}
