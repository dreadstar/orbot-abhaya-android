#!/bin/bash
# brace_paren_check.sh: Count open/close braces, parentheses, and brackets in a file
# Usage: ./brace_paren_check.sh <filename>

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <filename>"
    exit 1
fi
file="$1"

symbols=( '(' ')' '{' '}' '[' ']' )
# Escape special characters for grep and use -F for fixed string
for s in "${symbols[@]}"; do
    case "$s" in
        '(' | ')' | '[' | ']' | '{' | '}')
            pattern="\\$s"
            ;;
        *)
            pattern="$s"
            ;;
    esac
    count=$(grep -o -F "$s" "$file" | wc -l)
    echo "$s: $count"
done

# Summary for pairs
paren_open=$(grep -o -F '(' "$file" | wc -l)
paren_close=$(grep -o -F ')' "$file" | wc -l)
brace_open=$(grep -o -F '{' "$file" | wc -l)
brace_close=$(grep -o -F '}' "$file" | wc -l)
bracket_open=$(grep -o -F '[' "$file" | wc -l)
bracket_close=$(grep -o -F ']' "$file" | wc -l)

echo "\nSummary:"
echo "Parentheses: $paren_open open / $paren_close close"
echo "Braces: $brace_open open / $brace_close close"
echo "Brackets: $bracket_open open / $bracket_close close"

if [ "$paren_open" -eq "$paren_close" ] && [ "$brace_open" -eq "$brace_close" ] && [ "$bracket_open" -eq "$bracket_close" ]; then
    echo "All symbol pairs are balanced."
else
    echo "Unbalanced symbol pairs detected!"
fi
