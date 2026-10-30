package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.FieldSummary
import io.circe.{parser, HCursor}

/**
 * An expected schema kept in version control, checked by
 * `validate --expect-schema`. The accepted format is exactly what
 * `parqueteer schema --format json` prints, so a contract is produced with
 * `parqueteer schema data.parquet --format json > contract.json`. Only each
 * column's `name`, `dataType` and `optional` are compared; other keys
 * (encodings, row counts) are ignored because they describe one file, not
 * the schema.
 */
object SchemaContract {

  def parse(json: String): Either[String, List[FieldSummary]] =
    for {
      doc <- parser.parse(json).left.map(e => s"not valid JSON: ${e.message}")
      columns <- doc.hcursor
        .downField("columns")
        .as[List[HCursor]]
        .left
        .map(_ =>
          """expected an object with a "columns" array (the output of `parqueteer schema --format json`)"""
        )
        .flatMap(cs => columnsOf(cs))
      _ <- if columns.isEmpty then Left(""""columns" must not be empty""") else Right(())
    } yield columns

  private def columnsOf(cursors: List[HCursor]): Either[String, List[FieldSummary]] =
    cursors.zipWithIndex.foldLeft[Either[String, List[FieldSummary]]](Right(Nil)) {
      case (acc, (c, i)) =>
        acc.flatMap { done =>
          (for {
            name     <- c.downField("name").as[String]
            dataType <- c.downField("dataType").as[String]
            optional <- c.downField("optional").as[Boolean]
          } yield FieldSummary(name, dataType, optional)).left
            .map(_ =>
              s"""column #${i + 1} needs string "name", string "dataType" and boolean "optional""""
            )
            .map(done :+ _)
        }
    }
}
