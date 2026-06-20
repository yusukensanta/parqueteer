package io.github.yusukensanta.parqueteer.core.repositories

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ParquetRepositoryTraitTest extends AnyFlatSpec with Matchers {

  "ParquetRepository.cacheStats" should "be callable through the trait interface" in {
    val repo: ParquetRepository = new HadoopParquetRepository()
    val stats                   = repo.cacheStats()
    stats shouldBe a[ParquetRepository.CacheStats]
    stats.footerHits shouldBe 0L
    stats.footerMisses shouldBe 0L
    stats.configHits shouldBe 0L
    stats.configMisses shouldBe 0L
  }

  it should "return CacheStats with all zeros as the default implementation" in {
    // A no-op stub that inherits the default cacheStats() from the trait
    val stub = new ParquetRepository {
      import scala.util.Try
      import io.github.yusukensanta.parqueteer.core.models.*
      def readContent(f: ParquetFile, c: ReadConfig) = Try(???)
      def streamContent(f: ParquetFile, c: ReadConfig)(
          p: Map[String, CellValue] => Unit
      ) =
        Try(???)
      def readSchema(f: ParquetFile)   = Try(???)
      def readFileInfo(f: ParquetFile) = Try(???)
      def readMetadata(f: ParquetFile) = Try(???)
      def writeContent(
          l: StorageLocation,
          d: List[Map[String, CellValue]],
          s: Option[ParquetSchema],
          c: WriteConfig
      ) =
        Try(???)
      def writeContentStream(
          l: StorageLocation,
          s: ParquetSchema,
          c: WriteConfig
      )(
          feed: (Map[String, CellValue] => Unit) => Unit
      ) = Try(???)
      def validateFile(f: ParquetFile, deep: Boolean) = Try(???)
      def readSchemaFields(f: ParquetFile)            = Try(???)
      def deleteFile(l: StorageLocation)              = Try(???)
      def readStats(f: ParquetFile)                   = Try(???)
    }
    val stats = stub.cacheStats()
    stats shouldBe ParquetRepository.CacheStats(0L, 0L, 0L, 0L)
  }
}
