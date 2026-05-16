#!/usr/bin/env bash
# Generate Parquet test data files for benchmarking.
# Usage: ./scripts/generate-test-data.sh [output-dir]
# Requires: parqueteer on PATH (or PARQUETEER env var), python3 or awk
#
# Produces files named: bench_{rows}rows_{cols}cols.parquet

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${1:-${SCRIPT_DIR}/../.bench-data}"
PARQUETEER="${PARQUETEER:-parqueteer}"

mkdir -p "$OUTPUT_DIR"

# generate_csv <output_file> <rows> <num_cols>
# Writes a CSV with an id column, N string/numeric columns, and a score column.
generate_csv() {
  local out="$1"
  local rows="$2"
  local cols="$3"

  python3 - "$out" "$rows" "$cols" <<'PYEOF'
import sys, random, string, csv

out_path, rows, cols = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
rng = random.Random(42)

extra_cols = [f"col_{i}" for i in range(cols - 2)]  # id + score are fixed
header = ["id"] + extra_cols + ["score"]

def rand_str(n=8):
    return ''.join(rng.choices(string.ascii_lowercase, k=n))

with open(out_path, "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(header)
    for i in range(rows):
        row = [i] + [rand_str() for _ in extra_cols] + [round(rng.uniform(0, 100), 2)]
        w.writerow(row)
PYEOF
}

run_conversion() {
  local csv_file="$1"
  local parquet_file="$2"
  "$PARQUETEER" write "$csv_file" "$parquet_file" --input-format csv --quiet 2>/dev/null
}

echo "Generating test data in: $OUTPUT_DIR"

# Matrix: (rows, cols)
CONFIGS=(
  "10000 5"
  "10000 50"
  "100000 5"
  "100000 50"
  "100000 500"
  "1000000 5"
  "1000000 50"
)

for config in "${CONFIGS[@]}"; do
  rows=$(echo "$config" | awk '{print $1}')
  cols=$(echo "$config" | awk '{print $2}')
  base="${OUTPUT_DIR}/bench_${rows}rows_${cols}cols"
  csv_file="${base}.csv"
  parquet_file="${base}.parquet"

  if [[ -f "$parquet_file" ]]; then
    echo "  skip  ${parquet_file##*/} (exists)"
    continue
  fi

  printf "  gen   %-40s" "${parquet_file##*/}"
  generate_csv "$csv_file" "$rows" "$cols"
  run_conversion "$csv_file" "$parquet_file"
  rm -f "$csv_file"
  size=$(du -sh "$parquet_file" | cut -f1)
  echo "$size"
done

echo ""
echo "Test data ready. Files:"
ls -lh "$OUTPUT_DIR"/*.parquet 2>/dev/null | awk '{print "  "$5, $9}'
