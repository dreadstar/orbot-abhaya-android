#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
echo "Repo root: $REPO_ROOT"

SEARCH_DIRS=(
  "$REPO_ROOT/abhaya-sensor-android/app/src/main/java"
  "$REPO_ROOT/abhaya-sensor-android/src/main/java"
)

ORG_PREFIX="org/abhaya"
CANON_PREFIX="com/ustadmobile/meshrabiya"

report="$REPO_ROOT/tools/org_abhaya_report.txt"
: > "$report"

echo "Scanning for org/abhaya sources..."
for base in "${SEARCH_DIRS[@]}"; do
  if [ ! -d "$base" ]; then continue; fi
  find "$base" -path "*/$ORG_PREFIX/*" -type f -name '*.kt' | sort | while read -r f; do
    rel="${f#$REPO_ROOT/}"
    echo "----" >> "$report"
    echo "FOUND: $rel" | tee -a "$report"
    pkg=$(sed -n '1,8p' "$f" | sed -n 's/^package //p' || true)
    echo "package: ${pkg:-<none>}" >> "$report"
    echo "top-level signatures:" >> "$report"
    sed -n '1,300p' "$f" | sed -n -E 's/^[[:space:]]*(public|private|internal)?[[:space:]]*(data class|class|interface|object|fun)[[:space:]]+([A-Za-z0-9_]+)/\2 \3/p' >> "$report" || true

    name=$(basename "$f")
    candidates=$(find "$REPO_ROOT" -path "*/$CANON_PREFIX/*" -type f -name "$name" || true)
    if [ -z "$candidates" ]; then
      echo "candidates: <none>" >> "$report"
      echo "suggestion: keep OR refactor file to com.ustadmobile if intended" >> "$report"
    else
      echo "candidates:" >> "$report"
      for c in $candidates; do
        crel="${c#$REPO_ROOT/}"
        echo "  - $crel" >> "$report"
        echo "  package: $(sed -n '1,6p' "$c" | sed -n 's/^package //p' || true)" >> "$report"
        sum1=$(shasum -a 1 "$f" | awk '{print $1}')
        sum2=$(shasum -a 1 "$c" | awk '{print $1}')
        echo "  checksum org: $sum1" >> "$report"
        echo "  checksum cand: $sum2" >> "$report"
        if [ "$sum1" = "$sum2" ]; then
          echo "  diff: IDENTICAL" >> "$report"
        else
          echo "  diff: (first 80 chars of unified diff below)" >> "$report"
          diff -u --label "org/$name" "$f" --label "cand/$name" "$c" | sed -n '1,80p' >> "$report" || true
        fi
      done
      if [ "$sum1" = "$sum2" ]; then
        echo "suggestion: duplicate (IDENTICAL) — safe to delete org copy" >> "$report"
      else
        echo "suggestion: not identical — consider refactor: move to com.ustadmobile and fix package line, or manually merge" >> "$report"
      fi
    fi
    echo "" >> "$report"
  done
done

echo "Report written to: $report"
echo "Open the file and decide action per-file."
