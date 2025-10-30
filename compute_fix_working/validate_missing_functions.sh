#!/bin/bash
# Validate that functions/classes in functions_mappings2.txt are NOT found in compute kotlin files

MAP_FILE="/Users/dreadstar/workspace/orbot-android/compute_fix_working/functions_mappings2.txt"
SRC_DIR="/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute"

missing=()
found=()

while IFS='|' read -r symbol rest; do
    # Normalize symbol (strip whitespace, dashes, parentheses)
    norm=$(echo "$symbol" | sed 's/[ ()-]//g')
    # Search for symbol in all .kt files
    result=$(grep -r --include='*.kt' -w "$norm" "$SRC_DIR")
    if [[ -z "$result" ]]; then
        missing+=("$norm")
    else
        found+=("$norm")
    fi
    # Optionally print result for each symbol
    echo "$norm: $( [[ -z "$result" ]] && echo MISSING || echo FOUND )"
    [[ -n "$result" ]] && echo "$result"

done < "$MAP_FILE"

# Summary

echo "\n--- SUMMARY ---"
echo "Missing symbols: ${missing[@]}"
echo "Found symbols: ${found[@]}"
