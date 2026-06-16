<p align="center">

<!-- portfolio-badge -->
[![Portfolio Docs](https://img.shields.io/badge/docs-yusukensanta.github.io-blue?style=flat-square)](https://yusukensanta.github.io/projects/parqueteer/)
<!-- portfolio-badge -->
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/logo/parqueteer-wordmark-dark.svg" />
    <img src="assets/logo/parqueteer-wordmark.svg" alt="parqueteer" height="72" />
  </picture>
</p>

<p align="center"><strong>Read, write, and inspect Parquet files on local storage, S3, GCS, and Azure — no Spark required.</strong></p>

[![CI](https://github.com/yusukensanta/parqueteer/actions/workflows/ci.yml/badge.svg)](https://github.com/yusukensanta/parqueteer/actions/workflows/ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/yusukensanta/parqueteer)](https://github.com/yusukensanta/parqueteer/releases/latest)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

---

## Features

- 🚀 **No cluster required** - single JVM process, no Spark setup
- ☁️ **Cloud storage** - S3, GCS, and Azure out of the box
- 📊 **Multiple output formats** - table, JSON, CSV, Markdown, NDJSON, LTSV
- 🔍 **Filtering** - SQL-like expressions with BETWEEN, IN, IS NULL, nested columns
- 🛠️ **Format conversion** - CSV/JSON ↔ Parquet
- 🔀 **Schema diff** - compare schemas of two Parquet files
- 🐚 **Shell completions** - bash, zsh, and fish

---

## Project Scope

Parqueteer is a **Parquet lifecycle tool for pipelines** — the gap between basic inspection CLIs and full query engines.

**In scope:**
- Schema operations: `schema`, `schema diff`, `validate`, `info` — designed to wire into CI/CD and data quality gates
- Cloud-native Parquet I/O: read, write, merge across local, S3, GCS, and Azure
- Format conversion: JSON/CSV ↔ Parquet with compression control
- Operational inspection: `stats`, column-level metadata — quick answers without a query engine

**Out of scope:**
- **SQL analytics, joins, aggregations** — use [DuckDB](https://duckdb.org/) for ad-hoc queries
- **General data exploration** — for quick local inspection, [pqrs](https://github.com/manojkarthick/pqrs) or [parquet-cli](https://github.com/apache/parquet-java/tree/master/parquet-cli) are lighter
- **Streaming/batch processing** — use Spark or Flink
- **Schema inference from Avro, ORC, Arrow** — out of format scope
- **Interactive/REPL mode** — parqueteer is a scriptable CLI, not an interactive shell

Feature requests outside this scope will be closed. When in doubt, open a discussion first.

---

## Quick Start

### Read Parquet Files

```bash
# Display as table (default)
parqueteer read data.parquet

# Select specific columns (I/O-level projection — only reads requested column chunks)
parqueteer read data.parquet --columns "id,name,email"

# Limit rows
parqueteer read data.parquet --limit 100

# Filter expressions
parqueteer read data.parquet --filter "age > 25"
parqueteer read data.parquet --filter "status IN ('active', 'pending')"
parqueteer read data.parquet --filter "score BETWEEN 80 AND 100"
parqueteer read data.parquet --filter "deleted_at IS NULL"
parqueteer read data.parquet --filter "address.city = 'Tokyo'"  # nested column

# Output formats: table (default), json, csv, pretty, markdown, ndjson, ltsv
parqueteer read data.parquet --format json
parqueteer read data.parquet --format csv
parqueteer read data.parquet --format ndjson
parqueteer read data.parquet --format ltsv

# Streaming output (low memory; disables table formatting)
parqueteer read data.parquet --stream --format ndjson

# Parallel row-group reads (speeds up large files)
parqueteer read data.parquet --parallel 4

# Combine flags
parqueteer read data.parquet --columns "id,name" --filter "age > 25" --limit 50 --format csv

# From cloud storage
parqueteer read s3://bucket/data.parquet
parqueteer read gs://bucket/data.parquet
parqueteer read abfss://container@account.dfs.core.windows.net/data.parquet
```

### File Information

```bash
parqueteer info data.parquet

# JSON output (for scripting)
parqueteer info data.parquet --format json

# Verbose: include column-level encoding and compression details
parqueteer info data.parquet --verbose
```

### Write Parquet Files

```bash
# JSON to Parquet
parqueteer write data.json output.parquet

# CSV to Parquet
parqueteer write data.csv output.parquet --input-format csv

# NDJSON / LTSV to Parquet
parqueteer write data.ndjson output.parquet --input-format ndjson
parqueteer write data.ltsv output.parquet --input-format ltsv

# With compression (uncompressed, snappy, gzip, lzo, brotli, lz4, zstd)
parqueteer write data.csv output.parquet --input-format csv --compression zstd

# Dry-run: validate input without writing
parqueteer write data.json output.parquet --dry-run

# From stdin
cat data.json | parqueteer write - output.parquet
```

### Convert Files

```bash
# CSV to Parquet
parqueteer convert data.csv data.parquet --compression snappy

# Parquet to JSON
parqueteer convert data.parquet data.json

# Parquet to CSV
parqueteer convert data.parquet data.csv
```

### Inspect Schema

```bash
# Show column names, types, nullability, compression
parqueteer schema data.parquet

# JSON output (for scripting)
parqueteer schema data.parquet --format json
```

### Column Statistics

```bash
# Show per-column null count, min, max (reads from file footer — no data scan)
parqueteer stats data.parquet

# JSON output (for scripting)
parqueteer stats data.parquet --format json

# From cloud storage
parqueteer stats s3://bucket/data.parquet
```

### Compare Schemas

```bash
# Table output (default)
parqueteer schema diff old.parquet new.parquet

# JSON output (for CI scripting)
parqueteer schema diff old.parquet new.parquet --format json

# Exit code 0 = identical, 1 = schemas differ
# Output symbols: + added, - removed, ~ changed, = unchanged
```

### Validate Files

```bash
parqueteer validate data.parquet

# Verbose: show all checks performed
parqueteer validate data.parquet --verbose

# Deep: also verify row-level data integrity (slower)
parqueteer validate data.parquet --deep
```

### Configuration

```bash
# Show effective configuration (CLI flags and environment variables)
parqueteer config

# Validate config file syntax
parqueteer config --validate
```

> **Note:** The config file (`~/.parqueteer/config.yaml`) is parsed and validated but settings are not yet applied at runtime. Use environment variables below to control defaults.

### Shell Completions

```bash
# bash
eval "$(parqueteer completions bash)"
# or persist:
parqueteer completions bash > /etc/bash_completion.d/parqueteer

# zsh
parqueteer completions zsh > ~/.zfunc/_parqueteer

# fish
parqueteer completions fish > ~/.config/fish/completions/parqueteer.fish
```

---

## Global Flags

These flags work with every command:

```bash
parqueteer --verbose read data.parquet    # -v: show stack traces on error
parqueteer --quiet read data.parquet      # -q: suppress non-error output
parqueteer --color=never read data.parquet  # color: auto (default), always, never
```

---

## Environment Variables

```bash
# Set default output format (table, json, csv, pretty, markdown, ndjson, ltsv)
export PARQUETEER_DEFAULT_FORMAT=json

# Color output control (auto, always, never). NO_COLOR is also respected.
export PARQUETEER_COLOR=never

# Enable verbose mode
export PARQUETEER_VERBOSE=true

# Default row limit for read (same as --limit)
export PARQUETEER_MAX_ROWS=1000

# Path to config file (default: ~/.parqueteer/config.yaml)
export PARQUETEER_CONFIG=/path/to/config.yaml
```

**Precedence**: CLI flags > environment variables > defaults

---

## Installation

### Option 1: Homebrew (macOS / Linux)

```bash
brew tap yusukensanta/parqueteer
brew install parqueteer
```

### Option 2: Distribution Package

Download the latest `.tgz` (or `.zip`) from the [releases page](https://github.com/yusukensanta/parqueteer/releases/latest), then:

```bash
# Using .tgz
tar xzf parqueteer-VERSION.tgz
cd parqueteer-VERSION/

# Or using .zip
unzip parqueteer-VERSION.zip
cd parqueteer-VERSION/

# Run
bin/parqueteer --help
```

**Note**: Java 21 (LTS) recommended for clean output. Java 25+ may show harmless JVM deprecation warnings from the Scala runtime.

### Build from Source

```bash
git clone https://github.com/yusukensanta/parqueteer.git
cd parqueteer

# Quick start with Make (recommended)
make setup-dev    # One-time setup
make compile      # Build the project
make assembly     # Create standalone JAR

# Or use sbt directly
sbt compile
sbt assembly
```

**Common commands:**
- `make compile` - Compile the project
- `make test` - Run tests
- `make assembly` - Create fat JAR (target/scala-3.7.4/parqueteer.jar)
- `make package` - Create distribution with launch scripts
- `make clean` - Remove build artifacts

**Prerequisites**:
- Java 21 (or Java 17+)
- SBT 1.11+ (or use `make install` with [mise](https://mise.jdx.dev) for automatic version management)

### Cloud Credentials

**AWS S3**:

Static credentials:
```bash
export AWS_ACCESS_KEY_ID=your_key
export AWS_SECRET_ACCESS_KEY=your_secret
```

AWS SSO (Identity Center):
```bash
# Configure a profile once
aws configure sso --profile my-profile

# Authenticate before use
aws sso login --profile my-profile

# Use the profile
parqueteer read s3://bucket/data.parquet --profile my-profile
# or set as default
export AWS_PROFILE=my-profile
parqueteer read s3://bucket/data.parquet
```

saml2aws (SAML-based SSO):
```bash
# saml2aws writes standard credentials to ~/.aws/credentials
saml2aws login

# Use the generated profile (default: saml)
export AWS_PROFILE=saml
parqueteer read s3://bucket/data.parquet
```

Named profile (any auth method):
```bash
parqueteer read s3://bucket/data.parquet --profile my-profile
```

**Google Cloud**:

User credentials (recommended for local development):
```bash
gcloud auth application-default login
parqueteer read gs://bucket/data.parquet
```

`gcloud auth application-default login` writes credentials to `~/.config/gcloud/application_default_credentials.json` and they are picked up automatically — no environment variable needed.

Service account key file:
```bash
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json
# or
export GCP_SERVICE_ACCOUNT_KEY_FILE=/path/to/key.json
parqueteer read gs://bucket/data.parquet
```

Optional: set project ID with `GCP_PROJECT_ID` or `GOOGLE_CLOUD_PROJECT`.

**Azure**:

Managed identity (default, no env vars required on Azure-hosted compute):
```bash
parqueteer read abfss://container@account.dfs.core.windows.net/data.parquet
```

Service principal:
```bash
export AZURE_AUTH_METHOD=service_principal
export AZURE_CLIENT_ID=your-client-id
export AZURE_CLIENT_SECRET=your-client-secret
export AZURE_TENANT_ID=your-tenant-id
parqueteer read abfss://container@account.dfs.core.windows.net/data.parquet
```

Shared key:
```bash
export AZURE_AUTH_METHOD=shared_key
export AZURE_STORAGE_KEY=your-storage-key
parqueteer read abfss://container@account.dfs.core.windows.net/data.parquet
```

SAS token:
```bash
export AZURE_AUTH_METHOD=sas_token
export AZURE_STORAGE_SAS_TOKEN=your-sas-token
parqueteer read abfss://container@account.dfs.core.windows.net/data.parquet
```

---

## Requirements

- **Java**: 21 (LTS) recommended for optimal experience
  - Java 21: Clean output, no warnings ✅
  - Java 17: Also fully supported
  - Java 25+: may show JVM deprecation warnings from the Scala runtime
- **Memory**: Minimum 1GB RAM
- **Disk**: ~1GB for full cloud dependencies

---

## Commands

| Command | Description |
|---------|-------------|
| `read` | Display Parquet file content with optional filtering and format selection |
| `info` | Show file metadata (file size, dates, writer version, compression ratio) |
| `count` | Print total row count from footer metadata (no data scan) |
| `schema FILE` | Column structure — names, types, nullability, compression |
| `stats FILE` | Column statistics — null count, min, max (from file footer) |
| `schema diff FILE1 FILE2` | Compare schemas of two Parquet files |
| `write` | Create a Parquet file from JSON, NDJSON, CSV, or LTSV input |
| `convert` | Convert between Parquet, JSON, CSV, NDJSON, and LTSV formats |
| `validate` | Verify Parquet file integrity |
| `merge` | Combine multiple Parquet files into one |
| `config` | Show effective configuration |
| `config --validate` | Validate config file syntax |
| `completions` | Generate shell completion scripts for bash, zsh, or fish |

Run `parqueteer <command> --help` for per-command options.

---

## Documentation

| Document | Description |
|----------|-------------|
| [docs/SBT_COMMANDS.md](docs/SBT_COMMANDS.md) | Complete sbt command reference with artifacts and use cases |
| [docs/SCALA_IMPLICITS.md](docs/SCALA_IMPLICITS.md) | How Scala `given`/`implicit` works in this codebase (Circe encoders, type classes) |
| [docs/PERFORMANCE_STRATEGY.md](docs/PERFORMANCE_STRATEGY.md) | Performance analysis, implemented optimizations, and future levers |
| [docs/FUTURE_ROADMAP.md](docs/FUTURE_ROADMAP.md) | Strategic roadmap: completed items, in-progress themes, and long-horizon ideas |
| [docs/BENCHMARKS.md](docs/BENCHMARKS.md) | Benchmark methodology and results matrix (populate via `scripts/benchmark.sh`) |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Development setup, workflow, and PR guidelines |
| [CHANGELOG.md](CHANGELOG.md) | Version history |
| [SECURITY.md](SECURITY.md) | Security policy and vulnerability reporting |

---

## License

Apache 2.0
