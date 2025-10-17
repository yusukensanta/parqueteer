# Parqueteer 🦜

**A modern CLI toolkit for Apache Parquet files with cloud storage support**

[![Scala Version](https://img.shields.io/badge/scala-3.7.3-red)]()
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)]()

---

## Features

- 🚀 **Fast & Lightweight** - No Spark required
- ☁️ **Cloud Native** - S3, GCS, Azure support  
- 📊 **Multiple Formats** - Table, JSON, CSV output
- 🔍 **Filtering** - SQL-like filter expressions
- 🛠️ **Data Conversion** - CSV/JSON ↔ Parquet

---

## Quick Start

### Read Parquet Files

```bash
# Display as table
parqueteer read data.parquet

# With filters
parqueteer read data.parquet --filter "age > 25"

# Output as JSON
parqueteer read data.parquet --format json

# From S3
parqueteer read s3://bucket/data.parquet
```

### File Information

```bash
parqueteer info data.parquet
```

### Convert Files

```bash
# CSV to Parquet
parqueteer convert data.csv data.parquet --compression snappy

# Parquet to JSON
parqueteer convert data.parquet data.json
```

---

## Installation

### Option 1: Distribution Package (Recommended - Clean Output)

```bash
# Download and extract
wget https://github.com/yusukensanta/parqueteer/releases/download/v0.1.0/parqueteer-0.1.0.zip
unzip parqueteer-0.1.0.zip
cd parqueteer-0.1.0/

# Run (no warnings!)
bin/parqueteer --help
```

### Option 2: Standalone JAR (Universal)

```bash
# Download
wget https://github.com/yusukensanta/parqueteer/releases/download/v0.1.0/parqueteer.jar

# Run
java -jar parqueteer.jar --help
```

**Note**: For the best experience with clean output, use Java 21 (LTS). Java 25+ may show harmless JVM deprecation warnings from the Scala runtime.

### Build from Source

```bash
# Clone the repository
git clone https://github.com/yusukensanta/parqueteer.git
cd parqueteer

# Compile the project
sbt compile

# Create distribution package (recommended - includes launch scripts)
sbt universal:packageBin
# Creates: target/universal/parqueteer-0.1.0-SNAPSHOT.zip

# OR create standalone JAR
sbt assembly
# Creates: target/scala-3.7.3/parqueteer.jar
```

**Prerequisites**:
- Java 21 (or Java 17+)
- SBT 1.9+

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

- `read` - Display file content
- `info` - Show metadata and schema
- `convert` - Convert between formats  
- `validate` - Verify file integrity

---

## License

Apache 2.0
