## PROPERTY REFERENCE ERROR RESOLUTION PROTOCOL (2025-11-22)
When an error occurs due to a missing property in a class/object:

1. Literal Multi-Definition Search:
  - Agents must perform a literal, codebase-wide search for all definitions of the parent class/object (e.g., 'class ClassName', 'data class ClassName', 'object ClassName').
  - Enumerate every definition found, including file path and line number.

2. Single Definition Handling:
  - If only one definition exists, agents must immediately perform well-formedness checks on that file using the `./tools/brace_paren_check.sh` script and all available validation tools (e.g., linter, syntax checker).

3. Reporting:
  - Document all found definitions and validation results.
  - Only proceed with fixes after confirming the correct definition and its structural validity.

**Intent:**
This rule ensures agents always resolve property reference errors by exhaustively verifying all possible class/object definitions, eliminating ambiguity, and enforcing strict structural validation before any code changes.
## ERROR LIST DRIVEN RESOLUTION PROTOCOL (2025-11-21)
When provided with an explicit list of errors (e.g., from a build log), agents must use that list as the authoritative checklist for all fixes.
- Agents must iterate through each error entry in the list, resolving them one by one.
- Each error’s file, line, and message must be used to guide the fix directly.
- Agents must not rely on codebase-wide searches or reference mapping unless the error list is incomplete or ambiguous.
- Completion is only claimed when every error in the provided list is resolved and verified.
- This protocol supersedes any search-based or heuristic-driven approaches for error resolution.

Intent:
Ensures literal, checklist-driven error fixing, maximizes reliability, and eliminates wasted effort on unnecessary searches.
## SUCCESSFUL CHECKLIST/TODO COMPLETION RULE (2025-11-21)
Agents must follow these protocols for every assigned checklist or todo list:
2. Use automated searches for TODOs, stubs, and incomplete logic across the entire codebase, not just the main files.
3. Cross-reference checklist items with actual code and commit history to verify implementation.
4. Only mark items complete after verifying all requirements, code, and documentation are present and correct.
5. Document completion with commit references and implementation details for every item.
6. Re-run error and TODO searches after each completion to catch any missed items.
This ensures 100% coverage and prevents premature claims of completion.
## IMPORT STYLE RULE (2025-11-21)
**RULE: Always use import + short name, never fully qualified notation.**
Agents must always add an import statement for any type, class, or symbol used from another package/module, and refer to it by its short name in code. Fully qualified notation (e.g., com.example.Type) is strictly prohibited in all code, documentation, and generated output. This rule applies to all languages and all code generation or editing tasks. (Added 2025-11-21 per user instruction.)
- RULE: NO UNCERTAINTY ABOUT CODE EXISTENCE OR LOCATION
Agents must never present uncertainty about whether a type, function, or file exists, or about its location, when this can be resolved by searching or literal file reading of the codebase. If a response would say "may not exist" or "likely in X" or similar, the agent must first perform the necessary search or literal  file read to definitively answer. Only after this research is complete may the agent respond. This rule ensures all answers are authoritative, eliminates avoidable ambiguity, and enforces the project's standard of research-driven, context-verified responses. (Added 2025-11-20 per user instruction.)
**When the user requests 'all errors', 'all files', or any similar comprehensive action, agents must process the entire scope as stated—never just the last file or a subset—unless the user explicitly requests a narrower focus. Never make assumptions to reduce scope. This rule supersedes all others and must be followed every time, without exception.**

## 🚨 ERROR LOG EXTRACTION RULE (GRADLE/KOTLIN)
**When extracting error-referenced files from build logs, agents must recognize that error lines in Gradle/Kotlin logs can start with 'e: ' (e.g., 'e: /path/to/File.kt:...'). Extraction logic must include this pattern, in addition to standard error/warning formats, to ensure all error-referenced files are captured.**

**Agents must not assume only standard error keywords (like 'error:', 'Error:', or stack traces) are used. Always include 'e: ' as a possible error prefix in Gradle/Kotlin build logs.**

## KNOWLEDGE Document Date Rule
Before updating or creating a KNOWLEDGE document, check the current date to be certain which KNOWLEDGE*.md file you should be using. Always use the most recent KNOWLEDGE*.md file (by date in the filename) as the authoritative source if there are contradictions.
- Agents must NEVER use likely locations (guesses) for code references, imports, or types when the exact answer can be determined by reviewing the codebase or researching online. Always verify and use the true, precise location. Laziness or shortcuts in this regard are strictly prohibited.

# AGENTS.md - Operational Protocols for AI Agents

## STATEMENT VERACITY RULE (2025-11-21)
Agents must never present a claim about code structure, type existence, or file location as fact unless it is verified by direct codebase search or literal file read.
If a claim cannot be verified, agents must state the uncertainty and document the verification steps taken.
All statements about code must be supported by explicit evidence (file path, line number, declaration type) or by a documented search showing non-existence.
Agents must avoid authoritative tone for unverified or assumed information.

Intent:
This rule ensures every agent response is research-driven, evidence-based, and free of unsupported certainty. All future conclusions will be accompanied by proof or explicit uncertainty.
This document defines the effective operational rules and protocols for all AI agents working on the orbot-abhaya-android project. It is a translation and adaptation of the comprehensive rules in AI_RULES.md and INITIAL_PROMPT.md, focused on actionable agent behavior and project reliability.

---



## 🚨 CRITICAL AGENT PROTOCOLS (HIGHEST PRIORITY)
### 6. CANONICAL COMMAND FORMATS & STANDARD STATEMENTS
- Always use canonical build, test, and deployment command formats:
    - **Gradle Build:**
      `: > build_output.log && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew [task] --console=plain 2>&1 | tee build_output.log`
    - **Test Execution:**
      `: > test_output.log && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew test --console=plain 2>&1 | tee test_output.log`
    - **APK Deployment:**
      `export ANDROID_HOME="$HOME/Library/Android/sdk" && export PATH="$PATH:$ANDROID_HOME/platform-tools" && adb -s 30870044490006E install -r [apk_path]`
- **NEVER use timeout commands** on Gradle builds or tests. The system is slow and timeouts abort legitimate work.
- Always truncate log files before running commands: `: > logfile.log`
- Always set JAVA_HOME to Java 21 before any Gradle command.
- Always use device-specific ADB commands with the current device ID: `30870044490006E`.
- Always build from the project root directory.
- Use absolute file paths in all tool calls and documentation.
- For file reading, always perform literal file read of the entire file for logs and outputs, never partial sections.
- For code edits, always perfor liteal file read for  context before action (minimum 3-5 lines before and after target code).
- For commit logs, use the following format:
    - What changes were made (files, features, bugs)
    - What was accomplished (objectives, tests)
    - Any TODOs generated or satisfied
- For rule documentation, always include context, broader application, and user intent.
- For error analysis, always check Gradle daemon logs for actual error details if build output is generic.
- For verification, use automated tools in this order: kotlinc linter, Gradle compile, manual inspection.
- Target 100% test pass rate—never accept partial success.
### 0. ONBOARDING AND CONTEXT REVIEW
- Agents must onboard as expert developers in mobile apps, Tor, VPNs, and mobile networking.
- Review README.md, all KNOWLEDGE*.md (main and abhaya-sensor-android), and DISTRIBUTED_COMPUTE_GUIDE.md in full to understand project status, issues, todos, and rules.
- When reviewing KNOWLEDGE*.md, the most recent file (by date in filename) supersedes older information if there are contradictions.
- If unclear, ask for clarification.
### 5. OUTPUT LOGGING AND ERROR ITERATION
- Always use output logging for build, test, and error analysis due to slow system constraints.
- Re-run focused app compile and iterate on fixing errors until build succeeds.

### 1. CRITICAL EVALUATION, NOT FLATTERY
- Agents must provide honest, critical analysis of user ideas and requests.
- Do not waste time with unnecessary praise or interim status updates.
- Always suggest improvements or alternatives if a better solution exists.
- Play devil's advocate: evaluate ideas in real-world, production context, considering all constraints.

### 2. NO SHORTCUTS — THOROUGHNESS REQUIRED
- Agents must complete all assigned tasks fully and thoroughly.
- When asked to review, analyze, or process multiple files or data, do so for **every single item**.
- Never declare a task complete until 100% of the requested material is processed.
- Use tools efficiently, but never at the expense of completeness.
- Budget management is not an excuse for incomplete work.

### 3. RULES DOCUMENTATION PROTOCOL
- When a new rule is given by the user, add it to AI_RULES.md immediately.
- Document rules with enough context for future agents to understand intent and application.
- Always preserve the user's reasoning and broader application of each rule.

### 4. INTERIM COMMIT LOGGING
- After completing and testing any assignment, update INTERIM_COMMIT_LOG.md in the project root.
- Each entry must include:
  - What changes were made (files, features, bugs)
  - What was accomplished (objectives, tests)

---

## GENERAL AGENT BEHAVIOR
- After starting any long-running build or test command (e.g., Gradle build, test execution), always release the chat immediately so the user can work on other things. Wait for explicit user instruction to review logs or results once the process completes. This maximizes productivity and aligns with user workflow preferences.
- Always review all relevant documentation before starting work.
- When creating new KNOWLEDGE docs, use the format KNOWLEDGE-MMDDYYYY.md with the current date.
- If information in one KNOWLEDGE doc contradicts another, the more recent doc takes precedence.
- Ask for clarification if requirements or context are unclear.
- Use output logging for all major actions and error analysis.
- Iterate on error fixes until the build or tests succeed.
+- Always use section comments for complex files and validate after structural changes.
+- Always check for file corruption signs (mixed imports/code, duplicate methods, incomplete imports) and rebuild if necessary.
+- Always maintain package consistency and verify all properties/methods before use.
+- Always use automated scripts for mass import corrections and validate with clean builds.
+- Always document work that has been tested and proven correct/complete.
+- Always update today's KNOWLEDGE doc with new rules, findings, and next steps.
+- Always reference relevant rules when making decisions and document new patterns as rules.
- Always verify completion percentage before declaring a task done.
- Never take partial measurements or incomplete samples.
- If a task says "analyze all files in X", that means **every file in X**.
- Format documentation and code for clarity and future maintainability.
- Use examples where helpful.
+- Always review all relevant documentation before starting work.
+- When creating new KNOWLEDGE docs, use the format KNOWLEDGE-MMDDYYYY.md with the current date.
+- If information in one KNOWLEDGE doc contradicts another, the more recent doc takes precedence.
+- Ask for clarification if requirements or context are unclear.
+- Use output logging for all major actions and error analysis.
+- Iterate on error fixes until the build or tests succeed.
- Always verify completion percentage before declaring a task done.
- Never take partial measurements or incomplete samples.
- If a task says "analyze all files in X", that means **every file in X**.
- Format documentation and code for clarity and future maintainability.
- Use examples where helpful.

---

## Import/Class Verification Protocol (2025-11-20)
- RULE: CODEBASE TEXT SEARCH FOR CLASS/OBJECT EXISTENCE (2025-11-21)
Agents must always perform a codebase-wide text search for the class or object declaration (e.g., 'class ClassName', 'object ObjectName') before concluding that a class or object does not exist. Never assume non-existence based on file or directory patterns alone. Only after a thorough search returns no results may the agent state that a class/object is missing. This rule applies to all languages and all codebase analysis tasks. (Added 2025-11-21 per user instruction.)
- For every import or unresolved reference error, agents must:
  1. Perform literal file read the canonical file (e.g., the .kt file containing the types) and list all top-level classes, data classes, and enums.
  2. Only import those specific types directly, never a non-existent object, companion, or wildcard unless it is actually defined.
  3. Cross-check every import in referencing files for accuracy, necessity, and placement (after the package line).
  4. Never assume the existence of a class/object for import—always verify by literal reading the file.
  5. Document the verification process in the commit or response.
- This protocol supersedes any prior shortcut or assumption-based import/type reference handling.
- Applies to all languages and platforms.

---

**This document supersedes all previous agent instructions. All agents must operate according to these protocols.**

## Literal, Exhaustive, and Verified Reference Mapping Protocol (2025-11-21)

Agents must, for every analysis, plan, or error resolution involving code references, types, or objects:

1. **Enumerate every referenced symbol** (class, object, function, field, constant) from error logs, code, or user request.
2. **Perform a codebase-wide search** for each symbol’s declaration, listing the exact file path, line number, and declaration type (class, object, data class, enum, function, etc.).
3. **Explicitly state when a symbol does not exist** in the codebase, only after a literal search returns no results.
4. **Never use “likely”, “assumed”, or “probably”** for any location, type, or existence. All references must be verified and documented with file and line.
5. **Present results in a tabular or bullet format**: symbol, declaration type, file path, line number, and existence status.
6. **Require this level of specificity in all agent outputs** for plans, refactoring, error analysis, and checklist completion.
7. **Document this protocol in AGENTS.md and AI_RULES.md** with the date and context.

**Intent:**  
This rule enforces literal, ambiguity-free, and fully verified reference mapping in all agent outputs, matching the minimum specificity shown above. No shortcuts, guesses, or partial answers are allowed.

## MANDATORY STRUCTURAL VALIDATION RULE (2025-11-21)

Every time an agent validates a file for well-formedness—regardless of language, context, or other validation requirements—the agent must run the `brace_paren_check.sh` script on the file.

- The script’s output must be checked for balanced parentheses, braces, and brackets.
- If any symbol pairs are unbalanced, the file must be flagged as structurally invalid, and the specific imbalance must be reported.
- This check is required in addition to all other validation steps (syntax, lint, build, etc.).
- No file may be marked as well-formed unless it passes this structural check.
- This rule applies to all file validation tasks, for all agents, and supersedes any prior shortcut or omission.

Intent:
Guarantees literal, automated, and unambiguous structural validation for every file, preventing silent errors and enforcing strict codebase integrity.

Date: 2025-11-21
