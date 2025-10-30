#!/bin/bash
# Completely rewritten: Generate functions_mappings2.txt with non-overlapping groups, weighted by actual usage/co-occurrence
# Usage: bash generate_functions_mappings2.sh

# Paths
SRC_DIR="/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute"
MAP_FILE="/Users/dreadstar/workspace/orbot-android/compute_fix_working/function_mapping.txt"
RAW_FUNCS="/Users/dreadstar/workspace/orbot-android/compute_fix_working/functions_raw.txt"
OUT_FILE="/Users/dreadstar/workspace/orbot-android/compute_fix_working/functions_mappings2.txt"
TMP_WEIGHTS="/Users/dreadstar/workspace/orbot-android/compute_fix_working/tmp_func_weights.txt"
TMP_CALLS="/Users/dreadstar/workspace/orbot-android/compute_fix_working/tmp_func_calls.txt"

: > "$OUT_FILE"
: > "$TMP_WEIGHTS"
: > "$TMP_CALLS"


# Step 1: Extract all function/class/interface definitions
bash /Users/dreadstar/workspace/orbot-android/compute_fix_working/extract_kotlin_functions.sh


# Step 2: Build set of all extracted symbol names from functions_raw.txt
extracted_symbols=$(awk -F'|' '{print $1}' "$RAW_FUNCS" | sort | uniq)

# Step 3: For each mapping entry, output only if symbol is NOT present in extracted_symbols
while IFS='|' read -r name type target_file notes; do
    symbol=$(echo "$name" | tr -d ' ')
    found=0
    while read esym; do
        if [[ "$symbol" == "$esym" ]]; then
            found=1
            break
        fi
    done <<< "$extracted_symbols"
    if [[ $found -eq 0 ]]; then
        echo "$symbol|$type|$target_file|MISSING" >> "$OUT_FILE"
    fi
done < <(grep -E '^[^#].*\|' "$MAP_FILE")

MAP_FILE="/Users/dreadstar/workspace/orbot-android/compute_fix_working/function_mapping.txt"
RAW_FUNCS="/Users/dreadstar/workspace/orbot-android/compute_fix_working/functions_raw.txt"
OUT_FILE="/Users/dreadstar/workspace/orbot-android/compute_fix_working/functions_mappings2.txt"
TMP_WEIGHTS="/Users/dreadstar/workspace/orbot-android/compute_fix_working/tmp_func_weights.txt"
TMP_CALLS="/Users/dreadstar/workspace/orbot-android/compute_fix_working/tmp_func_calls.txt"

: > "$OUT_FILE"
: > "$TMP_WEIGHTS"
: > "$TMP_CALLS"

# Step 1: Extract all function/class/interface definitions
bash /Users/dreadstar/workspace/orbot-android/compute_fix_working/extract_kotlin_functions.sh

# Step 2: Extract all function calls/references (not just definitions)
find "$SRC_DIR" -type f -name "*.kt" | while read file; do
    # Extract all function calls (simple heuristic: word followed by '(')
    grep -oE '\b[A-Za-z0-9_]+\s*\(' "$file" | sed 's/(//g' | awk '{print $1}' | sort | uniq | while read func; do
        echo "$func|$(basename "$file")" >> "$TMP_CALLS"
    done
done

# Step 3: Build weighted association map
# For each function/class/interface, count how many times it is called/referenced in each file
while IFS='|' read -r name type file notes; do
    group_file=$(basename "$file" | tr -d ' ')
    func_name=$(echo "$name" | tr -d ' ')
    # Count references/calls in the group file
    count=$(grep "^$func_name|$group_file" "$TMP_CALLS" | wc -l)
    echo "$func_name|$group_file|$count" >> "$TMP_WEIGHTS"
done < <(grep -E '^[^#].*\|' "$MAP_FILE")

# Step 4: Assign each function/class/interface to the group with highest weight
awk -F'|' '{if (!seen[$1] || $3 > seen[$1]) {seen[$1]=$3; group[$1]=$2}} END {for (f in group) print f " | " group[f]}' "$TMP_WEIGHTS" > "$OUT_FILE"
