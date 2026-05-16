# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.4.1] - 2026-05-16

### Added
- Shell completion scripts for bash, zsh, and fish via `parqueteer completions <shell>` (#45)
  - Covers all commands, subcommands, `--format`, `--compression`, and nested subcommands
  - Install: `eval "$(parqueteer completions bash)"` or persist to shell completion dir

## [0.4.0] - 2026-05-16

### Added
- `parqueteer schema diff <file1> <file2>` command to compare Parquet schemas (#14)
  - Reports added (+), removed (-), changed (~), and unchanged (=) columns
  - Supports `--format table|json` output
  - Exit code 0 = identical schemas, 1 = schemas differ (CI-scriptable)

## [0.3.0] - 2026-05-16

### Added
- Advanced filter operators in `--filter` expressions (#15)
  - `BETWEEN`: `age BETWEEN 18 AND 65`
  - `IN`: `status IN ('active', 'pending')`
  - `IS NULL` / `IS NOT NULL`: `deleted_at IS NULL`
  - Nested column paths: `address.city = 'Tokyo'`

## [0.2.2] - 2026-05-16

### Added
- `--quiet` / `-q` global flag to suppress non-error output (#43)
- `--color <auto|always|never>` global flag for color output control (#44)
- `markdown` and `ndjson` output formats for `read` and `info` commands (#16)
- Environment variable support: `PARQUETEER_FORMAT`, `PARQUETEER_COLOR`, `PARQUETEER_VERBOSE`, `PARQUETEER_MAX_ROWS` (#46, #17)
- Layered config precedence: CLI flags > env vars > config file > defaults
- Stdin read support: use `-` as input path in `write` and `convert` commands
- Sealed `ParqueteerError` ADT with typed error variants and per-error exit codes (#7)
  - `InvalidFormat`, `FilterParseError`, `IOError`, `UnsupportedOperation`

### Fixed
- Filter parser silently returned wrong results when numeric comparison produced type mismatch
- JSON Long values lost precision for integers > 2^53 (now encoded as JSON integers not floats)
- Filter parse errors now propagate as typed errors instead of falling back to no-filter silently

## [0.2.1] - 2026-05-14

### Fixed
- Removed unsupported `tsv` from `--input-format` validator in `write` command

### Changed
- Extracted `SizeParser` utility, removing duplicate `parseSize` logic
- Extracted `DefaultRowGroupSize` constant
- Centralized `Any → Json` encoding in `JsonEncoder` utility
- Promoted `extractColumns` to `OutputFormatter` trait, removing duplicates

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
- `Using.resource` for improved resource management

### Fixed
- Long.toInt truncation in FilterParser that caused silent wrong results for values > Int.MaxValue
- WriteCommand constructor argument swap in ArgumentParser (output path was silently lost)
- Duplicate `-v` flag in validate subcommand
- Removed unnecessary println calls to stdout in repository and cloud layers
- CSV/JSON → Parquet conversion now works correctly
- Build configuration issues (dead javaOptions, unnecessary Scala 2 language flags)

### Changed
- **BREAKING**: `write` command now takes `<INPUT> <OUTPUT>` positional args instead of `<OUTPUT> --input <INPUT>`
- Sealed traits converted to Scala 3 enums (`OutputFormat`, `CompressionType`, `CredentialError`)
- Implicit vals migrated to Scala 3 `given` instances

### Removed
- Dead code (`convertValue` in FilterParser, duplicate `PerformanceConfig`/`LoggingConfig`)
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

[0.4.1]: https://github.com/yusukensanta/parqueteer/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/yusukensanta/parqueteer/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/yusukensanta/parqueteer/compare/v0.2.2...v0.3.0
[0.2.2]: https://github.com/yusukensanta/parqueteer/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/yusukensanta/parqueteer/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/yusukensanta/parqueteer/compare/v0.1.5...v0.2.0
[0.1.5]: https://github.com/yusukensanta/parqueteer/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/yusukensanta/parqueteer/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/yusukensanta/parqueteer/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/yusukensanta/parqueteer/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/yusukensanta/parqueteer/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/yusukensanta/parqueteer/releases/tag/v0.1.0
