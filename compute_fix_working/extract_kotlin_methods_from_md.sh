#!/bin/bash
# Extract all function, class, and interface definitions from IntelligentDistributedComputeService.md
# Output results to compute_fix_working/md_functions_raw.txt

MD_FILE="/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/IntelligentDistributedComputeService.md"
OUTPUT_FILE="/Users/dreadstar/workspace/orbot-android/compute_fix_working/md_functions_raw.txt"

: > "$OUTPUT_FILE"

# Extract class, interface, object, and fun definitions (top-level and inner)
grep -E '^(class |interface |object |fun )' "$MD_FILE" > "$OUTPUT_FILE"
