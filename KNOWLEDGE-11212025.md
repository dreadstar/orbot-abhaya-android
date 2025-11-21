# KNOWLEDGE-11212025.md

## Date: 2025-11-21

### Summary of Modularization, Verification, and Rule Enforcement (2025-11-21)

#### Completed Tasks
- All outstanding TODOs in TaskManager.kt implemented and verified (recipient access, executor node address, file retrieval, container creation, output file storage, completion notification, secure memory zeroing).
- ServicePackageManager modularized: stubs for local testing workflow and local dev server, canonical workflow alignment, no errors.
- DistributedStorageManager modularized and verified, no outstanding TODOs or errors.
- Messaging, executors, and constants modularized and verified. All constants referenced from MeshrabiyaConstants.kt, outstanding TODOs implemented, no errors.

#### Canonical Constants Source
- All constants must be referenced from MeshrabiyaConstants.kt. No hardcoded or scattered constants allowed in messaging, executors, or related modules.

#### Import Style Rule (2025-11-21)
- Always use import + short name, never fully qualified notation. All imports verified for accuracy and placement.

#### Error Log Extraction Rule (Gradle/Kotlin)
- When extracting error-referenced files from build logs, include lines starting with 'e: ' as well as standard error/warning formats.

#### Knowledge Document Date Rule
- Always use the most recent KNOWLEDGE*.md file as authoritative if contradictions exist.

#### Commit Logging Protocol
- Each commit must document: files changed, features/bugs addressed, objectives/tests, and any TODOs generated or satisfied.

#### Completion Verification
- 100% of checklist items processed and verified. No partial measurements or incomplete samples accepted.

#### Next Steps
- Continue to enforce modularization, canonical constants, and import style rules for all future work.
- Update this document with any new rules, findings, or next steps as work progresses.

---

## Rules and Protocols (2025-11-21)
- See AGENTS.md for full operational protocols and rule documentation.
- All work must be research-driven, context-verified, and fully documented.
- No shortcuts or assumptions allowed; thoroughness required for all tasks.
