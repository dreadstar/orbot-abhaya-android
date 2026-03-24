#!/usr/bin/env bash
set -euo pipefail

# rollback_diff_skill.sh
# Usage:
#   ./rollback_diff_skill.sh <orbot_commit> <meshrabiya_commit>
#
# Generates diffs between current working tree and historic commits (local repo),
# emitting structured JSONL report.

ORBOT_COMMIT="${1:-}"
MESHRABIYA_COMMIT="${2:-}"

if [[ -z "$ORBOT_COMMIT" || -z "$MESHRABIYA_COMMIT" ]]; then
  cat >&2 <<EOF
Usage: $0 <orbot_commit> <meshrabiya_commit>
Example:
  $0 4da7673 9a9eddd
EOF
  exit 1
fi

BASE_DIR="/home/d8rkl3ft/workspace/orbot-abhaya-android"
TMP_DIR="/tmp/orbot-rollback-diff-$(date +%s)-$RANDOM"
mkdir -p "$TMP_DIR"

REPORT="${TMP_DIR}/rollback_diff_report_$(date +%Y%m%d%H%M%S).jsonl"

# metadata
cat > "$REPORT" <<EOF
{"type":"meta","orbot_commit":"$ORBOT_COMMIT","meshrabiya_commit":"$MESHRABIYA_COMMIT","current_commit":"$(git -C "$BASE_DIR" rev-parse HEAD)","scratch_dir":"$TMP_DIR","time":"$(date --iso-8601=seconds)"}
EOF

# Orbot diff
ORBOT_DIFF="${REPORT%.jsonl}-orbot.diff"

# ensure commit exists
if ! git -C "$BASE_DIR" cat-file -e "$ORBOT_COMMIT"^{commit}; then
  echo "ERROR: orbot commit not found: $ORBOT_COMMIT" >&2
  exit 2
fi

git -C "$BASE_DIR" diff --no-color "$ORBOT_COMMIT" -- . > "$ORBOT_DIFF"
cat >> "$REPORT" <<EOF
{"type":"diff","repo":"orbot","file":"$ORBOT_DIFF"}
EOF

# Meshrabiya diff
MESHRABIYA_DIFF="${REPORT%.jsonl}-meshrabiya.diff"

if ! git -C "$BASE_DIR/Meshrabiya" cat-file -e "$MESHRABIYA_COMMIT"^{commit}; then
  echo "ERROR: meshrabiya commit not found: $MESHRABIYA_COMMIT" >&2
  exit 3
fi

git -C "$BASE_DIR/Meshrabiya" diff --no-color "$MESHRABIYA_COMMIT" -- . > "$MESHRABIYA_DIFF"
cat >> "$REPORT" <<EOF
{"type":"diff","repo":"meshrabiya","file":"$MESHRABIYA_DIFF"}
EOF

# Working status
STATUS_FILE="${REPORT%.jsonl}-status.txt"
git -C "$BASE_DIR" status --short > "$STATUS_FILE"
cat >> "$REPORT" <<EOF
{"type":"status","file":"$STATUS_FILE"}
EOF

cat <<EOF
DONE.
Report: $REPORT
Orbot diff: $ORBOT_DIFF
Meshrabiya diff: $MESHRABIYA_DIFF
Status: $STATUS_FILE
EOF
