#!/bin/bash
# functional_diff.sh
# Compare method bodies for behavioral changes and provide human-friendly descriptions
# Usage: ./functional_diff.sh AndroidVirtualNode_orig.kt AndroidVirtualNode.kt

orig_file="$1"
refactored_file="$2"

# Extract all function definitions and bodies
extract_functions() {
    awk '/fun / {infun=1; print $0} infun && /\{/ {depth++; next} infun {if(depth>0){print $0}; if(/\}/){depth--; if(depth==0){infun=0}}}' "$1"
}

# Generate a summary for each function (simple heuristic)
summarize_function() {
    local file="$1"
    grep -E '^    fun |^    override fun |^    private fun |^    suspend fun ' "$file" | while read -r line; do
        fname=$(echo "$line" | sed -E 's/.*fun ([a-zA-Z0-9_]+)\(.*/\1/')
        echo "Function: $fname"
        # Extract first comment or line after signature for summary
        awk "/fun $fname\(/, /\}/" "$file" | grep -m 1 -E '//|/\*|#' || echo "  No summary found."
        echo "---"
    done
}

echo "==== Functional Diff: $orig_file vs $refactored_file ===="

echo "\n--- Original Functions Summary ---"
summarize_function "$orig_file"

echo "\n--- Refactored Functions Summary ---"
summarize_function "$refactored_file"

echo "\n--- Function Body Diff ---"
diff <(extract_functions "$orig_file") <(extract_functions "$refactored_file")
