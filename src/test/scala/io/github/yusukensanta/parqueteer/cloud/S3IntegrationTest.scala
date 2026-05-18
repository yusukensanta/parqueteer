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

  private val sampleData: List[Map[String, Any]] = List(
    Map("id" -> 1L, "name" -> "Alice", "score" -> 95.5),
    Map("id" -> 2L, "name" -> "Bob", "score" -> 87.3),
    Map("id" -> 3L, "name" -> "Charlie", "score" -> 92.1)
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

  "ParquetRepository" should "write a parquet file to S3 and read it back" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/test.parquet")
    val write = repo.writeContent(loc, sampleData, None)
    write.isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    result.get.rows should have length 3
    result.get.totalRows shouldBe 3L
  }

  it should "read correct column values from S3" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/cols.parquet")
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.readContent(ParquetFile(loc), ReadConfig())
    result.isSuccess shouldBe true
    result.get.rows.head.keys should contain allOf ("id", "name", "score")
    val names = result.get.rows.flatMap(_.get("name"))
    names should contain("Alice")
  }

  // ── Column projection ───────────────────────────────────────────────────

  it should "project specific columns when reading from S3" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/proj.parquet")
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val config = ReadConfig(columns = Some(List("id", "name")))
    val result = repo.readContent(ParquetFile(loc), config)
    result.isSuccess shouldBe true
    result.get.rows.head.keys should contain allOf ("id", "name")
    result.get.rows.head.keys should not contain "score"
  }

  // ── Schema read ─────────────────────────────────────────────────────────

  it should "read schema from an S3 parquet file" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/schema.parquet")
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.readSchema(ParquetFile(loc))
    result.isSuccess shouldBe true
    result.get.columns.map(_.name) should contain allOf ("id", "name", "score")
    result.get.totalRowCount shouldBe 3L
  }

  // ── Metadata / info ─────────────────────────────────────────────────────

  it should "read metadata with non-zero file size from S3" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/meta.parquet")
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.readMetadata(ParquetFile(loc))
    result.isSuccess shouldBe true
    result.get.fileSize should be > 0L
  }

  // ── Validate ────────────────────────────────────────────────────────────

  it should "validate a well-formed parquet file on S3 with no issues" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/validate.parquet")
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result = repo.validateFile(ParquetFile(loc))
    result.isSuccess shouldBe true
    result.get shouldBe empty
  }

  // ── Row limit ───────────────────────────────────────────────────────────

  it should "respect maxRows when reading from S3" taggedAs S3IntegrationTest in {
    assumeS3Available()

    val loc = S3Location(testBucket, "roundtrip/limit.parquet")
    repo.writeContent(loc, sampleData, None).isSuccess shouldBe true

    val result =
      repo.readContent(ParquetFile(loc), ReadConfig(maxRows = Some(2L)))
    result.isSuccess shouldBe true
    result.get.rows should have length 2
    result.get.isPartial shouldBe true
  }
}
