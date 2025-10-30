
#!/bin/bash
# Extract all top-level function, class, and interface definitions from Kotlin files in compute folder
# Output results as symbol|type|file to compute_fix_working/functions_raw.txt

COMPUTE_DIR="/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute"
OUTPUT_FILE="/Users/dreadstar/workspace/orbot-android/compute_fix_working/functions_raw.txt"

: > "$OUTPUT_FILE"

find "$COMPUTE_DIR" -type f -name "*.kt" | while read file; do
    grep -E '^(class |interface |object |fun |sealed class |data class |enum class )' "$file" | while read line; do
        # Extract symbol name and type
        type=$(echo "$line" | awk '{print $1" "$2}' | sed 's/\(class\|interface\|object\|fun\|sealed class\|data class\|enum class\)//')
        symbol=$(echo "$line" | awk '{print $2}')
        # If data class, sealed class, enum class, use full type
        if [[ "$line" == data* ]]; then type="data class"; fi
        if [[ "$line" == sealed* ]]; then type="sealed class"; fi
        if [[ "$line" == enum* ]]; then type="enum class"; fi
        echo "$symbol|$type|$(basename "$file")" >> "$OUTPUT_FILE"
    done
done
