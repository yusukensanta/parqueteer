package io.github.yusukensanta.parqueteer.core.util

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Names threads `<prefix>-0`, `<prefix>-1`, ... and marks them daemon, so a
 * worker pool that isn't shut down (e.g. on an uncaught error) can never keep
 * the CLI's JVM alive after main returns.
 */
final class DaemonThreadFactory(prefix: String) extends ThreadFactory {
  private val counter = new AtomicInteger(0)

  override def newThread(r: Runnable): Thread = {
    val t = new Thread(r, s"$prefix-${counter.getAndIncrement()}")
    t.setDaemon(true)
    t
  }
}
