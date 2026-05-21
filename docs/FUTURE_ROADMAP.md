# parqueteer Future Roadmap (Draft)

This document is an exploratory / strategic view forward, complementing the existing `ROADMAP.md` (execution-focused). It groups future possibilities by impact and sequencing assumptions based on current codebase structure (CLI-centric, repository/service layering, Parquet-first scope) and technology choices (Scala 3, Parquet4s, Circe).

> Status: Draft for discussion. Not yet committed. Use to refine priorities.

---
## 1. Foundational Hardening (v0.2.x–v0.3.x)
### 1.1 Performance & Footprint
- Row group parallelism with controlled thread-pool (avoid global execution context contention)
- Column projection pruning (lazy materialization; integrate with repository layer methods)
- Benchmark harness (JMH module or simple timing suite in `scripts/`)
- Memory profiling & backpressure strategy for streaming (choose FS2 vs. custom iterator + Resource model)

### 1.2 Robust Error Model
- Introduce sealed ADT for domain errors (e.g. FileNotFound, SchemaMismatch, FilterParseError, CloudAuthError)
- Shift from `Try` to `Either[Error, A]` / `IO` (cats-effect) for clearer composition
- Uniform error rendering layer (colored CLI output, exit codes table)

### 1.3 Configuration System
- Layered config precedence: CLI flags > ENV > config file
- Validation phase w/ aggregated diagnostics (show all invalid fields at once)
- Config schema introspection command: `parqueteer config explain`

### 1.4 Test & Quality Enhancements
- Golden file tests for formatter outputs (table, json, csv)
- Property tests for conversion idempotency (Parquet -> JSON -> Parquet)
- Filter parser fuzzing (reject invalid tokens gracefully)

---
## 2. Data Access Evolution (v0.3.x–v0.4.x)
### 2.1 Streaming & Iteration
- Unified streaming interface: `parqueteer read --stream` returning progressive output
- Backpressure surface (limit rows/sec or memory usage)
- Push-based vs. pull-based API evaluation

### 2.2 Unified Query Layer
- Abstract filter representation (current SQL-like string -> parsed AST -> optimized predicate tree)
- Predicate pushdown hints (stats-aware: skip row groups based on min/max column metadata)
- Expression simplifier & canonicalizer

### 2.3 Schema Intelligence
- Auto schema merge across directory scans (conflict report mode)
- ~~Schema diff: `parqueteer schema diff file1 file2`~~ ✓ Done
- Schema evolution assistant (generate migration plan)

---
## 3. Format & Interop Expansion (v0.4.x–v0.5.x)
### 3.1 Additional Formats
- Arrow IPC writer (zero-copy path evaluation; depends on memory model choices)
- Avro bridge (read/write; map Parquet logical types)
- NDJSON streaming encoder (line-by-line)

### 3.2 External Systems
- JDBC export prototype (PostgreSQL focus; COPY-based bulk ingestion)
- Cloud catalog (AWS Glue minimal read integration)
- Optional Iceberg/Delta scan (metadata-only mode first)

### 3.3 Plugin Architecture (Experimental)
- SPI-based discovery of: InputFormat, OutputFormat, StorageBackend
- Isolation boundary: classloader or service loader pattern
- Version negotiation strategy

---
## 4. Cloud & Storage Maturity (v0.5.x–v0.6.x)
### 4.1 Credential & Security
- Unified credentials resolver (env, profile, config chain)
- Pluggable encryption-at-rest toggle (KMS integration research)
- Redaction of sensitive values in logs

### 4.2 Storage Optimizations
- Multipart upload for large writes (S3/GCS/Azure)
- Speculative prefetch of footer + first row group
- Client-side cache layer (LRU on metadata + small column chunks)

### 4.3 Cross-Cloud Ops
- File copy command: `parqueteer cp s3://... azure://...`
- Integrity verification (hash compare) after transfer
- Multi-cloud listing abstraction (uniform pagination)

---
## 5. Developer & User Experience (continuous)
### 5.1 CLI Ergonomics
- Rich help pages (sections, examples, env var references)
- Suggestions on unknown flags (Levenshtein distance)
- `--explain` flag for filter evaluation plan

### 5.2 Interactive & Exploratory Tools
- Mini REPL: load file, run filters, project columns
- Auto-complete (columns, filter operators) via JLine integration
- Session history persistence

### 5.3 Documentation & Learning
- Cookbook: tasks ("preview 10 rows", "merge two parquet files")
- Troubleshooting matrix (symptom → probable cause → resolution)
- Architecture overview diagram (services, repository, storage abstraction)

---
## 6. Architectural Refactors (strategic)
### 6.1 Effect System Adoption
- Introduce cats-effect IO progressively (repository first) behind façade
- Replace ad-hoc `Using.resource` with Resource
- Structured concurrency for parallel scans

### 6.2 Internal API Boundaries
- Split `core` into: `core-model`, `core-io`, `core-format`, `core-cli`
- Storage abstraction trait: `StorageBackend` (S3, GCS, Azure, Local, Memory)
- Filter parsing module isolation (option to reuse externally)

### 6.3 Metrics & Observability Hooks
- Pluggable metrics sink (noop by default)
- Timing wrappers around repository ops
- Latency histogram for row group reads

---
## 7. Advanced / Long Horizon (v0.7.x–v1.0)
### 7.1 Distributed / Scale-Out
- Split-scan planner for multi-node execution
- Coordination layer (gRPC or lightweight HTTP) for task distribution
- Fault recovery & speculative re-execution

### 7.2 Intelligent Optimization
- Adaptive predicate reordering (cheap filters first)
- Statistics learning cache (persist column stats summary)
- Cost-based scan planner prototype

### 7.3 Semi-Structured & Nested Enhancements
- Improved nested column projection (path-based selection: a.b.c)
- Schema flattening utility
- JSON schema inference -> Parquet schema suggestion

### 7.4 Governance & Policy
- Policy engine for allowed operations (read/write restrictions)
- Data classification tags on columns (PII detection skeleton)
- Audit trail command: `parqueteer audit log show`

---
## 8. Tooling & Release Engineering
### 8.1 Build & Release
- ~~Reproducible builds (sbt-ci-release adoption)~~ ✓ Done
- Automated changelog generation (GitHub Actions + conventional commits)
- Dependency vulnerability scanning (Snyk or OSS Index)

### 8.2 QA Pipelines
- Nightly large-file performance run (publish trend graph)
- Cross-Java version test matrix (17, 21, LTS+1)
- Optional native image experiment (GraalVM) for ultra-fast startup

### 8.3 Binary Distribution
- ~~Homebrew formula~~ ✓ Done (via `yusukensanta/homebrew-parqueteer`)
- Scoop manifest (Windows)
- Docker image (distroless + JRE)
- Self-updating mechanism ("parqueteer self-update")

---
## 9. Priority Matrix (Indicative)
| Theme | Impact | Effort | Priority Tier |
|-------|--------|--------|---------------|
| Parallel row group reads | High | Medium | P1 |
| Streaming mode | High | Medium | P1 |
| Unified error ADT | Medium | Low | P1 |
| Column projection pruning | High | Medium | P1 |
| Schema merge + diff | Medium | Medium | P2 |
| Arrow / Avro support | Medium | High | P2 |
| Plugin architecture | High (strategic) | High | P3 |
| Distributed scan planner | Very High | Very High | P3 |

Legend: P1 = near-term, P2 = mid-term, P3 = strategic.

---
## 10. Risk & Mitigation Notes
| Risk | Description | Mitigation |
|------|-------------|------------|
| Scope Creep | Feature list expands faster than capacity | Enforce milestone scoping; freeze per release |
| Performance Regression | Optimizations introduce edge-case bugs | Add regression benchmarks pre-merge |
| Complexity Inflation | Early distributed features add overhead | Delay distributed until core ergonomics mature |
| Library Lock-in | Deep dependency on Parquet4s specifics | Abstract repository layer strictly; test alt impl |
| Error Handling Inconsistency | Mix of Try/Either/throws across modules | Gradual codemod to single effect style |

---
## 11. Decision Backlog (To Be Clarified)
- Choose FS2 vs. plain iterator streaming (benchmark prototype both)
- Adopt cats-effect baseline now or after v0.3.0?
- Single-binary native image goal? (affects dependency choices)
- Formal plugin SPI vs. ad-hoc registration
- Long-term columnar analytics (vectorization) scope inclusion

---
## 12. Suggested Immediate Next Steps
1. Add benchmarking scaffold (simple timing around current read command)
2. Draft error ADT & refactor one path (readContent) as pilot
3. Implement column projection pruning baseline
4. Design streaming API shape (signature + minimal prototype)
5. Write developer doc: architecture + contribution conventions

---
## 13. How to Use This Document
- Treat as living strategy; prune items not aligned with core mission (fast Parquet CLI first)
- Before each minor release: select 3–5 P1 items only
- Track decisions in a DECISIONS.md file (append-only log)

