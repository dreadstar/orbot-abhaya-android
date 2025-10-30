#!/bin/bash
# Sorts functions_mappings2.txt by target file (second field, delimiter "|") and writes to functions_mapping_sorted.txt

input_file="functions_mappings2.txt"
output_file="functions_mapping_sorted.txt"

# Remove spaces around delimiter, sort by second field, preserve header if any
awk -F'|' '{gsub(/^ +| +$/,"",$1); gsub(/^ +| +$/,"",$2); print $1 " | " $2}' "$input_file" | sort -t '|' -k2,2 > "$output_file"
echo "Sorted mapping written to $output_file"
