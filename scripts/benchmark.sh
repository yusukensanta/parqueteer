#!/usr/bin/env bash
# Benchmark parqueteer core operations across varying file sizes and column counts.
# Usage: ./scripts/benchmark.sh [bench-data-dir] [results-file]
#
# Results are appended to RESULTS_FILE (default: docs/BENCHMARKS.md).
# Requires: parqueteer on PATH (or PARQUETEER env var), python3

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCH_DATA="${1:-${SCRIPT_DIR}/../.bench-data}"
RESULTS_FILE="${2:-${SCRIPT_DIR}/../docs/BENCHMARKS.md}"
PARQUETEER="${PARQUETEER:-parqueteer}"
ITERATIONS="${ITERATIONS:-5}"  # number of timed runs per scenario

# ── Utilities ──────────────────────────────────────────────────────────────

log() { printf "  %s\n" "$*"; }
header() { printf "\n=== %s ===\n" "$*"; }

# Signature: time_cmd <cmd> [args...]
#
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

# ── Data check ─────────────────────────────────────────────────────────────

if [[ ! -d "$BENCH_DATA" ]] || [[ -z "$(ls "$BENCH_DATA"/*.parquet 2>/dev/null)" ]]; then
  echo "No benchmark data found in $BENCH_DATA"
  echo "Run: ./scripts/generate-test-data.sh"
  exit 1
fi

# ── Benchmark suite ────────────────────────────────────────────────────────

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
PARQUETEER_VERSION=$("$PARQUETEER" --version 2>/dev/null || echo "unknown")

header "parqueteer benchmark — $TIMESTAMP"
log "version   : $PARQUETEER_VERSION"
log "iterations: $ITERATIONS"
log "data dir  : $BENCH_DATA"

declare -A RESULTS

run_suite() {
  local parquet_file="$1"
  local label="${parquet_file##*/}"
  label="${label%.parquet}"

  log ""
  log "── $label ──"

  # read: all columns
  local t
  t=$(time_cmd "$PARQUETEER" read "$parquet_file" --format ndjson --quiet)
  log "  read (all cols)      $t"
  RESULTS["${label}:read_all"]="$t"

  # read: 2 columns (projection pushdown)
  t=$(time_cmd "$PARQUETEER" read "$parquet_file" --columns "id,score" --format ndjson --quiet)
  log "  read (2 cols)        $t"
  RESULTS["${label}:read_2cols"]="$t"

  # info (schema + metadata only)
  t=$(time_cmd "$PARQUETEER" info "$parquet_file" --format json --quiet)
  log "  info                 $t"
  RESULTS["${label}:info"]="$t"

  # validate
  t=$(time_cmd "$PARQUETEER" validate "$parquet_file" --quiet)
  log "  validate             $t"
  RESULTS["${label}:validate"]="$t"
}

for parquet_file in "$BENCH_DATA"/*.parquet; do
  run_suite "$parquet_file"
done

# Write + convert benchmarks (using smallest file as source)
SMALL_FILE="$BENCH_DATA/bench_10000rows_5cols.parquet"
if [[ -f "$SMALL_FILE" ]]; then
  TMPDIR_BENCH=$(mktemp -d)
  trap 'rm -rf "$TMPDIR_BENCH"' EXIT

  header "Write / convert (10k rows, 5 cols)"

  # convert parquet → json
  OUT_JSON="$TMPDIR_BENCH/out.json"
  t=$(time_cmd "$PARQUETEER" convert "$SMALL_FILE" "$OUT_JSON" --quiet)
  log "  convert parquet→json $t"
  RESULTS["10k5:convert_to_json"]="$t"

  # convert json → parquet
  if [[ -f "$OUT_JSON" ]]; then
    OUT_PARQUET="$TMPDIR_BENCH/out.parquet"
    t=$(time_cmd "$PARQUETEER" write "$OUT_JSON" "$OUT_PARQUET" --quiet)
    log "  write json→parquet   $t"
    RESULTS["10k5:write_from_json"]="$t"
  fi
fi

# ── Append results to BENCHMARKS.md ───────────────────────────────────────

header "Appending results to $RESULTS_FILE"

{
  echo ""
  echo "## Run: $TIMESTAMP"
  echo ""
  echo "- **Version**: $PARQUETEER_VERSION"
  echo "- **Iterations**: $ITERATIONS"
  echo "- **Host**: $(uname -srm)"
  echo ""
  echo "| Scenario | min | avg | max |"
  echo "|----------|-----|-----|-----|"
  for key in "${!RESULTS[@]}"; do
    val="${RESULTS[$key]}"
    min=$(echo "$val" | grep -o 'min=[^ ]*' | cut -d= -f2)
    avg=$(echo "$val" | grep -o 'avg=[^ ]*' | cut -d= -f2)
    max=$(echo "$val" | grep -o 'max=[^ ]*' | cut -d= -f2)
    echo "| $key | $min | $avg | $max |"
  done
  echo ""
} >> "$RESULTS_FILE"

log "Done. Results appended to $RESULTS_FILE"
