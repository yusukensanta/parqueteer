# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- **BREAKING**: `write` command now takes `<INPUT> <OUTPUT>` positional args instead of `<OUTPUT> --input <INPUT>`, matching UNIX conventions and `convert` command (closes #41)

## [0.2.0] - 2026-05-14

### Added
- Comprehensive test suite with 84 tests across multiple modules:
  - FilterParser: 18 tests
  - TableFormatter: 21 tests
  - JSONFormatter: 12 tests
  - CSVFormatter: 9 tests
  - ParquetService: 24 tests
- CSV/JSON to Parquet conversion support in `convertFile`
- RFC 4180 quoted-field support in CSV reader
- Version string auto-generation via sbt-buildinfo from git tags
- Using.resource for improved resource management

### Fixed
- Long.toInt truncation in FilterParser that caused silent wrong results for values > Int.MaxValue
- WriteCommand constructor argument swap in ArgumentParser (output path was silently lost)
- Duplicate `-v` flag in validate subcommand
- Removed unnecessary println calls to stdout in repository and cloud layers to prevent output corruption when piping JSON/CSV
- CSV/JSON → Parquet conversion now works correctly
- Build configuration issues (dead javaOptions, unnecessary Scala 2 language flags)

### Changed
- Sealed traits converted to Scala 3 enums (OutputFormat, CompressionType, CredentialError, CredentialResolutionStrategy)
- Implicit vals migrated to Scala 3 given instances
- Improved code quality with functional formatBytes, AwsSessionCredentials direct pattern matching
- Minor optimizations including inline drawHeaderRow

### Removed
- Dead code (convertValue in FilterParser, duplicate PerformanceConfig/LoggingConfig from ParquetFile)
- Scala 2 `-language:higherKinds` flag

## [0.1.5] - 2025-10-25

### Added
- Homebrew formula automation improvements

## [0.1.4] - 2025-10-25

### Fixed
- Maven Central publish now only pushes when tag is committed
- Homebrew formula CI workflow

## [0.1.3] - 2025-10-25

### Added
- CODEOWNERS for Homebrew Formula

### Changed
- Further dependency minimization efforts

### Fixed
- CI workflow issues

## [0.1.2] - 2025-10-25

### Changed
- Reduced dependency footprint (smaller assembly JAR)

## [0.1.1] - 2025-10-24

### Added
- sbt support in release workflow

### Fixed
- Version management issues

## [0.1.0] - 2025-10-24

### Added
- Initial project structure and core functionality
- Hierarchical help system for CLI commands
- Assembly build optimization for executable JAR
- GitHub release workflow automation
- Base implementation of Parquet file operations
- Command-line interface with subcommands

[Unreleased]: https://github.com/yusukensanta/parqueteer/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/yusukensanta/parqueteer/compare/v0.1.5...v0.2.0
[0.1.5]: https://github.com/yusukensanta/parqueteer/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/yusukensanta/parqueteer/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/yusukensanta/parqueteer/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/yusukensanta/parqueteer/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/yusukensanta/parqueteer/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/yusukensanta/parqueteer/releases/tag/v0.1.0
