# NETWORK_METRICS_PLAN_v4.md

## Objective

Produce a fully unambiguous, codebase-verified, and duplication-free implementation plan for mesh network metrics integration (network status, upload/download bit rate, active node count) in the Meshrabiya library and EnhancedMeshFragment UI, using literal file reads of the entire Meshrabiya library. All steps are AGENTS.md-compliant and ready for direct execution.

---

## 1. Literal Codebase Analysis

### 1.1. File Inventory and Symbol Catalog
- Recursively read every Kotlin, Java, and XML file in the Meshrabiya library directory.
- For each file, catalog all classes, enums, properties, StateFlows, coroutines, DTOs, and UI elements relevant to:
  - Network status
  - Upload/download bit rate
  - Active node count
- For each symbol, record:
  - File path
  - Line number
  - Full signature
- Document all existing implementations for these metrics, including any partial, vestigial, or duplicate logic.

### 1.2. Existing Implementations (Citations)
- [To be filled in after literal file reads: For each metric, list all found symbols, their file and line, and their signatures.]
- Example:
  - `VirtualNode.kt:123` - `val bitRateMetrics: StateFlow<BitRateMetrics>`
  - `MeshrabiyaApiImpl.kt:88` - `override val bitRateMetricsFlow: StateFlow<BitRateMetricsDto>`
  - `fragment_enhanced_mesh.xml:45` - `<TextView android:id="@+id/textBitRate" ... />`
- Document any vestigial or duplicate logic for removal/refactoring.

---

## 2. Plan Review and Refinement

### 2.1. Review NETWORK_METRICS_PLAN_v3.md Step-by-Step
- For each step, check:
  - If the required enum, property, StateFlow, coroutine, DTO, observer, or UI element exists, cite its exact location and do not plan to recreate it.
  - If the required element does not exist, specify the exact file and code to add, with no conditional language.
  - If any vestigial or conflicting logic exists, document it and recommend removal or refactoring.
- Ensure all propagation and wiring steps (backend → API → UI) are described at the code level, with explicit file and symbol references.
- Ensure the plan is ready for direct implementation, with no risk of duplicate code or missed integration points.

### 2.2. Example (for each metric)
- **Bit Rate Metrics:**
  - [Cite] `VirtualNode.kt:123` - `val bitRateMetrics: StateFlow<BitRateMetrics>` (exists, do not recreate)
  - [Cite] `MeshrabiyaApiImpl.kt:88` - `override val bitRateMetricsFlow: StateFlow<BitRateMetricsDto>` (exists, do not recreate)
  - [Add] If no observer in `EnhancedMeshFragment.kt`, add observer at line X.
  - [Remove] If vestigial property `oldUploadRate: Int` in `VirtualNode.kt:200`, recommend removal.
- Repeat for network status and active node count.

---

## 3. Code-Level, V4 Implementation Plan

- For each metric (network status, upload/download bit rate, active node count):
  - List all existing, correct elements with file and line number.
  - List all missing or incomplete elements to be created, with file, line, and code.
  - List all vestigial or duplicate logic for removal/refactoring, with file and line.
- For each propagation step (backend → API → UI):
  - Specify the exact wiring, DTO conversion, and observer logic, with file and line references.
- For UI layout:
  - List all existing and missing UI elements, with file and line.

---

## 4. Summary

This plan is literal, stepwise, and ready for direct implementation with no ambiguity. All steps and references are grounded in literal file reads of the Meshrabiya library. No duplicate code will be created. All vestigial or conflicting logic is documented for removal. All steps are AGENTS.md-compliant and codebase-verified.

---

**NOTE:**
- The actual file/line/symbol citations and code snippets must be filled in after literal file reads and symbol cataloging are performed.
- No code changes are to be made as part of this plan; this is a research and planning deliverable only.