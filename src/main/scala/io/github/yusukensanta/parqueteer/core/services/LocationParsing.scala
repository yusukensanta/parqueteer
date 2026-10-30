package io.github.yusukensanta.parqueteer.core.services

import io.github.yusukensanta.parqueteer.core.models.*
import io.github.yusukensanta.parqueteer.core.util.Eithers

/** Path-string to [[StorageLocation]] parsing shared by the service collaborators. */
private[services] object LocationParsing {

  def parseLocation(path: String): Either[ParqueteerError, StorageLocation] =
    StorageLocationParser
      .parse(path)
      .left
      .map(msg => ParqueteerError.InvalidLocation(path, msg))

  /**
   * Lift a list of paths into a single Either of parsed locations,
   * short-circuiting on the first malformed path.
   */
  def parseLocations(paths: List[String]): Either[ParqueteerError, Vector[StorageLocation]] =
    Eithers.traverse(paths)(parseLocation)
}
