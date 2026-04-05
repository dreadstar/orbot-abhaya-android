#!/bin/bash
# large-file-edit: commit_edit.sh
#
# Atomically overwrites the original file with the validated /tmp edit copy.
# Refuses to commit if the edit copy is empty or the reduction is suspiciously large.
#
# Usage:
#   bash commit_edit.sh /tmp/lfe_file.edit /absolute/path/to/original [--force]
#
#   --force  bypasses the 40% size-reduction safety guard (use only after reviewing diff)
#
# Exit codes:
#   0  COMMIT_OK   — original overwritten successfully
#   1  ERROR       — precondition failed; original NOT modified
#   2  WARNING     — size reduction > 40%; use --force to override after reviewing diff

set -e

TMP_EDIT="$1"
ORIGINAL="$2"
FORCE="${3:-}"

if [ -z "$TMP_EDIT" ] || [ -z "$ORIGINAL" ]; then
    echo "Usage: bash commit_edit.sh <tmp_edit_path> <original_path> [--force]"
    exit 1
fi

for F in "$TMP_EDIT" "$ORIGINAL"; do
    if [ ! -f "$F" ]; then
        echo "ERROR: File not found: $F"
        echo "Original NOT modified."
        exit 1
    fi
done

TMP_LINES=$(wc -l < "$TMP_EDIT")
ORIG_LINES=$(wc -l < "$ORIGINAL")

if [ "$TMP_LINES" -lt 1 ]; then
    echo "ERROR: Edit copy is empty. Refusing to commit."
    echo "Original NOT modified."
    exit 1
fi

REDUCTION=$((ORIG_LINES - TMP_LINES))
THRESHOLD=$((ORIG_LINES * 40 / 100))
if [ "$REDUCTION" -gt "$THRESHOLD" ] && [ "$FORCE" != "--force" ]; then
    echo "WARNING: Edit copy is $REDUCTION lines shorter than original ($ORIG_LINES → $TMP_LINES)."
    echo "         This exceeds the 40% reduction safety guard."
    echo ""
    echo "  Review the diff first:"
    echo "    bash diff_check.sh \"$TMP_EDIT\" \"$ORIGINAL\""
    echo ""
    echo "  If the reduction is intentional, rerun with --force:"
    echo "    bash commit_edit.sh \"$TMP_EDIT\" \"$ORIGINAL\" --force"
    echo ""
    echo "  Original NOT modified."
    exit 2
fi

# Atomic in-tree swap: copy then rename to minimise window where file is missing
INPLACE_TMP="${ORIGINAL}.lfe_$$"
cp "$TMP_EDIT" "$INPLACE_TMP"
mv -f "$INPLACE_TMP" "$ORIGINAL"

echo "COMMIT_OK"
echo "Written:  $TMP_EDIT → $ORIGINAL"
echo "Lines:    $ORIG_LINES → $TMP_LINES  (delta: $((TMP_LINES - ORIG_LINES)))"
BAK=$(echo "$TMP_EDIT" | sed 's/\.edit$/.bak/')
echo "Backup:   $BAK  (kept until you delete it)"
echo ""
echo "NEXT: Verify with read_file on key sections of the original."
