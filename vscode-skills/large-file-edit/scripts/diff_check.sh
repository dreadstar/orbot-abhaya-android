#!/bin/bash
# large-file-edit: diff_check.sh
#
# Compares the /tmp edit copy against the original.
# Prints a unified diff and summarises whether changes are correct.
#
# Usage:
#   bash diff_check.sh /tmp/lfe_file.edit /absolute/path/to/original
#
# Exit codes:
#   0  CHANGES_DETECTED — diff non-empty; inspect hunks then commit
#   2  NO_CHANGES       — copies are identical; no edits landed (abort!)
#   3  ERROR            — missing file or diff tool failure

TMP_EDIT="$1"
ORIGINAL="$2"

if [ -z "$TMP_EDIT" ] || [ -z "$ORIGINAL" ]; then
    echo "Usage: bash diff_check.sh <tmp_edit_path> <original_path>"
    exit 3
fi

for F in "$TMP_EDIT" "$ORIGINAL"; do
    if [ ! -f "$F" ]; then
        echo "ERROR: File not found: $F"
        exit 3
    fi
done

ORIG_LINES=$(wc -l < "$ORIGINAL")
EDIT_LINES=$(wc -l < "$TMP_EDIT")
LINE_DELTA=$((EDIT_LINES - ORIG_LINES))

echo "=== DIFF SUMMARY ==="
echo "Original:  $ORIGINAL  ($ORIG_LINES lines)"
echo "Edit copy: $TMP_EDIT  ($EDIT_LINES lines)"
if [ "$LINE_DELTA" -ge 0 ]; then
    echo "Delta:     +${LINE_DELTA} lines"
else
    echo "Delta:     ${LINE_DELTA} lines"
fi
echo ""

diff -u \
    --label "ORIGINAL ($ORIGINAL)" \
    --label "EDIT_COPY ($TMP_EDIT)" \
    "$ORIGINAL" "$TMP_EDIT"
DIFF_EXIT=$?

echo ""
if [ $DIFF_EXIT -eq 0 ]; then
    echo "DIFF_RESULT: NO_CHANGES"
    echo "ERROR: Edit copy is IDENTICAL to original. No edits landed."
    echo "       Check replace_string_in_file oldString patterns."
    echo "       Do NOT commit."
    exit 2
elif [ $DIFF_EXIT -eq 1 ]; then
    echo "DIFF_RESULT: CHANGES_DETECTED"
    echo "  Review every hunk above."
    echo "  If all hunks are correct  → run commit_edit.sh"
    echo "  If unexpected hunks exist → abort and restore from .bak"
    exit 0
else
    echo "DIFF_RESULT: ERROR (diff exited $DIFF_EXIT)"
    exit 3
fi
