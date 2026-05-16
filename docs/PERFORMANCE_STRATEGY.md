# parqueteer Performance Strategy (Draft)

This document analyzes current code paths and outlines pragmatic + advanced optimizations for large-scale read/write scenarios in a CLI context. It complements the existing roadmap by focusing only on performance levers.

> Scope: Observations from repository state (Scala 3.7.3, parquet4s usage, direct Parquet API for writes, Try-based sync model). No code changes yet—this is strategic guidance.

---
## 1. Current Baseline & Observations

### 1.1 Read Path (ParquetRepository.readContent)
- Uses parquet4s `ParquetReader.as[RowParquetRecord]...read(path)` producing an Iterator.
- Converts entire iterator to `List` (potential full materialization):
  ```scala
  val records = config.maxRows match {
    case Some(limit) => reader.take(limit.toInt).toList
    case None        => reader.toList
  }
  ```
- Row group filtering is only via parquet4s `Filter` (predicate pushdown), but no explicit column projection optimization.
- Always computes total row count by opening file again (`getRowCount`) – double footer read.

### 1.2 Schema & Metadata Reads
- Uses `ParquetFileReader.open` directly each time—could share footer metadata cache.
- Compression ratio derived by iterating row groups; acceptable but can be cached.

### 1.3 Write Path (writeContent)
- Builds schema each invocation (either from provided ParquetSchema or inferred from first row).
- Uses `ExampleParquetWriter` with fully materialized input `List[Map[String, Any]]` (no streaming/iterator support yet).
- Per-row overhead: reflection avoided, but `Map[String, Any]` + pattern matching introduces boxing and allocation.

### 1.4 Data Model
- Generic `Map[String, Any]` rows → flexible but slower than generated case classes / columnar mapping.
- No explicit memory reuse / object pooling.

### 1.5 Concurrency Model
- Purely synchronous; no parallelization of row group processing.
- No user-configurable thread pools.

### 1.6 Error & Control Flow
- `Try` used; no ability to integrate structured async / streaming libs (cats-effect / FS2) yet.

---
## 2. Guiding Principles
1. Avoid premature complexity—optimize hot paths proven via profiling.
2. Always enable early exit & pruning (filters, column projection, row limits).
3. Embrace streaming over full materialization for large outputs.
4. Prefer structural zero-cost abstractions (value classes, inline methods) where possible.
5. Make performance controls explicit to users (flags, config).

---
## 3. Immediate Low-Effort Wins (Tier 1)
| Item | Change | Benefit | Effort |
|------|--------|---------|--------|
| Avoid double footer read | Pass footer metadata from first reader to row count logic | ~5–15% faster on small files (I/O bound) | Low |
| Lazy limit enforcement | Stop iteration once `maxRows` reached without converting all to List first | Lower allocation, faster early exit | Low |
| Column projection flag | Implement `--columns colA,colB` to restrict materialization | Lower CPU + memory | Low |
| Footer cache | Simple in-memory (path -> (timestamp, footer)) | Repeated operations faster | Low |
| Replace println in error filter fallback | Avoid console I/O in hot path (use logger at debug) | Micro | Low |

---
## 4. Near-Term Structural Enhancements (Tier 2)
### 4.1 Streaming Output Mode
- Introduce an API returning `Iterator[Map[String, Any]]` (current already has one internally) but do NOT `.toList` unless formatting requires it.
- For large exports (JSON/CSV), stream directly to stdout / file writer.
- Add `--stream` flag; fallback to buffered mode for table formatting.

### 4.2 Column Projection Implementation
- Parse `--columns` into a set; while converting `RowParquetRecord`, skip keys not selected.
- Possible parquet4s optimization: use its built-in column projection (if available) or a lower-level read schema.

### 4.3 Predicate Optimization Layer
- Pre-parse and canonicalize filter expression; detect trivial always-true/always-false branches.
- If filter impossible (e.g. `age > 10 AND age < 5`) → early empty result.

### 4.4 Metadata Reuse
- Add small LRU (path, mtime) -> (rowCount, footer schema summary)
- Invalidate on mtime change.

### 4.5 Compression & Encoding Tuning
- Expose row group size, page size, dictionary toggle via CLI flags already present—document performance tradeoffs.
- Provide presets: `--performance-profile fast|balanced|compress`.

---
## 5. Medium-Term Optimizations (Tier 3)
### 5.1 Parallel Row Group Reads
- Plan: Inspect footer → create tasks per row group → parallel decode → preserve order if requested.
- Use bounded thread pool (e.g. `java.util.concurrent.ForkJoinPool` or cats-effect `IO` pool if adopted later).
- Combine iterators with on-the-fly merge; respect `maxRows` (cancel remainder early).

### 5.2 Vectorization & Batch Processing
- Instead of per-row `Map[String, Any]`, introduce an internal `ColumnBatch` representation:
  ```scala
  final case class ColumnBatch(names: Array[String], columns: Array[Array[Any]], size: Int)
  ```
- Formatters consume batches; reduces per-row allocation & improves CPU cache locality.

### 5.3 Specialized Encodings
- Replace `Map[String, Any]` with generated case classes when schema known (user-provided schema or introspected once).
- Optional code generation step (experimental) to build fast readers.

### 5.4 Write Path Streaming
- Accept `Iterator[Map[String, Any]]` or `Source` (future streaming) to write large datasets without holding all in memory.
- Flush per N rows; reuse `Group` builders when possible.

### 5.5 Adaptive Row Group Sizing
- Dynamically adjust row group size based on observed compression ratio & memory usage.

### 5.6 Memory Monitoring Hooks
- Periodic sampling of `Runtime.getRuntime().freeMemory()` → trigger backpressure or log warnings.

---
## 6. Advanced / Strategic (Tier 4)
### 6.1 Asynchronous / Effect Integration
- Migrate to cats-effect IO for structured concurrency (enables parallelism, cancellation for `maxRows`).
- Combine with FS2 or ZIO streams for uniform streaming API.

### 6.2 Off-Heap Buffers / Zero-Copy
- Evaluate Arrow integration for zero-copy handling of primitive columns.
- Use memory-mapped files for local large reads (tradeoffs: OS cache interaction, complexity).

### 6.3 Pushdown & Metadata Intelligence
- Leverage statistics (min/max, null counts) to skip row groups predictively.
- Maintain local persistent cache of file stats (sidecar JSON) keyed by path + mtime.

### 6.4 SIMD / JNI Optimizations
- Evaluate libraries (e.g. Arrow Gandiva or vectorized filtering) for heavy predicate evaluation.
- Only after high-level structural wins are realized.

### 6.5 Native Image (GraalVM)
- Faster cold start & lower RSS for short-lived invocations.
- Need to remove/refactor dynamic reflection usage; add `--native-opt` build profile.

---
## 7. Benchmarking & Profiling Strategy
| Layer | Tool | Purpose |
|-------|------|---------|
| Micro (single method) | JMH | Compare map vs. batch conversion |
| End-to-end CLI | Custom script (time + /usr/bin/time) | Wall clock + RSS |
| Allocation Tracking | JFR / async-profiler | Identify hotspot allocations |
| Concurrency | JFR events | Detect contention / blocking |

Add a `scripts/perf/` directory:
- `generate-large-parquet.scala` (synthetic data)
- `bench-read.sh` varying flags (row group size, projection)

Key metrics: rows/sec, MB/sec, alloc bytes / row, CPU utilization.

---
## 8. Recommended Implementation Sequence
1. (T1) Remove unconditional `.toList` for streaming mode; implement column filtering.
2. (T1) Metadata/footer cache + avoid double row count read.
3. (T2) Introduce parallel row group prototype behind `--parallel`.
4. (T2) Batch representation experiment (toggle via env var).
5. (T3) Streaming writer for large JSON/CSV exports.
6. (T3) Adopt effect system for controlled parallelism & cancellation.
7. (T4) Stats-based row group skipping + persistent metadata cache.

---
## 9. Risks & Tradeoffs
| Optimization | Risk | Mitigation |
|-------------|------|------------|
| Parallel reads | Increased memory footprint | Batch size cap + monitoring |
| Footer cache | Stale metadata on file change | mtime validation before reuse |
| Batch model | Increased complexity for formatters | Keep row fallback path |
| cats-effect adoption | Learning curve / incremental refactor complexity | Start at repository boundary only |
| Native image | Build complexity | Provide optional profile, not default |

---
## 10. Instrumentation Plan
- Add lightweight timing utility: `Perf.time("readContent") { ... }` logging at debug.
- Expose `--metrics` flag to print summary (rows/sec, bytes/sec, compression ratio).
- Optional `PARQUETEER_DEBUG=perf` environment toggle for verbose metrics.

---
## 11. Example: Streaming Refactor Sketch
```scala
// New method
def streamContent(file: ParquetFile, config: ReadConfig): Iterator[Map[String, Any]] = {
  val path = Parquet4sPath(file.location.path)
  val filter = createFilter(config.filter)
  val base = ParquetReader
    .as[RowParquetRecord]
    .options(ParquetReader.Options(hadoopConf = hadoopConfig))
    .filter(filter)
    .read(path)

  val limited = config.maxRows match {
    case Some(limit) => base.take(limit.toInt)
    case None        => base
  }

  val projected = config.columns match {
    case Some(cols) =>
      val set = cols.toSet
      limited.map(r => convertRecordToMapFiltered(r, set))
    case None => limited.map(convertRecordToMap)
  }
  projected
}
```
(Formatting layers consume `Iterator` and stream to output.)

---
## 12. Summary
Immediate performance gains are available without architectural upheaval (streaming, projection, caching). Mid-term gains come from parallelism and batch models. Advanced paths (stats-based skipping, Arrow/zero-copy, native images) should wait until simpler improvements are realized and benchmarked.

*Draft – refine before commit.*
