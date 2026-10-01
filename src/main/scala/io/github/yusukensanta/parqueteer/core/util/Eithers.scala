package io.github.yusukensanta.parqueteer.core.util

/** Small Either combinators (the project doesn't depend on cats). */
object Eithers {

  /**
   * Applies `f` to each element in order, collecting the results, and
   * short-circuits on the first Left (later elements are not evaluated).
   */
  def traverse[A, E, B](as: IterableOnce[A])(f: A => Either[E, B]): Either[E, Vector[B]] = {
    val builder = Vector.newBuilder[B]
    val it      = as.iterator
    var failure = Option.empty[E]
    while failure.isEmpty && it.hasNext do
      f(it.next()) match {
        case Right(b) => builder += b
        case Left(e)  => failure = Some(e)
      }
    failure.toLeft(builder.result())
  }
}
