# MERGE_FIX_PROCESS.md
## Purpose

This document provides a step-by-step, replicable protocol for agents to restore missing functions, classes, and interfaces during modular refactoring of Kotlin files. It is designed to be detailed enough for any agent to apply the process to a different set of files, ensuring no code is lost and all work is auditable.

---

## Step 1: Preparation & Extraction

### 1.1. Identify Source and Refactored Files
- **Source file:** The original file containing all code elements (functions, classes, interfaces).
- **Refactored file:** The modular or rewritten file that may have missing code elements.
- **Temp files:** Copies of both files with `.kt` extension for reliable script processing.

### 1.2. Copy Files Using Shell
- Use shell `cp` to avoid agent-side file write inconsistencies:
```bash
cp <source_file> <temp_source_file>
cp <refactored_file> <temp_refactored_file>
```
- Example:
```bash
cp Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/IntelligentDistributedComputeService.md compute_fix_working/IntelligentDistributedComputeService_mdcopy.kt
cp compute_fix_working/IntelligentDistributedComputeService_refactored.tmp compute_fix_working/IntelligentDistributedComputeService_refactored.kt
```

### 1.3. Extract Code Elements
- Use a targeted extraction script to list all functions, classes, and interfaces in each file.
- **Script:** `extract_kotlin_functions_single.sh`
```bash
#!/bin/bash
# Usage: ./extract_kotlin_functions_single.sh <input_file> <output_file>
INPUT_FILE="$1"
OUTPUT_FILE="$2"
: > "$OUTPUT_FILE"
grep -E '^[[:space:]]*(class|interface|object|fun)[[:space:]]' "$INPUT_FILE" >> "$OUTPUT_FILE"
```
- Run extraction:
```bash
bash compute_fix_working/extract_kotlin_functions_single.sh compute_fix_working/IntelligentDistributedComputeService_mdcopy.kt compute_fix_working/original_functions_single.txt
bash compute_fix_working/extract_kotlin_functions_single.sh compute_fix_working/IntelligentDistributedComputeService_refactored.kt compute_fix_working/refactored_functions_single.txt
```

---

## Step 2: Comparison & Logical Grouping

### 2.1. Compare Extracted Lists
- Use `diff` or manual inspection to compare the lists of code elements.
- Identify which functions/classes/interfaces are present in the source but missing in the refactored file.
- Example:
```bash
diff compute_fix_working/original_functions_single.txt compute_fix_working/refactored_functions_single.txt
```

### 2.2. Logical Grouping
- Group missing code elements by their logical association with the file/module being refactored.
- Only restore elements that belong to the current file/module.
- Document the mapping for traceability.

---

## Step 3: Restoration & Verification

### 3.1. Restore Missing Code
- For each missing function/class/interface, locate the full code block in the source file.
- Insert the code block into the refactored file at the appropriate location.
- Use agent patch/edit tools or shell-based append/insert as needed.

### 3.2. Re-Extract and Validate
- Re-run the extraction script on the updated refactored file.
- Confirm that all required code elements are now present.
- Example:
```bash
bash compute_fix_working/extract_kotlin_functions_single.sh compute_fix_working/IntelligentDistributedComputeService_refactored.kt compute_fix_working/refactored_functions_single.txt
```
- Compare again with the source list.

---

## Step 4: Final Validation

### 4.1. Comprehensive Extraction
- Use the original full-directory extraction script to validate all files in the module.
- **Script:** `extract_kotlin_functions.sh`
```bash
#!/bin/bash
COMPUTE_DIR="<module_dir>"
OUTPUT_FILE="<output_file>"
: > "$OUTPUT_FILE"
find "$COMPUTE_DIR" -type f -name "*.kt" | while read file; do
    echo "# $file" >> "$OUTPUT_FILE"
    grep -E '^(class |interface |object |fun )' "$file" >> "$OUTPUT_FILE"
    echo >> "$OUTPUT_FILE"
done
```
- Run extraction and review results for completeness.

---

## Step 5: Documentation & Audit Trail

### 5.1. Document Each Step
- Record all shell commands, scripts, and decisions in this file.
- Note the rationale for each restoration and grouping.
- Update the document as new files/groups are processed.

---

## Rationale for Each Step
- **Shell copy:** Ensures file contents are reliably transferred, avoiding agent-side write errors.
- **Extraction scripts:** Provide auditable lists of code elements for comparison and validation.
- **Logical grouping:** Prevents restoration of unrelated code, keeping modular files clean.
- **Incremental verification:** Ensures no code is lost and all work is traceable.
- **Documentation:** Enables future agents to replicate the process and maintain project integrity.

---

## Example Workflow Summary
1. Copy source and refactored files to temp `.kt` files using `cp`.
2. Extract code elements from both files using the single-file extraction script.
3. Generate weighted, non-overlapping mapping using `generate_functions_mappings2.sh`.
4. Compare lists and identify missing items.
5. Restore missing code blocks to the refactored file, only for elements assigned to the current group.
6. Re-extract and validate restoration.
7. Use full-directory extraction for final validation and consistency with mapping.
8. Document all steps and decisions in this file.

---
This protocol should be followed for each modular file in the refactoring process. Update this document with new findings, scripts, and decisions as the project evolves.
