#!/bin/bash

# Enhanced coverage calculation with test count information
echo "=== PROJECT COVERAGE AND TEST SUMMARY ==="

# Function to count total tests from XML test result files
count_tests() {
    local total_tests=0
    local total_failures=0
    local total_errors=0
    local total_skipped=0
    
    # Find all test result XML files and process them
    while IFS= read -r xml_file; do
        if [[ -f "$xml_file" ]]; then
            # Extract test counts from XML attributes using more robust parsing
            local tests=$(grep -o 'tests="[0-9]*"' "$xml_file" | head -1 | cut -d'"' -f2)
            local failures=$(grep -o 'failures="[0-9]*"' "$xml_file" | head -1 | cut -d'"' -f2)
            local errors=$(grep -o 'errors="[0-9]*"' "$xml_file" | head -1 | cut -d'"' -f2)
            local skipped=$(grep -o 'skipped="[0-9]*"' "$xml_file" | head -1 | cut -d'"' -f2)
            
            # Default to 0 if not found
            tests=${tests:-0}
            failures=${failures:-0}
            errors=${errors:-0}
            skipped=${skipped:-0}
            
            total_tests=$((total_tests + tests))
            total_failures=$((total_failures + failures))
            total_errors=$((total_errors + errors))
            total_skipped=$((total_skipped + skipped))
        fi
    done < <(find . -path "*/test-results/*/TEST-*.xml" -type f 2>/dev/null)
    
    echo "$total_tests $total_failures $total_errors $total_skipped"
}

# Get test statistics
test_stats=($(count_tests))
total_tests=${test_stats[0]:-0}
total_failures=${test_stats[1]:-0}
total_errors=${test_stats[2]:-0}
total_skipped=${test_stats[3]:-0}
total_passed=$((total_tests - total_failures - total_errors - total_skipped))

# Display test results summary
echo "📊 TEST EXECUTION SUMMARY:"
echo "   Tests Run: $total_tests"
echo "   ✅ Passed: $total_passed"
echo "   ❌ Failed: $total_failures"
echo "   🚫 Errors: $total_errors"
echo "   ⏭️  Skipped: $total_skipped"
echo ""

# Calculate code coverage from Jacoco CSV report
echo "📋 CODE COVERAGE SUMMARY:"

awk -F',' '
NR > 1 {
    inst_missed += $4
    inst_covered += $5
    branch_missed += $6
    branch_covered += $7
    line_missed += $8
    line_covered += $9
    complex_missed += $10
    complex_covered += $11
    method_missed += $12
    method_covered += $13
}
END {
    inst_total = inst_missed + inst_covered
    branch_total = branch_missed + branch_covered
    line_total = line_missed + line_covered
    complex_total = complex_missed + complex_covered
    method_total = method_missed + method_covered
    
    printf "   Instructions: %.2f%% (%d covered / %d total)\n", 
           (inst_total > 0 ? inst_covered/inst_total*100 : 0), inst_covered, inst_total
    printf "   Branches: %.2f%% (%d covered / %d total)\n", 
           (branch_total > 0 ? branch_covered/branch_total*100 : 0), branch_covered, branch_total
    printf "   Lines: %.2f%% (%d covered / %d total)\n", 
           (line_total > 0 ? line_covered/line_total*100 : 0), line_covered, line_total
    printf "   Complexity: %.2f%% (%d covered / %d total)\n", 
           (complex_total > 0 ? complex_covered/complex_total*100 : 0), complex_covered, complex_total
    printf "   Methods: %.2f%% (%d covered / %d total)\n", 
           (method_total > 0 ? method_covered/method_total*100 : 0), method_covered, method_total
}' build/reports/jacoco/aggregated/jacoco.csv

echo ""
echo "🏆 SUMMARY: $total_tests tests executed, $total_passed passed"
