# SBT Commands Reference

Complete guide to sbt commands used in parqueteer project.

---

## Basic Commands

### `sbt compile`
**What it does**: Compiles Scala source code to `.class` files

```bash
sbt compile
# Output: target/scala-3.7.3/classes/
```

**Use when**:
- ✅ Checking if code compiles
- ✅ During development
- ✅ Running in IDE

**Artifacts**:
```
target/scala-3.7.3/classes/
├── io/parqueteer/cli/CliApp.class
├── io/parqueteer/core/...
└── logback.xml
```

**Size**: ~5 MB (compiled classes only)

---

### `sbt test`
**What it does**: Compiles and runs all tests

```bash
sbt test
# Runs ScalaTest suites
```

**Use when**:
- ✅ Verifying code correctness
- ✅ Before commits
- ✅ In CI/CD pipeline

**Output**:
```
[info] ArgumentParserTest:
[info] - should parse read command correctly ✓
[info] All tests passed.
```

---

### `sbt clean`
**What it does**: Deletes all build artifacts

```bash
sbt clean
# Removes target/ directory
```

**Use when**:
- ✅ Build issues (corrupted cache)
- ✅ Before fresh rebuild
- ✅ Reclaiming disk space

**Removes**:
```
target/                 # Entire directory deleted
project/target/         # Project build cache
project/project/        # Nested build cache
```

---

## Packaging Commands

### `sbt assembly`
**What it does**: Creates a **fat JAR** (uber JAR) with all dependencies

```bash
sbt assembly
# Output: target/scala-3.7.3/parqueteer.jar
```

**Use when**:
- ✅ Need single, portable file
- ✅ Distributing to users without package managers
- ✅ Running on systems without dependency management
- ✅ Simplicity is priority

**Artifacts**:
```
target/scala-3.7.3/parqueteer.jar   # 766 MB - Everything included!
```

**What's inside**:
```
parqueteer.jar
├── io/parqueteer/...           # Your code
├── org/apache/parquet/...      # Parquet library
├── com/amazonaws/...           # AWS SDK
├── scala/...                   # Scala runtime
└── META-INF/MANIFEST.MF        # Main-Class entry
```

**Pros**:
- ✅ Single file - easy to distribute
- ✅ Works anywhere with Java installed
- ✅ No classpath issues
- ✅ Perfect for GitHub releases

**Cons**:
- ❌ Large file size (766 MB)
- ❌ Slow builds (merges all JARs)
- ❌ Duplicate classes need merge strategies

**Plugin**: `sbt-assembly`
```scala
// In project/plugins.sbt
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
```

**How to run**:
```bash
java -jar target/scala-3.7.3/parqueteer.jar --help
```

---

### `sbt stage`
**What it does**: Creates a **staged application** with launcher scripts

```bash
sbt stage
# Output: target/universal/stage/
```

**Use when**:
- ✅ Need clean output (no JVM warnings)
- ✅ Professional distribution
- ✅ Better organization (separate lib directory)
- ✅ Easier to update dependencies

**Artifacts**:
```
target/universal/stage/
├── bin/
│   ├── parqueteer       # Unix launcher script (23 KB)
│   └── parqueteer.bat   # Windows launcher script (20 KB)
└── lib/
    ├── io.parqueteer.parqueteer-0.1.0-SNAPSHOT.jar     # Your code (471 KB)
    ├── org.scala-lang.scala3-library_3-3.7.3.jar       # Scala runtime
    ├── com.github.mjakubowski84.parquet4s-core_3-...   # Dependencies
    └── ... (200+ dependency JARs)
```

**What launcher scripts do**:
1. Set up classpath (all JARs in lib/)
2. Add JVM options (memory, --add-opens flags)
3. Execute main class
4. Handle shell-specific quirks

**Pros**:
- ✅ Clean output (launcher script suppresses warnings)
- ✅ Professional appearance
- ✅ JVM options pre-configured
- ✅ Easy to see dependencies (lib/ folder)
- ✅ Can update single JARs

**Cons**:
- ❌ Multiple files (need to distribute as ZIP)
- ❌ Requires extraction step
- ❌ More complex distribution

**Plugin**: `sbt-native-packager`
```scala
// In build.sbt
.enablePlugins(JavaAppPackaging)
```

**How to run**:
```bash
target/universal/stage/bin/parqueteer --help
# Or on Windows:
target\universal\stage\bin\parqueteer.bat --help
```

---

### `sbt universal:packageBin`
**What it does**: Creates a **ZIP distribution** (staged app packaged)

```bash
sbt universal:packageBin
# Output: target/universal/parqueteer-0.1.0-SNAPSHOT.zip
```

**Use when**:
- ✅ Distributing staged application
- ✅ Professional releases
- ✅ Users need to extract and run

**Artifacts**:
```
target/universal/parqueteer-0.1.0-SNAPSHOT.zip   # 685 MB
```

**What's inside**:
```
parqueteer-0.1.0-SNAPSHOT/
├── bin/
│   ├── parqueteer       # Ready to run!
│   └── parqueteer.bat
└── lib/
    └── ... (all JARs)
```

**User experience**:
```bash
# Download
wget https://github.com/yusukensanta/parqueteer/releases/download/v0.1.0/parqueteer-0.1.0.zip

# Extract
unzip parqueteer-0.1.0.zip
cd parqueteer-0.1.0/

# Run (clean output!)
bin/parqueteer --help
```

**Pros**:
- ✅ Best user experience (no warnings)
- ✅ Professional packaging
- ✅ Cross-platform (Unix + Windows scripts)
- ✅ Pre-configured JVM options

**Cons**:
- ❌ Extra extraction step
- ❌ Larger download than fat JAR (685 MB vs 766 MB compressed)

---

## Less Common Commands

### `sbt run`
**What it does**: Compiles and runs the main class

```bash
sbt "run --help"
# Compiles and executes CliApp.main()
```

**Use when**:
- ✅ Quick testing during development
- ✅ Debugging

**Note**: Slower than running JAR directly

---

### `sbt package`
**What it does**: Creates a thin JAR (no dependencies)

```bash
sbt package
# Output: target/scala-3.7.3/parqueteer_3-0.1.0-SNAPSHOT.jar
```

**Artifacts**:
```
target/scala-3.7.3/parqueteer_3-0.1.0-SNAPSHOT.jar   # ~471 KB
```

**Use when**:
- ✅ Publishing to Maven repository
- ✅ Library development (not applications)

**Not useful for**:
- ❌ End users (missing dependencies)
- ❌ Standalone distribution

**How to run** (requires classpath):
```bash
java -cp "target/scala-3.7.3/parqueteer_3-0.1.0-SNAPSHOT.jar:lib/*" \
  io.parqueteer.cli.CliApp --help
# Too complex for users!
```

---

### `sbt publishLocal`
**What it does**: Publishes to local Ivy repository

```bash
sbt publishLocal
# Output: ~/.ivy2/local/io.parqueteer/parqueteer_3/0.1.0-SNAPSHOT/
```

**Use when**:
- ✅ Testing library in other local projects
- ✅ Multi-module projects

---

### `sbt console`
**What it does**: Starts Scala REPL with project classpath

```bash
sbt console
# Interactive Scala shell
```

**Use when**:
- ✅ Testing code snippets
- ✅ Exploring APIs
- ✅ Quick experiments

**Example**:
```scala
scala> import io.parqueteer.core.models._
scala> StorageLocationParser.parse("s3://bucket/file.parquet")
```

---

## Command Comparison

| Command | Output | Size | Use Case |
|---------|--------|------|----------|
| `compile` | .class files | 5 MB | Development |
| `package` | Thin JAR | 471 KB | Libraries |
| `assembly` | Fat JAR | 766 MB | Simple distribution |
| `stage` | Staged app | ~700 MB | Professional use |
| `universal:packageBin` | ZIP | 685 MB | Best for users |

---

## Why I Used These Commands

### For Development
```bash
sbt compile      # Fast feedback during coding
sbt test         # Verify correctness
```

### For Distribution
```bash
sbt assembly               # Quick single-file distribution
sbt universal:packageBin   # Professional user experience
```

### For Debugging
```bash
sbt clean compile test     # Fresh build to isolate issues
```

---

## Advanced: Command Combinations

### Clean Build Everything
```bash
sbt clean compile test assembly universal:packageBin
# Nuclear option: clean slate → all artifacts
```

### Quick Iteration
```bash
sbt ~compile
# Auto-recompile on file changes (~ = continuous)
```

### Parallel Commands
```bash
sbt clean compile test
# Runs in sequence: clean → compile → test
```

---

## Plugin-Specific Commands

### sbt-assembly
```bash
sbt "assembly / assemblyMergeStrategy"      # Show merge strategies
sbt "show assembly / fullClasspath"         # Show what's included
```

### sbt-native-packager
```bash
sbt stage                   # Staged application
sbt universal:packageBin    # ZIP distribution
sbt universal:packageZipTarball  # .tgz distribution
sbt debian:packageBin       # .deb package (Linux)
sbt rpm:packageBin          # .rpm package (RedHat)
sbt windows:packageBin      # .msi installer (Windows)
sbt docker:publishLocal     # Docker image
```

---

## Performance Tips

### Speed Up Builds
```bash
# Increase memory
export SBT_OPTS="-Xmx2G"

# Use shell mode (faster for multiple commands)
sbt
> compile
> test
> assembly
```

### Parallel Execution
```scala
// In build.sbt
Global / concurrentRestrictions := Seq(
  Tags.limit(Tags.CPU, Runtime.getRuntime.availableProcessors())
)
```

---

## Common Errors

### Out of Memory
```bash
# Error: Java heap space
# Solution: Increase memory
export SBT_OPTS="-Xmx2G -XX:+UseG1GC"
sbt assembly
```

### Merge Conflicts (assembly)
```bash
# Error: deduplicate: different file contents found
# Solution: Configure merge strategy in build.sbt
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}
```

### Missing Plugin
```bash
# Error: Not a valid command: assembly
# Solution: Add plugin to project/plugins.sbt
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
```

---

## Quick Reference

```bash
# Essential Commands
sbt compile              # Compile code
sbt test                 # Run tests
sbt clean                # Clean build
sbt assembly             # Fat JAR
sbt stage                # Staged app
sbt universal:packageBin # ZIP distribution

# Development
sbt run                  # Run application
sbt console              # Scala REPL
sbt ~compile             # Auto-compile

# Publishing
sbt publishLocal         # Local publish
sbt publish              # Remote publish
```

---

*Last updated: October 2025*
