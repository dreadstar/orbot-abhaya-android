## KNOWLEDGE Document Date Rule
Before updating or creating a KNOWLEDGE document, check the current date to be certain which KNOWLEDGE*.md file you should be using. Always use the most recent KNOWLEDGE*.md file (by date in the filename) as the authoritative source if there are contradictions.
- Agents must NEVER use likely locations (guesses) for code references, imports, or types when the exact answer can be determined by reviewing the codebase or researching online. Always verify and use the true, precise location. Laziness or shortcuts in this regard are strictly prohibited.

# AGENTS.md - Operational Protocols for AI Agents

**Purpose:**
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
- For file reading, always read the entire file for logs and outputs, never partial sections.
- For code edits, always read context before action (minimum 3-5 lines before and after target code).
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

**This document supersedes all previous agent instructions. All agents must operate according to these protocols.**
