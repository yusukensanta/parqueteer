package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.{
  ColumnChange,
  ColumnInfo,
  FieldSummary,
  ParqueteerError,
  SchemaDiff,
  SchemaMode
}
import scala.collection.immutable.VectorMap

/**
 * Pure schema logic for multi-file operations: reconciling per-file schemas
 * under a SchemaMode, and diffing two schemas. Kept free of I/O so it can be
 * tested without a repository.
 */
private[services] object SchemaReconciler {

  /**
   * Combine per-file schemas under the chosen strategy:
   *   - Strict: every input must match the first file's schema exactly.
   *   - Union: collect the union of fields, surfacing per-column type
   *     conflicts as a single SchemaMismatch error. Union-merged fields are
   *     marked optional because not every file is guaranteed to supply them.
   * `paths(i)` names the file `schemas(i)` was read from, for error messages.
   */
  def reconcile(
      schemas: Vector[List[FieldSummary]],
      paths: List[String],
      mode: SchemaMode
  ): Either[ParqueteerError, List[FieldSummary]] = mode match {
    case SchemaMode.Strict => strict(schemas, paths)
    case SchemaMode.Union  => union(schemas, paths)
  }

  private def strict(
      schemas: Vector[List[FieldSummary]],
      paths: List[String]
  ): Either[ParqueteerError, List[FieldSummary]] =
    schemas.headOption match {
      case None => Right(Nil)
      case Some(first) =>
        val expected = first.toSet
        // .view keeps this lazy so the scan stops at the first mismatching file.
        schemas.view.zipWithIndex
          .map { case (s, i) => (i, s.toSet) }
          .collectFirst { case (i, actual) if actual != expected => (i, actual) } match {
          case Some((i, actual)) =>
            Left(
              ParqueteerError.SchemaMismatch(
                paths(i),
                s"${describeMismatch(expected, actual)}. Use --schema-mode union to allow schema differences."
              )
            )
          case None => Right(first)
        }
    }

  // Column name -> (dataType, required in every input so far). VectorMap keeps
  // first-seen column order, and `updated` on an existing key keeps its slot.
  private type Seen = VectorMap[String, (String, Boolean)]

  private def union(
      schemas: Vector[List[FieldSummary]],
      paths: List[String]
  ): Either[ParqueteerError, List[FieldSummary]] =
    schemas.zipWithIndex
      .foldLeft[Either[ParqueteerError, Seen]](Right(VectorMap.empty)) {
        case (acc, (fields, fileIdx)) =>
          acc.flatMap { seen =>
            val path = paths(fileIdx)
            for {
              _ <- requireUniqueNames(fields, path)
              _ <- requireCompatibleTypes(fields, seen, path)
            } yield absorb(seen, fields, isFirstFile = fileIdx == 0)
          }
      }
      .map(_.map { case (name, (dataType, required)) =>
        FieldSummary(name, dataType, isOptional = !required)
      }.toList)

  private def requireUniqueNames(
      fields: List[FieldSummary],
      path: String
  ): Either[ParqueteerError, Unit] = {
    val duplicates =
      fields.groupBy(_.name).collect { case (n, fs) if fs.size > 1 => n }.toList.sorted
    if duplicates.isEmpty then Right(())
    else
      Left(
        ParqueteerError.SchemaMismatch(
          path,
          s"duplicate column names: ${duplicates.mkString(", ")}. " +
            "Parquet files with duplicate column names cannot be merged."
        )
      )
  }

  private def requireCompatibleTypes(
      fields: List[FieldSummary],
      seen: Seen,
      path: String
  ): Either[ParqueteerError, Unit] = {
    val conflicts = fields.flatMap { f =>
      seen.get(f.name).collect {
        case (dt, _) if dt != f.dataType => s"'${f.name}' ($dt vs ${f.dataType})"
      }
    }
    if conflicts.isEmpty then Right(())
    else
      Left(
        ParqueteerError.SchemaMismatch(
          path,
          s"Type conflicts in union merge: ${conflicts.mkString(", ")}. " +
            "Cannot union-merge columns with incompatible types."
        )
      )
  }

  /** Folds one file's fields into `seen`, downgrading requiredness as needed. */
  private def absorb(seen: Seen, fields: List[FieldSummary], isFirstFile: Boolean): Seen = {
    val present = fields.iterator.map(_.name).toSet
    // A column seen earlier but absent from this file must become optional.
    val withAbsent: Seen = seen.map { case (name, (dt, required)) =>
      name -> (dt, required && present(name))
    }
    fields.foldLeft(withAbsent) { (acc, f) =>
      acc.get(f.name) match {
        case Some((dt, required)) => acc.updated(f.name, (dt, required && !f.isOptional))
        // A column first seen in a later file was absent from earlier files,
        // so it is required only if it comes from the first file.
        case None => acc.updated(f.name, (f.dataType, isFirstFile && !f.isOptional))
      }
    }
  }

  /** Human-readable summary of how `actual` differs from `expected`. */
  def describeMismatch(expected: Set[FieldSummary], actual: Set[FieldSummary]): String = {
    val missing                      = expected -- actual
    val extra                        = actual -- expected
    val missingNames                 = missing.map(_.name)
    val extraNames                   = extra.map(_.name)
    val changedNames                 = missingNames.intersect(extraNames)
    val onlyMissingNames             = missingNames -- changedNames
    val onlyExtraNames               = extraNames -- changedNames
    def fmt(f: FieldSummary): String = if f.isOptional then s"${f.dataType}?" else f.dataType
    val changedDetails = changedNames.toList.sorted.flatMap { name =>
      for {
        from <- missing.find(_.name == name)
        to   <- extra.find(_.name == name)
      } yield s"$name (${fmt(from)} → ${fmt(to)})"
    }
    List(
      Option.when(changedNames.nonEmpty)(
        s"type/nullability changed: ${changedDetails.mkString(", ")}"
      ),
      Option.when(onlyMissingNames.nonEmpty)(
        s"missing: ${onlyMissingNames.toList.sorted.mkString(", ")}"
      ),
      Option.when(onlyExtraNames.nonEmpty)(s"extra: ${onlyExtraNames.toList.sorted.mkString(", ")}")
    ).flatten.mkString("; ")
  }

  /** Column-level diff of `cols2` against `cols1` (matched by name). */
  def diff(cols1: List[ColumnInfo], cols2: List[ColumnInfo]): SchemaDiff = {
    val byName1 = cols1.map(c => c.name -> c).toMap
    val byName2 = cols2.map(c => c.name -> c).toMap
    def sameShape(a: ColumnInfo, b: ColumnInfo): Boolean =
      a.dataType == b.dataType && a.isOptional == b.isOptional

    val added   = cols2.filterNot(c => byName1.contains(c.name))
    val removed = cols1.filterNot(c => byName2.contains(c.name))
    val changed = cols1.flatMap { c1 =>
      byName2.get(c1.name).filterNot(sameShape(c1, _)).map { c2 =>
        ColumnChange(c1.name, c1.dataType, c2.dataType, c1.isOptional, c2.isOptional)
      }
    }
    val unchanged = cols1.collect {
      case c if byName2.get(c.name).exists(sameShape(c, _)) => c.name
    }
    SchemaDiff(added, removed, changed, unchanged)
  }
}
