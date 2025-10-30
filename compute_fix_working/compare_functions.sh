#!/bin/bash
# Compare functions/classes/interfaces in md_functions_raw.txt vs functions_raw.txt
# Output missing and present functions to compute_fix_working/functions_comparison.txt

MD_FUNCS="/Users/dreadstar/workspace/orbot-android/compute_fix_working/md_functions_raw.txt"
KT_FUNCS="/Users/dreadstar/workspace/orbot-android/compute_fix_working/functions_raw.txt"
OUTPUT_FILE="/Users/dreadstar/workspace/orbot-android/compute_fix_working/functions_comparison.txt"

: > "$OUTPUT_FILE"

echo "Missing in .kt files:" >> "$OUTPUT_FILE"
grep -Fvxf "$KT_FUNCS" "$MD_FUNCS" >> "$OUTPUT_FILE"

echo "\nPresent in .kt files:" >> "$OUTPUT_FILE"
grep -Fxf "$KT_FUNCS" "$MD_FUNCS" >> "$OUTPUT_FILE"
