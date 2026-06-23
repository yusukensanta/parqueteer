package io.github.yusukensanta.parqueteer.core.repositories

import io.github.yusukensanta.parqueteer.core.models.{CellValue, ReadConfig}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.metadata.BlockMetaData
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.ParquetReadOptions
import org.apache.parquet.schema.MessageType
import org.slf4j.LoggerFactory
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutorService, Future}
import java.util.concurrent.{Executors, TimeoutException}
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*
import scala.util.Using

private[repositories] object ParallelRowGroupReader {

  private val logger = LoggerFactory.getLogger(getClass)

  private val sharedPool: java.util.concurrent.ExecutorService = {
    val factory = new java.util.concurrent.ThreadFactory {
      private val counter = new java.util.concurrent.atomic.AtomicInteger(0)
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, s"parqueteer-parallel-read-${counter.getAndIncrement()}")
        t.setDaemon(true)
        t
      }
    }
    Executors.newCachedThreadPool(factory)
  }

  def read(
      path: HadoopPath,
      conf: Configuration,
      config: ReadConfig,
      fileSchema: MessageType,
      blocks: List[BlockMetaData]
  ): List[Map[String, CellValue]] = {
    val selectedBlocks = config.maxRows match {
      case None => blocks
      case Some(limit) =>
        var cumulative = 0L
        blocks.takeWhile { block =>
          if cumulative >= limit then false
          else { cumulative += block.getRowCount; true }
        }
    }

    val requestedSchema = config.columns match {
      case Some(cols) if cols.nonEmpty =>
        ParquetSchemaBuilder.projectSchema(fileSchema, cols)
      case _ => fileSchema
    }

    implicit val ec: ExecutionContextExecutorService =
      ExecutionContext.fromExecutorService(sharedPool)
    val requestedNames =
      requestedSchema.getColumns.asScala.map(_.getPath.mkString(".")).toSet
    val nullRowsAllotted = new AtomicLong(
      config.maxRows.getOrElse(Long.MaxValue)
    )
    val futures = selectedBlocks.map { block =>
      Future {
        readBlock(
          path,
          conf,
          block,
          fileSchema,
          requestedSchema,
          requestedNames,
          nullRowsAllotted
        )
      }
    }
    val allRows =
      try Await.result(Future.sequence(futures), config.readTimeout).flatten
      catch {
        case _: TimeoutException =>
          throw new RuntimeException(
            s"parallel read timed out after ${config.readTimeout} — " +
              "retry with --parallelism 1, or check network connectivity"
          )
      }
    io.github.yusukensanta.parqueteer.core.util.RowLimiter
      .limitList(allRows, config.maxRows)
  }

  private def readBlock(
      path: HadoopPath,
      conf: Configuration,
      block: BlockMetaData,
      fileSchema: MessageType,
      requestedSchema: MessageType,
      requestedNames: Set[String],
      nullRowsAllotted: AtomicLong
  ): List[Map[String, CellValue]] = {
    val colChunks = block.getColumns.asScala.toList
    val relevantChunks =
      if requestedNames.isEmpty then colChunks
      else colChunks.filter(c => requestedNames.contains(c.getPath.toDotString))
    if relevantChunks.isEmpty then {
      logger.warn(
        s"Row group (${block.getRowCount} rows) has no chunks matching " +
          s"requested columns $requestedNames — fabricating null rows for schema evolution."
      )
      val nullRow = requestedSchema.getColumns.asScala
        .map(col => col.getPath.mkString(".") -> CellValue.Null)
        .toMap
      val rowCount = block.getRowCount
      if rowCount > Int.MaxValue then
        throw new IllegalStateException(
          s"Row group has $rowCount rows — exceeds Int.MaxValue; use sequential read (--parallelism 1)"
        )
      val prev =
        nullRowsAllotted.getAndUpdate(r => r - rowCount.min(r))
      val rowsToFabricate = rowCount.min(prev).toInt
      List.fill(rowsToFabricate)(nullRow)
    } else {
      val rangeStart = relevantChunks.map(_.getStartingPos).min
      val rangeEnd =
        relevantChunks.map(c => c.getStartingPos + c.getTotalSize).max
      val readOptions = ParquetReadOptions
        .builder()
        .withRange(rangeStart, rangeEnd)
        .build()
      Using.resource(
        ParquetFileReader.open(
          HadoopInputFile.fromPath(path, conf),
          readOptions
        )
      ) { reader =>
        val pageStore = reader.readNextRowGroup()
        if pageStore == null then List.empty[Map[String, CellValue]]
        else
          ParquetRecordDecoder.decodePageStore(
            pageStore,
            fileSchema,
            requestedSchema
          )
      }
    }
  }
}
