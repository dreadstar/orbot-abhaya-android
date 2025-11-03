#!/bin/bash
# structural_diff.sh
# Compare class members, methods, and constructor signatures between two Kotlin files
# Usage: ./structural_diff.sh AndroidVirtualNode_orig.kt AndroidVirtualNode.kt

orig_file="$1"
refactored_file="$2"

# Extract class members and method signatures
extract_structure() {
    grep -E '^(    (val|var|fun) |class |object |companion object|override fun |private fun |protected fun |internal fun )' "$1" |
    sed 's/^[[:space:]]*//'
}

echo "==== Structural Diff: $orig_file vs $refactored_file ===="

echo "\n--- Original Structure ---"
extract_structure "$orig_file"

echo "\n--- Refactored Structure ---"
extract_structure "$refactored_file"

echo "\n--- Diff ---"
diff <(extract_structure "$orig_file") <(extract_structure "$refactored_file")
