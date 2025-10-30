#!/bin/bash
# Usage: ./extract_kotlin_functions_single.sh <input_file> <output_file>
INPUT_FILE="$1"
OUTPUT_FILE="$2"

: > "$OUTPUT_FILE"

grep -E '^[[:space:]]*(class|interface|object|fun)[[:space:]]' "$INPUT_FILE" >> "$OUTPUT_FILE"
