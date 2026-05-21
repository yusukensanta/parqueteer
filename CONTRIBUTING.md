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

**To bypass the hook** (not recommended, only for emergencies):
```bash
git commit --no-verify
```

## Development Workflow

### Making Changes

1. **Create a branch**
   ```bash
   git checkout -b feature/my-awesome-feature
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
   git push origin feature/my-awesome-feature
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

We use scalafmt for consistent code formatting:

```bash
make fmt         # Format all code
make lint        # Check formatting (CI uses this)
```

### Testing

```bash
make test        # Run all tests
make check       # Run linting + tests
```

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

The Makefile automatically detects mise and syncs versions from `.tool-versions`.

### Without mise

You can contribute without mise! Just ensure you have:
- Java 21 (or 17+)
- sbt 1.9+

The project will build fine with any recent versions.

## Project Structure

```
parqueteer/
├── src/main/scala/io/github/yusukensanta/parqueteer/
│   ├── cli/
│   │   ├── CliApp.scala              # Entry point, command dispatch
│   │   └── CliOutputFormatter.scala  # Presentation layer (table/json/csv)
│   └── core/
│       ├── services/
│       │   └── ParquetService.scala  # Business logic, Either-based API
│       └── repositories/
│           ├── ParquetRepository.scala    # Parquet I/O orchestration
│           ├── ParquetRecordDecoder.scala # Row → Map decoding
│           ├── ParquetSchemaBuilder.scala # Schema construction
│           └── ParquetWriteOps.scala      # Write/convert operations
├── src/test/scala/         # Mirror structure of main
├── project/                # sbt build configuration
├── scripts/                # Utility scripts
├── .github/workflows/      # CI/CD pipelines
├── Makefile               # Build commands
├── build.sbt              # sbt build definition
└── .tool-versions         # Version specifications (for mise)
```

## Common Tasks

### Adding a New Command

1. Create command class in `src/main/scala/io/github/yusukensanta/parqueteer/cli/`
2. Add tests in `src/test/scala/io/github/yusukensanta/parqueteer/cli/`
3. Register in `CliApp.scala`
4. Update documentation

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

Our CI pipeline automatically:
- ✅ Compiles the project
- ✅ Runs all tests
- ✅ Checks code formatting
- ✅ Builds assembly JAR

Make sure `make check` passes before submitting PR!

## Getting Help

- 📖 **Documentation**: Check `.claude/` directory for detailed guides
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
- [ ] Changelog entry added (if applicable)
```

## Code Review Process

1. Maintainer reviews your PR
2. Automated checks must pass
3. Address review feedback
4. Once approved, maintainer will merge

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

Version is managed automatically by `sbt-ci-release` from git tags — no manual version bumps needed.

1. Update CHANGELOG.md
2. Create and push git tag:
   ```bash
   git tag v1.2.3
   git push origin v1.2.3
   ```
3. GitHub Actions runs two workflows in parallel:
   - **release.yml** — builds tgz/zip, creates GitHub Release, updates Homebrew formula
   - **maven-central.yml** — publishes JAR to Maven Central

See `.claude/adr-homebrew-distribution.md` for distribution methodology decisions.

## Code of Conduct

- Be respectful and inclusive
- Provide constructive feedback
- Focus on the code, not the person
- Help others learn and grow

## License

By contributing, you agree that your contributions will be licensed under the Apache 2.0 License.

---

**Questions?** Don't hesitate to ask in issues or discussions. We're here to help! 🎉
