# parqueteer 🦜

**A modern CLI toolkit for Apache Parquet files with cloud storage support**

[![Scala Version](https://img.shields.io/badge/scala-3.7.3-red)]()
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)]()

---

## Features

- 🚀 **Fast & Lightweight** - No Spark required
- ☁️ **Cloud Native** - S3, GCS, Azure support
- 📊 **Multiple Formats** - Table, JSON, CSV, Markdown, NDJSON output
- 🔍 **Advanced Filtering** - SQL-like filter expressions with BETWEEN, IN, IS NULL, nested columns
- 🛠️ **Data Conversion** - CSV/JSON ↔ Parquet
- 🔀 **Schema Diff** - Compare schemas of two Parquet files
- 🐚 **Shell Completions** - bash, zsh, and fish tab-completion

---

## Quick Start

### Read Parquet Files

```bash
# Display as table
parqueteer read data.parquet

# With filters
parqueteer read data.parquet --filter "age > 25"
parqueteer read data.parquet --filter "status IN ('active', 'pending')"
parqueteer read data.parquet --filter "score BETWEEN 80 AND 100"
parqueteer read data.parquet --filter "deleted_at IS NULL"

# Nested column
parqueteer read data.parquet --filter "address.city = 'Tokyo'"

# Output as JSON
parqueteer read data.parquet --format json

# From S3
parqueteer read s3://bucket/data.parquet

# Read from stdin
cat data.json | parqueteer write - output.parquet
```

### File Information

```bash
parqueteer info data.parquet
```

### Write Parquet Files

```bash
# JSON to Parquet
parqueteer write data.json output.parquet

# CSV to Parquet with compression
parqueteer write data.csv output.parquet --input-format csv --compression zstd
```

### Convert Files

```bash
# CSV to Parquet
parqueteer convert data.csv data.parquet --compression snappy

# Parquet to JSON
parqueteer convert data.parquet data.json
```

### Compare Schemas

```bash
# Table output (default)
parqueteer schema diff old.parquet new.parquet

# JSON output (for CI scripting)
parqueteer schema diff old.parquet new.parquet --format json

# Exit code 0 = identical, 1 = schemas differ
```

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

## Installation

### Option 1: Distribution Package (Recommended)

```bash
# Download and extract
wget https://github.com/yusukensanta/parqueteer/releases/download/v0.4.1/parqueteer-0.4.1.zip
unzip parqueteer-0.4.1.zip
cd parqueteer-0.4.1/

# Run
bin/parqueteer --help
```

### Option 2: Standalone JAR (Universal)

```bash
wget https://github.com/yusukensanta/parqueteer/releases/download/v0.4.1/parqueteer.jar
java -jar parqueteer.jar --help
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
- `make assembly` - Create fat JAR (target/scala-3.7.3/parqueteer.jar)
- `make package` - Create distribution with launch scripts
- `make clean` - Remove build artifacts

**Prerequisites**:
- Java 21 (or Java 17+)
- SBT 1.9+ (or use `make install` with [mise](https://mise.jdx.dev) for automatic version management)

### Cloud Credentials

**AWS S3**:
```bash
export AWS_ACCESS_KEY_ID=your_key
export AWS_SECRET_ACCESS_KEY=your_secret
```

**Google Cloud**:
```bash
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json
```

**Azure**:
```bash
export AZURE_STORAGE_CONNECTION_STRING="..."
```

---

## Requirements

- **Java**: 21 (LTS) recommended for optimal experience
  - Java 21: Clean output, no warnings ✅
  - Java 17: Also fully supported
  - Java 25+: May show JVM deprecation warnings from Scala runtime
- **Memory**: Minimum 1GB RAM
- **Disk**: ~1GB for full cloud dependencies

---

## Commands

| Command | Description |
|---------|-------------|
| `read` | Display Parquet file content with optional filtering and format selection |
| `info` | Show file metadata and schema |
| `write` | Create a Parquet file from JSON or CSV input |
| `convert` | Convert between Parquet, JSON, and CSV formats |
| `validate` | Verify Parquet file integrity |
| `schema diff` | Compare schemas of two Parquet files |
| `config` | Show or validate configuration |
| `completions` | Generate shell completion scripts for bash, zsh, or fish |

Run `parqueteer <command> --help` for per-command options.

---

## License

Apache 2.0
