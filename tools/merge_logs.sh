#!/usr/bin/env bash

# Merge multiple log files into project-root phone_test_combo.log
# Each entry is prefixed by filename and sorted by timestamp.
# The output file is truncated at script start.

set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: $0 <log1> <log2> [more logs...]" >&2
  exit 1
fi

ROOT_DIR=$(pwd)
OUTFILE="$ROOT_DIR/phone_test_combo.log"
TMPFILE=$(mktemp -t merge_logs.XXXXXX)
truncate -s 0 "$OUTFILE"

for src in "$@"; do
  if [ ! -f "$src" ]; then
    echo "Error: file not found: $src" >&2
    rm -f "$TMPFILE"
    exit 2
  fi
  base=$(basename "$src")
  while IFS= read -r line; do
    if [[ $line =~ ^([0-9]{2}-[0-9]{2})[[:space:]]([0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}) ]]; then
      # Use 2026 placeholder year to make POSIX sort chronological
      ts="2026-${BASH_REMATCH[1]} ${BASH_REMATCH[2]}"
    else
      ts="0000-00-00 00:00:00.000"
    fi
    printf '%s|%s|%s\n' "$ts" "$base" "$line" >> "$TMPFILE"
  done < "$src"
done

sort -t '|' -k1,1 -k2,2 -k3,3 "$TMPFILE" | while IFS='|' read -r ts base line; do
  printf '[%s] %s\n' "$base" "$line"
done >> "$OUTFILE"

rm -f "$TMPFILE"

echo "Merged $* into $OUTFILE (sorted)"
