#!/bin/bash
# large-file-edit: preflight.sh
#
# Creates a /tmp working copy and a backup of the target file.
# All replace_string_in_file calls MUST target TMP_EDIT, not the original.
#
# Usage:
#   bash preflight.sh /absolute/path/to/file
#
# Outputs (printed to stdout so the agent can parse):
#   PREFLIGHT_OK
#   ORIGINAL:     <path>
#   TMP_EDIT:     /tmp/lfe_<name>_<ts>.edit    ← target for all edits
#   TMP_BACKUP:   /tmp/lfe_<name>_<ts>.bak     ← pristine copy; restore on abort
#   LINE_COUNT:   <n>
#   PACKAGE_LINE: <n>  (0 = no package/module declaration found)
#   TIMESTAMP:    <ts>
#
# Exit codes: 0 = success, 1 = error

set -e

FILE="$1"

if [ -z "$FILE" ]; then
    echo "ERROR: No file path provided."
    echo "Usage: bash preflight.sh /absolute/path/to/file"
    exit 1
fi

if [ ! -f "$FILE" ]; then
    echo "ERROR: File not found: $FILE"
    exit 1
fi

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BASENAME=$(basename "$FILE")
SAFE_NAME=$(echo "$BASENAME" | tr '/:' '__')
TMP_EDIT="/tmp/lfe_${SAFE_NAME}_${TIMESTAMP}.edit"
TMP_BACKUP="/tmp/lfe_${SAFE_NAME}_${TIMESTAMP}.bak"

cp "$FILE" "$TMP_EDIT"
cp "$FILE" "$TMP_BACKUP"

LINE_COUNT=$(wc -l < "$FILE")

# Detect package / module declaration (Kotlin, Java; 0 = not found / Python / plain text)
PKG_LINE=$(grep -n "^package \|^module " "$FILE" 2>/dev/null | head -1 | cut -d: -f1)
if [ -z "$PKG_LINE" ]; then
    PKG_LINE=0
fi

echo "PREFLIGHT_OK"
echo "ORIGINAL:     $FILE"
echo "TMP_EDIT:     $TMP_EDIT"
echo "TMP_BACKUP:   $TMP_BACKUP"
echo "LINE_COUNT:   $LINE_COUNT"
echo "PACKAGE_LINE: $PKG_LINE  (0 = no package/module declaration)"
echo "TIMESTAMP:    $TIMESTAMP"
echo ""
echo "NEXT: Run all replace_string_in_file calls on TMP_EDIT."
echo "      Then run: bash diff_check.sh TMP_EDIT ORIGINAL"
echo "      Then run: bash commit_edit.sh TMP_EDIT ORIGINAL"
