#!/bin/bash

# Calculate coverage from Jacoco CSV report
echo "=== PROJECT COVERAGE SUMMARY ==="

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
    
    printf "Instructions: %.2f%% (%d covered / %d total)\n", 
           (inst_total > 0 ? inst_covered/inst_total*100 : 0), inst_covered, inst_total
    printf "Branches: %.2f%% (%d covered / %d total)\n", 
           (branch_total > 0 ? branch_covered/branch_total*100 : 0), branch_covered, branch_total
    printf "Lines: %.2f%% (%d covered / %d total)\n", 
           (line_total > 0 ? line_covered/line_total*100 : 0), line_covered, line_total
    printf "Complexity: %.2f%% (%d covered / %d total)\n", 
           (complex_total > 0 ? complex_covered/complex_total*100 : 0), complex_covered, complex_total
    printf "Methods: %.2f%% (%d covered / %d total)\n", 
           (method_total > 0 ? method_covered/method_total*100 : 0), method_covered, method_total
}' build/reports/jacoco/aggregated/jacoco.csv
