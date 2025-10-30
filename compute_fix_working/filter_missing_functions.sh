#!/bin/bash
# Filters out symbols from functions_mapping_sorted.txt that are already present in Kotlin files in the compute directory.
# Only keeps missing function declarations (not classes, sealed classes, or data classes).

mapping_file="functions_mapping_sorted.txt"
compute_dir="../Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute"
temp_file="filtered_functions.tmp"
output_file="functions_mapping_filtered.txt"

# Get all function names declared in compute directory (recursively)
find "$compute_dir" -name '*.kt' | xargs grep -hE '^\s*fun\s+[a-zA-Z0-9_]+' | \
    sed -E 's/^\s*fun\s+([a-zA-Z0-9_]+).*/\1/' | sort | uniq > declared_functions.txt

# Filter mapping file: only keep rows where first field is not in declared_functions.txt and is not a class/struct
awk -F'|' '{
    # Remove leading dash and whitespace
    symbol=$1;
    gsub(/^\s*-?/, "", symbol);
    gsub(/\s+$/, "", symbol);
    # Exclude class/type symbols by pattern
    if (symbol ~ /^(class|sealed class|data class|object|interface)$/) next;
    # Exclude symbols that look like types (start with uppercase letter)
    if (symbol ~ /^[A-Z]/) next;
    # Only keep if symbol is not in declared_functions.txt
    cmd = "grep -w '" symbol "' declared_functions.txt > /dev/null";
    if (system(cmd) != 0) print symbol " | " $2;
}' "$mapping_file" > "$output_file"

rm -f declared_functions.txt $temp_file

echo "Filtered missing functions written to $output_file"
