#!/usr/bin/env bash
# Compare parqueteer's metadata-read latency against other commonly used
# Parquet tools (DuckDB, pyarrow, Polars).
#
# Usage: ./scripts/benchmark-compare.sh [bench-data-dir] [results-file]
#
# Requires: parqueteer on PATH (or PARQUETEER env var), python3 with
# duckdb/pyarrow/polars importable (set BENCH_PYTHON to a specific
# interpreter/venv if they're not on the ambient python3). Any tool that
# isn't importable is skipped, not treated as a failure — this script is
# meant to run with whichever subset of comparison tools happens to be
# installed.
#
# This is NOT a query-engine benchmark: DuckDB/pyarrow/Polars can do far
# more than parqueteer (SQL, joins, aggregations — see the "Project Scope"
# section of the README). It only compares the one thing they overlap on:
# reading a Parquet file's schema and row count. See docs/BENCHMARKS.md for
# why the results look the way they do.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCH_DATA="${1:-${SCRIPT_DIR}/../.bench-data}"
RESULTS_FILE="${2:-${SCRIPT_DIR}/../docs/BENCHMARKS.md}"
PARQUETEER="${PARQUETEER:-parqueteer}"
BENCH_PYTHON="${BENCH_PYTHON:-python3}"
ITERATIONS="${ITERATIONS:-5}"

FILES=(
  "bench_10000rows_5cols.parquet"
  "bench_100000rows_5cols.parquet"
)

log() { printf "  %s\n" "$*"; }
header() { printf "\n=== %s ===\n" "$*"; }

for f in "${FILES[@]}"; do
  if [[ ! -f "$BENCH_DATA/$f" ]]; then
    echo "Missing $BENCH_DATA/$f — run ./scripts/generate-test-data.sh first."
    exit 1
  fi
done

# Uses bash's $EPOCHREALTIME builtin (seconds.microseconds, bash 5+) rather
# than `date +%s%3N`: the latter assumes GNU date's %N-truncation extension
# (%3N == milliseconds), which uutils/BSD date implementations don't honor —
# they emit full nanoseconds regardless of the digit prefix, which then
# overflows bash's 64-bit integer arithmetic into garbage/negative results.
time_cmd() {
  local times=() i start end elapsed_ms
  for ((i = 0; i < ITERATIONS; i++)); do
    start=$EPOCHREALTIME
    if ! "$@" >/dev/null 2>&1; then
      return 1
    fi
    end=$EPOCHREALTIME
    elapsed_ms=$(awk -v s="$start" -v e="$end" 'BEGIN { printf "%.0f", (e - s) * 1000 }')
    times+=("$elapsed_ms")
  done

  local sum=0 min max t
  min="${times[0]}"
  max="${times[0]}"
  for t in "${times[@]}"; do
    sum=$(( sum + t ))
    min=$(( t < min ? t : min ))
    max=$(( t > max ? t : max ))
  done
  local avg=$(( sum / ITERATIONS ))

  echo "min=${min}ms avg=${avg}ms max=${max}ms"
}

tool_available() {
  "$BENCH_PYTHON" -c "import $1" >/dev/null 2>&1
}

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
# grep -m1 skips any stray JVM log lines (e.g. -Xlog:cds warnings, which go
# to stdout, not stderr) ahead of the actual "parqueteer X.Y.Z" line --
# `head -1` alone would grab a warning line instead when one is present.
PARQUETEER_VERSION=$("$PARQUETEER" --version 2>/dev/null | grep -m1 '^parqueteer ' || echo "unknown")
PYTHON_VERSION=$("$BENCH_PYTHON" --version 2>&1 || echo "unknown")

header "vs-other-tools benchmark — $TIMESTAMP"
log "parqueteer: $PARQUETEER_VERSION"
log "python    : $PYTHON_VERSION"
log "iterations: $ITERATIONS"

declare -A RESULTS
declare -A TOOL_VERSIONS

HAVE_PYARROW=0
HAVE_DUCKDB=0
HAVE_POLARS=0
tool_available pyarrow && HAVE_PYARROW=1
tool_available duckdb && HAVE_DUCKDB=1
tool_available polars && HAVE_POLARS=1

if [[ $HAVE_PYARROW -eq 1 ]]; then
  TOOL_VERSIONS["pyarrow"]=$("$BENCH_PYTHON" -c "import pyarrow; print(pyarrow.__version__)")
fi
if [[ $HAVE_DUCKDB -eq 1 ]]; then
  TOOL_VERSIONS["duckdb"]=$("$BENCH_PYTHON" -c "import duckdb; print(duckdb.__version__)")
fi
if [[ $HAVE_POLARS -eq 1 ]]; then
  TOOL_VERSIONS["polars"]=$("$BENCH_PYTHON" -c "import polars; print(polars.__version__)")
fi

if [[ $HAVE_PYARROW -eq 0 && $HAVE_DUCKDB -eq 0 && $HAVE_POLARS -eq 0 ]]; then
  echo "None of pyarrow/duckdb/polars are importable via '$BENCH_PYTHON'."
  echo "Install with: $BENCH_PYTHON -m pip install duckdb pyarrow polars"
  exit 1
fi

run_suite() {
  local parquet_file="$1"
  local label="${parquet_file##*/}"
  label="${label%.parquet}"

  log ""
  log "── $label ──"

  local t
  t=$(time_cmd "$PARQUETEER" schema "$parquet_file" --quiet)
  log "  parqueteer schema     $t"
  RESULTS["${label}:parqueteer:schema"]="$t"

  t=$(time_cmd "$PARQUETEER" count "$parquet_file" --quiet)
  log "  parqueteer count      $t"
  RESULTS["${label}:parqueteer:count"]="$t"

  if [[ $HAVE_PYARROW -eq 1 ]]; then
    t=$(time_cmd "$BENCH_PYTHON" -c "
import pyarrow.parquet as pq
pq.ParquetFile('$parquet_file').schema_arrow
")
    log "  pyarrow schema        $t"
    RESULTS["${label}:pyarrow:schema"]="$t"

    t=$(time_cmd "$BENCH_PYTHON" -c "
import pyarrow.parquet as pq
pq.ParquetFile('$parquet_file').metadata.num_rows
")
    log "  pyarrow count         $t"
    RESULTS["${label}:pyarrow:count"]="$t"
  fi

  if [[ $HAVE_DUCKDB -eq 1 ]]; then
    t=$(time_cmd "$BENCH_PYTHON" -c "
import duckdb
duckdb.sql(\"DESCRIBE SELECT * FROM read_parquet('$parquet_file')\")
")
    log "  duckdb schema         $t"
    RESULTS["${label}:duckdb:schema"]="$t"

    t=$(time_cmd "$BENCH_PYTHON" -c "
import duckdb
duckdb.sql(\"SELECT COUNT(*) FROM read_parquet('$parquet_file')\").fetchall()
")
    log "  duckdb count          $t"
    RESULTS["${label}:duckdb:count"]="$t"
  fi

  if [[ $HAVE_POLARS -eq 1 ]]; then
    t=$(time_cmd "$BENCH_PYTHON" -c "
import polars as pl
pl.scan_parquet('$parquet_file').collect_schema()
")
    log "  polars schema         $t"
    RESULTS["${label}:polars:schema"]="$t"

    t=$(time_cmd "$BENCH_PYTHON" -c "
import polars as pl
pl.scan_parquet('$parquet_file').select(pl.len()).collect()
")
    log "  polars count          $t"
    RESULTS["${label}:polars:count"]="$t"
  fi
}

for f in "${FILES[@]}"; do
  run_suite "$BENCH_DATA/$f"
done

header "Appending results to $RESULTS_FILE"

{
  echo ""
  echo "## vs. Other Tools (informational): $TIMESTAMP"
  echo ""
  echo "Metadata-read latency only (schema + row count) — not a query-engine"
  echo "comparison. See \"Why the gap, and why it doesn't close\" below."
  echo ""
  echo "- **parqueteer**: $PARQUETEER_VERSION"
  for tool in pyarrow duckdb polars; do
    if [[ -n "${TOOL_VERSIONS[$tool]:-}" ]]; then
      echo "- **$tool**: ${TOOL_VERSIONS[$tool]}"
    fi
  done
  echo "- **Host**: $(uname -srm)"
  echo "- **Iterations**: $ITERATIONS"
  echo ""
  echo "| File | Tool | Operation | min | avg | max |"
  echo "|------|------|-----------|-----|-----|-----|"
  for f in "${FILES[@]}"; do
    label="${f%.parquet}"
    for tool in parqueteer pyarrow duckdb polars; do
      for op in schema count; do
        key="${label}:${tool}:${op}"
        val="${RESULTS[$key]:-}"
        [[ -z "$val" ]] && continue
        min=$(echo "$val" | grep -o 'min=[^ ]*' | cut -d= -f2)
        avg=$(echo "$val" | grep -o 'avg=[^ ]*' | cut -d= -f2)
        max=$(echo "$val" | grep -o 'max=[^ ]*' | cut -d= -f2)
        echo "| $label | $tool | $op | $min | $avg | $max |"
      done
    done
  done
  echo ""
} >> "$RESULTS_FILE"

log "Done. Results appended to $RESULTS_FILE"
