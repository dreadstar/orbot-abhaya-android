#!/bin/bash
# Precise filter: Only include functions not present in their target file.
# Usage: bash filter_missing_functions_precise.sh

mapping_file="functions_mapping_sorted.txt"
output_file="functions_mapping_filtered.txt"

> "$output_file"

while IFS='|' read -r func_name file_name; do
    # Clean up whitespace
    func_name=$(echo "$func_name" | sed 's/^ *//;s/ *$//;s/^-//')
    file_name=$(echo "$file_name" | sed 's/^ *//;s/ *$//')
    # Only process non-empty function names and file names
    if [[ -z "$func_name" || -z "$file_name" ]]; then
        continue
    fi
    # Only check for function names (skip class/type names)
    # If function name starts with uppercase, skip (likely class/type)
    if [[ "$func_name" =~ ^[A-Z] ]]; then
        continue
    fi
    # Grep for 'fun <func_name>' in any .kt file in the compute folder
    if ! grep -r -q "fun[[:space:]]\+$func_name" ../Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/*.kt ../Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/*/*.kt; then
        echo "$func_name | $file_name" >> "$output_file"
    fi

done < "$mapping_file"

echo "Filtered missing functions written to $output_file"
