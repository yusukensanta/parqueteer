# Contributing to parqueteer

Thank you for your interest in contributing to parqueteer! This guide will help you get started.

## Quick Start

### 1. Fork and Clone

```bash
git clone https://github.com/yusukensanta/parqueteer.git
cd parqueteer
```

### 2. Setup Development Environment

**Option A: Quick Setup (Recommended for first-time contributors)**

```bash
make setup-dev
```

This works immediately if you have Java and sbt installed.

**Option B: With Version Management (Recommended for regular contributors)**

```bash
# Install mise (one-time)
curl https://mise.run | sh

# Setup environment
make setup-dev    # Installs exact versions from .tool-versions
```

### 3. Build and Test

```bash
make compile      # Compile the project
make test         # Run tests
make assembly     # Create distributable JAR
```

**See all available commands:**
```bash
make help
```

### 4. Git Hooks (Automatic Quality Checks)

The `make setup-dev` command automatically configures git hooks that help maintain code quality:

**Pre-commit Hook** - Runs comprehensive checks before each commit (only when Scala/sbt files are modified):
- ✅ **Smart Detection**: Automatically skips checks if only docs or config files changed
- ✅ **Formatting**: Checks code formatting with scalafmt
- ✅ **Compilation**: Ensures code compiles successfully
- ✅ **Tests**: Runs all tests to ensure they pass
- ✅ **Cleanup**: Removes temporary build artifacts after compile

**To test pre-commit checks manually:**
```bash
make pre-commit    # Run all checks (format, compile, test)
make fmt           # Auto-fix formatting issues only
```

**What happens during pre-commit:**
1. **Detection**: Checks if any `.scala` or `.sbt` files are staged
   - If no Scala/sbt files modified → Skips all checks ✅ Fast commit!
   - If Scala/sbt files modified → Runs full checks below
2. Code formatting check (`sbt scalafmtCheckAll`)
3. Compilation check (`sbt compile`)
4. Cleanup of build artifacts (`target/streams`)
5. Test execution (`sbt test`)

Static analysis (`sbt "scalafix --check"`, rules in `.scalafix.conf`) is not part of the hook; it runs in CI and in `make lint`.

**To bypass the hook** (not recommended, only for emergencies):
```bash
git commit --no-verify
```

## Development Workflow

### Making Changes

1. **Create a branch**
   ```bash
   git checkout -b feature/my-feature
   ```

2. **Make your changes**
   - Write code
   - Add tests
   - Update documentation

3. **Test your changes**
   ```bash
   make check    # Runs linting and tests
   ```

4. **Commit your changes**
   ```bash
   git add .
   git commit -m "feat: add awesome feature"
   ```

   We follow [Conventional Commits](https://www.conventionalcommits.org/):
   - `feat:` - New feature
   - `fix:` - Bug fix
   - `docs:` - Documentation changes
   - `test:` - Adding tests
   - `refactor:` - Code refactoring
   - `chore:` - Maintenance tasks

5. **Push and create Pull Request**
   ```bash
   git push origin feature/my-feature
   ```

### Running the Application Locally

After building:

```bash
# Using staged distribution (recommended for testing)
make stage
./target/universal/stage/bin/parqueteer --help

# Or run directly with sbt
make run ARGS="--help"
```

## Code Quality

### Formatting

We use scalafmt for consistent code formatting and scalafix for static analysis:

```bash
make fmt         # Format all code
make lint        # Check formatting and run scalafix (the same checks CI runs)
```

Sources use Scala 3 indentation syntax, and scalafmt enforces it (see `rewrite.scala3` in `.scalafmt.conf`): optional braces are removed and indented regions of 30 lines or more get an `end` marker. `make fmt` converts brace-style code for you.

### Testing

```bash
make test        # Run all tests
make check       # Run linting + tests
make coverage    # Run tests with a coverage report
```

Tests that need a cloud account or a local S3 endpoint are reported as canceled when that environment is not configured. CI enforces minimum coverage of 75% of statements and 68% of branches (`coverageMinimum*` in `build.sbt`).

### Building

```bash
make compile     # Compile only
make assembly    # Create fat JAR
make package     # Create distribution package
```

## Version Management

### Using mise (Optional but Recommended)

If you're a regular contributor, we recommend using [mise](https://mise.jdx.dev) for version management:

**Benefits:**
- ✅ Ensures you use the exact same versions as maintainers
- ✅ Automatic version sync
- ✅ No "works on my machine" issues

**Setup:**
```bash
curl https://mise.run | sh
mise install
```

`.tool-versions` selects the latest Amazon Corretto 21, the same JDK
distribution CI uses.
Run `mise upgrade java` after each quarterly JDK security release.

The Makefile automatically detects mise and syncs versions from `.tool-versions`.

### Without mise

You can contribute without mise! Just ensure you have:
- Java 21 (or 17+)
- sbt 1.11+

The project will build fine with any recent versions.

## Project Structure

```
parqueteer/
├── src/main/scala/io/github/yusukensanta/parqueteer/
│   ├── cli/
│   │   ├── CliApp.scala               # Entry point: parses args, loads config, builds the service, runs the command
│   │   ├── ArgumentParser.scala       # scopt definitions: the one place flags and subcommands are declared
│   │   ├── Commands.scala             # Command ADT (one case class per command)
│   │   ├── CommandExecutor.scala      # Routes a parsed Command to its handler, expanding globs first
│   │   ├── ReadCommands.scala         # Handlers for `read` (single file and multi-file/glob)
│   │   ├── WriteCommands.scala        # Handlers for write, convert, merge
│   │   ├── InspectCommands.scala      # Handlers for info, validate, schema, schema diff, stats, count
│   │   ├── CommandSupport.scala       # Error reporting and output-path checks shared by the handlers
│   │   ├── CliSpec.scala              # Commands and options as data, read from the parser
│   │   ├── HelpFormatter.scala        # --help text (OPTIONS sections rendered from CliSpec)
│   │   ├── ShellCompletions.scala     # bash/zsh/fish completion scripts (rendered from CliSpec)
│   │   ├── CliOutputFormatter.scala   # Presentation layer (table/json/csv/ltsv)
│   │   ├── MetadataFormatter.scala    # `metadata` report as a table or JSON
│   │   ├── MultiFileReport.scala      # One report per matched file for multi-file metadata commands
│   │   ├── StreamingOutput.scala      # Streaming row output and safe local output files
│   │   ├── DryRunReport.scala         # "Dry run: would …" summaries
│   │   ├── ConfigCommandRenderer.scala # `parqueteer config` output rendering
│   │   └── Terminal.scala             # Terminal capability detection
│   ├── config/
│   │   ├── Configuration.scala       # Config case classes with Circe given instances
│   │   └── EnvConfig.scala           # Environment variable resolution
│   └── core/                         # Never imports cli or config
│       ├── models/                   # Domain model (CellValue, ParquetFile, ParqueteerError, …)
│       ├── services/
│       │   ├── ParquetService.scala     # Business logic, Either-based API (facade)
│       │   ├── MergeOperations.scala    # merge / parquet→parquet convert
│       │   ├── RawToParquetWriter.scala # JSON/NDJSON/CSV/LTSV → parquet (buffered or streaming)
│       │   ├── StreamWriteSupport.scala # Shared stream-write helpers (cleanup, schema, row projection)
│       │   ├── RowPipeline.scala        # Ordered multi-file row fetch with bounded read-ahead
│       │   ├── DataFileReader.scala     # Reads JSON/NDJSON/CSV/LTSV input files into rows
│       │   ├── SchemaReconciler.scala   # Pure schema logic for multi-file operations (reconcile, diff)
│       │   ├── SchemaContract.scala     # Expected schema for `validate --expect-schema`
│       │   ├── StatsAssertion.scala     # Data-quality checks evaluated against footer statistics
│       │   └── LocationParsing.scala    # Path string → StorageLocation
│       ├── repositories/
│       │   ├── ParquetRepository.scala        # Parquet I/O interface (trait)
│       │   ├── HadoopParquetRepository.scala  # Hadoop-backed implementation
│       │   ├── HadoopConfigProvider.scala      # Per-location Hadoop config + cloud auth, cached
│       │   ├── ParquetRecordDecoder.scala      # Row → Map decoding
│       │   ├── ParquetSchemaBuilder.scala      # Schema construction
│       │   ├── ParquetWriteOps.scala           # Write/convert operations
│       │   ├── FooterReader.scala              # Cached footer/metadata reads
│       │   ├── MetadataReader.scala            # `metadata` command: footer → full report
│       │   ├── SchemaTypeReader.scala          # Schema types → logical type detail, group chain, schema tree
│       │   ├── PageInspector.scala             # `metadata --index/--pages`: column/offset index and page headers
│       │   ├── ParallelRowGroupReader.scala    # `--parallel` row group reads
│       │   ├── StatsComputer.scala             # `stats` command column statistics
│       │   └── LruCache.scala                  # Bounded LRU cache behind the footer and Hadoop config caches
│       ├── cloud/
│       │   ├── CloudCredentialManager.scala # Shared credential-resolution contract
│       │   ├── S3CredentialManager.scala
│       │   ├── AzureCredentialManager.scala
│       │   ├── AzureIdentityTokenProvider.scala # azure-identity access tokens for ABFS
│       │   └── GCSCredentialManager.scala
│       ├── filters/
│       │   └── FilterParser.scala    # SQL-like `--filter` expression parsing
│       ├── parsers/                  # Input text → rows (CsvParser, LTSVParser, TypeInferrer)
│       ├── formatters/               # Rows → output text (table/json/csv/markdown/ndjson/ltsv/pretty)
│       └── util/                     # Domain-free helpers (SizeParser, FileExtension, CredentialRedactor, …);
│                                     # imports no other parqueteer package
├── src/test/scala/         # Mirror structure of main, plus fuzz/ (fuzz targets)
├── project/                # sbt build configuration
├── scripts/                # Utility scripts
├── docs/                   # Extended documentation
├── .githooks/              # pre-commit and post-checkout hooks (enabled by `make setup-dev`)
├── .github/workflows/      # CI/CD pipelines
├── Makefile               # Build commands
├── build.sbt              # sbt build definition
└── .tool-versions         # Version specifications (for mise)
```

## Common Tasks

### Adding a New Command

All of these live in `src/main/scala/io/github/yusukensanta/parqueteer/cli/`:

1. Add a case class extending `Command` in `Commands.scala`
2. Declare the subcommand and its options in `ArgumentParser.scala`. Shell completions and the OPTIONS section of `--help` are rendered from the parser (via `CliSpec`), so they need no separate change
3. Write the handler in `ReadCommands.scala`, `WriteCommands.scala` or `InspectCommands.scala`, and route to it from `CommandExecutor.execute`
4. Add the help prose (summary, usage, examples) in `HelpFormatter.scala`
5. Add tests in `src/test/scala/io/github/yusukensanta/parqueteer/cli/`
6. Update documentation

### Adding Dependencies

Edit `build.sbt`:
```scala
libraryDependencies += "org.example" %% "library" % "version"
```

Then rebuild:
```bash
make compile
```

### Debugging

Use sbt directly for better debugging experience:
```bash
sbt
> compile
> test
> run --help
```

Or use your IDE (IntelliJ IDEA, VS Code with Metals):
1. Import project as sbt project
2. Run/debug configurations will be auto-generated

## CI/CD

`.github/workflows/ci.yml` runs on every pull request and on pushes to `main`:

- ✅ Static analysis (`scalafix --check`)
- ✅ Compiles and runs all tests on Java 17 and 21
- ✅ Enforces the coverage minimums (Java 21 job)
- ✅ Checks code formatting (`scalafmtCheckAll`)
- ✅ Checks architecture boundaries: `core` must not reference `cli`, and `core.util` must not import any other parqueteer package
- ✅ Runs the S3 integration tests against a RustFS container

`Build & Test (Java 17)`, `Build & Test (Java 21)` and `S3 Integration Tests (RustFS)` are required checks on `main`.

Make sure `make check` passes before submitting PR!

### Azure E2E (maintainers)

`.github/workflows/azure-e2e.yml` runs `AzureIntegrationTest` against a real ADLS Gen2 account on pushes to `main`, weekly, and on demand. It is not run on pull requests. Azurite cannot be used because it does not serve the `dfs` endpoint that ABFS needs.

The job signs in with GitHub OIDC (no secrets) and is skipped until the repository variables `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `AZURE_E2E_ACCOUNT` and `AZURE_E2E_CONTAINER` are set. On the Azure side:

1. Create an app registration with a federated credential: issuer `https://token.actions.githubusercontent.com`, audience `api://AzureADTokenExchange`, subject `repo:<owner>/<repo>:ref:refs/heads/main`.
2. Create its service principal and assign it `Storage Blob Data Contributor` on the test container only.

To run the tests locally, sign in with `az login` as an identity that has that role:

```bash
AZURE_E2E_ACCOUNT=<account> AZURE_E2E_CONTAINER=<container> AZURE_AUTH_METHOD=azure_cli \
  sbt "testOnly *AzureIntegrationTest"
```

## Getting Help

- 📖 **Documentation**: Check the [`docs/`](docs/) directory for detailed guides
- 🐛 **Issues**: Browse existing issues or create a new one
- 💬 **Discussions**: Ask questions in GitHub Discussions
- 📧 **Email**: Contact maintainers (see GitHub profile)

## Pull Request Guidelines

### Before Submitting

- [ ] Code compiles without errors (`make compile`)
- [ ] All tests pass (`make test`)
- [ ] Code is formatted (`make fmt`)
- [ ] Commit messages follow Conventional Commits
- [ ] PR description explains what and why

### PR Description Template

```markdown
## What

Brief description of changes

## Why

Why is this change needed?

## Testing

How did you test this?

## Checklist

- [ ] Tests added/updated
- [ ] Documentation updated
```

## Code Review Process

1. Maintainer reviews your PR
2. Automated checks must pass
3. Address review feedback
4. Once approved, maintainer will merge

Pull requests are squash-merged (the only merge method enabled), so each PR becomes one commit on `main`. Give the PR a Conventional Commits title, and split changes that should stay separate in history into separate PRs.

`main` requires signed commits. A PR that contains an unsigned commit cannot be merged even when all checks pass, so [set up commit signing](https://docs.github.com/en/authentication/managing-commit-signature-verification) before you push.

## Development Tips

### Faster Compilation

Use sbt shell for faster incremental compilation:
```bash
sbt
> ~compile    # Watch mode
> ~test       # Watch mode for tests
```

### IDE Setup

**IntelliJ IDEA:**
1. File → Open → Select `build.sbt`
2. Import as sbt project
3. Wait for indexing

**VS Code:**
1. Install Metals extension
2. Open project folder
3. Import build when prompted

### Common Issues

**Issue: sbt version mismatch**
```bash
make sync-versions    # Sync from .tool-versions
```

**Issue: Compilation errors after pull**
```bash
make clean
make compile
```

**Issue: Tests fail locally but pass in CI**
```bash
# Ensure you're using correct versions
make show-versions
```

## Release Process

(For maintainers only)

The version comes from git tags (`sbt-dynver`); there is no version file to edit.

1. Make sure CI is green on the `main` commit you want to release
2. Create and push a tag on that commit:
   ```bash
   git tag v1.2.3
   git push origin v1.2.3
   ```
3. The tag push triggers `.github/workflows/release.yml`, which:
   - runs the tests and builds reproducible tgz/zip distributions with checksums
   - creates the GitHub Release with auto-generated release notes (a version containing `-alpha`, `-beta` or `-rc` is marked as a pre-release)
   - updates the Homebrew formula in `yusukensanta/homebrew-parqueteer`
   - generates a build provenance attestation and attaches it to the release

Release notes are generated from commit history, so `CHANGELOG.md` does not need an entry per release.

## Code of Conduct

- Be respectful and inclusive
- Provide constructive feedback
- Focus on the code, not the person
- Help others learn and grow

## License

By contributing, you agree that your contributions will be licensed under the Apache 2.0 License.

---

**Questions?** Open an issue or start a discussion.
