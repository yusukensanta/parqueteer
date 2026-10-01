package io.github.yusukensanta.parqueteer.core.models

case class ColumnStats(
    name: String,
    dataType: String,
    nullCount: Long,
    minValue: Option[String],
    maxValue: Option[String]
)

case class FileStats(
    columns: List[ColumnStats],
    totalRows: Long,
    rowGroupCount: Long
)

case class ValidationResult(
    isValid: Boolean,
    issues: List[String]
)
