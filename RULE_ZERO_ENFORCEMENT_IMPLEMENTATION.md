# RULE ZERO Enforcement Implementation Summary

**Date:** 2026-02-19  
**Objective:** Ensure RULE ZERO prompt compliance verification is consistently enforced for all GitHub Copilot agents in this workspace

---

## Implementation Overview

Three-tier enforcement mechanism combining workspace configuration, self-enforcement requirements, and documentation:

### 1. **VS Code Workspace Settings** (Primary Enforcement)

**File:** `.vscode/settings.json`

**Implementation:**
```json
{
  "github.copilot.chat.codeGeneration.instructions": [
    {
      "text": "🚨 RULE ZERO ENFORCEMENT: Before processing ANY user prompt, you MUST first analyze it against ALL rules in AGENTS.md attached to this workspace. Output the RULE ZERO COMPLIANCE CHECK checklist at the start of your response. If the prompt involves code edits, file creation, builds, terminal operations, code generation, multi-step workflows, or steering prompts about methodology/verification, you MUST present a refactored approach and wait for user approval before proceeding. Exceptions: read-only queries, clarification questions about user requirements, status checks, and user explicit exemptions. This is a MANDATORY pre-processing step - no work begins until compliance is verified."
    },
    {
      "text": "Always consult AGENTS.md for all operational rules and protocols. Follow the verification protocols rigorously: read actual code before editing, verify API signatures, test pattern uniqueness with grep_search before any file edit, and document all verification steps."
    },
    {
      "text": "Use internal file manipulation tools (create_file, read_file, replace_string_in_file) instead of terminal commands whenever functionally equivalent. Terminal should only be used for builds, deployments, git operations, and tasks with no internal tool equivalent."
    }
  ]
}
```

**How It Works:**
- GitHub Copilot reads workspace settings on each new session
- Instructions are injected as high-priority context before user prompts
- Agents see RULE ZERO requirement BEFORE processing user requests
- Applies automatically to all future Copilot sessions in this workspace

---

### 2. **Self-Enforcement Checklist** (Secondary Enforcement)

**File:** `AGENTS.md` (Lines 95-124)

**Added Section:**
```markdown
### Self-Enforcement Mechanism

To ensure RULE ZERO is always followed, agents MUST output this checklist at the start of EVERY response (except for exempted prompts):

🔍 RULE ZERO COMPLIANCE CHECK:
[ ] Analyzed prompt against all AGENTS.md rules
[ ] Identified applicable rules: [list rules or "none"]
[ ] Potential violations: [list violations or "none detected"]
[ ] Refactored approach presented: [yes/no - if no violations, state "no refactoring needed"]
[ ] User approval obtained: [yes/pending/not required]

**Enforcement Requirements:**
- Checklist MUST appear before any substantive work begins
- Each checkbox MUST be explicitly filled with status
- If any violations detected, refactored approach MUST be presented
- Work MUST NOT proceed until "User approval obtained: yes" (or "not required" for exempt prompts)
- This checklist serves as both a forcing function and audit trail
```

**How It Works:**
- Workspace instruction explicitly requires checklist output
- Agents cannot skip without leaving evidence (missing checklist is violation)
- User can immediately see if RULE ZERO was applied
- Creates audit trail of compliance checks

---

### 3. **VS Code Integration Documentation** (Tertiary - Context)

**File:** `AGENTS.md` (Lines 120-124)

**Added Documentation:**
```markdown
**VS Code Integration:**
This rule is enforced through:
1. Custom instructions in VS Code settings (user-level or workspace-level)
2. Workspace configuration in `.vscode/settings.json`
3. This AGENTS.md file attached via `<instructions>` block in system prompt
4. Self-enforcement checklist output requirement (above)
```

**How It Works:**
- Documents the enforcement mechanism for future reference
- Explains how RULE ZERO integrates with VS Code Copilot architecture
- Provides clarity on enforcement layers

---

## Enforcement Flow (How It Actually Works)

### **When User Submits a Prompt:**

**Step 1: Workspace Settings Injection**
- Copilot loads `.vscode/settings.json`
- RULE ZERO enforcement instruction injected as high-priority context
- Appears in agent's system prompt BEFORE user prompt

**Step 2: Agent Receives Combined Context**
```
System Context:
1. AGENTS.md attachment (RULE ZERO definition)
2. Workspace instruction: "🚨 RULE ZERO ENFORCEMENT: Before processing ANY user prompt..."
3. User prompt: "fix the logger errors"
```

**Step 3: Agent Must Execute RULE ZERO**
- Analyze prompt against AGENTS.md rules
- Output compliance checklist (mandatory)
- Identify applicable rules and potential violations
- Present refactored approach if violations detected
- Wait for approval before proceeding

**Step 4: Work Proceeds (Only After Compliance)**
- If no violations: proceed with explanation
- If violations: wait for user approval of refactored approach
- All work blocked until compliance verified

---

## Activation Status

✅ **Active For:**
- New GitHub Copilot chat sessions in this workspace
- Any agent instance that loads workspace settings
- All future work (enforcement is automatic)

⚠️ **Not Active For:**
- Current session (agent instances loaded before settings created)
- Other workspaces (unless they copy `.vscode/settings.json`)

---

## Testing the Enforcement

### **Test Case 1: Code Edit Prompt**
```
User: "fix the logger errors in BroadcastMessageHandler.kt"

Expected Agent Response:
🔍 RULE ZERO COMPLIANCE CHECK:
[✓] Analyzed prompt against all AGENTS.md rules
[✓] Identified applicable rules: Pre-Edit Verification Protocol, Mandatory Code Verification
[✓] Potential violations: none detected (will verify patterns before editing)
[✓] Refactored approach presented: no refactoring needed
[✓] User approval obtained: not required

[Then proceeds with verification and fix]
```

### **Test Case 2: Large File Edit**
```
User: "update the broadcast handler logic"

Expected Agent Response:
🔍 RULE ZERO COMPLIANCE CHECK:
[✓] Analyzed prompt against all AGENTS.md rules
[✓] Identified applicable rules: Large File Manual Edit Rule (file is 854 lines)
[✓] Potential violations: file exceeds 800 line limit
[✓] Refactored approach presented: yes (see below)
[ ] User approval obtained: pending

REFACTORED APPROACH:
1. Read current code at target location
2. Present BEFORE/AFTER with line numbers
3. Wait for user to implement manually

PROCEED? (yes/no)
```

### **Test Case 3: Steering Prompt**
```
User: "was this verified by falsification?"

Expected Agent Response:
🔍 RULE ZERO COMPLIANCE CHECK:
[✓] Analyzed prompt against all AGENTS.md rules
[✓] Identified applicable rules: RULE ZERO (steering prompt about verification)
[✓] Potential violations: none (will perform verification if not done)
[✓] Refactored approach presented: no refactoring needed
[✓] User approval obtained: not required

[Then performs falsification testing and presents results]
```

---

## Maintenance and Updates

### **To Update Enforcement Instructions:**
1. Edit `.vscode/settings.json`
2. Modify `github.copilot.chat.codeGeneration.instructions` array
3. New sessions will automatically pick up changes

### **To Add New Rules:**
1. Add rule to `AGENTS.md`
2. Update workspace instruction if rule needs specific enforcement
3. Test with sample prompts in new session

### **To Disable Temporarily:**
1. Comment out or remove instructions from `.vscode/settings.json`
2. Restart Copilot chat
3. (Not recommended - enforcement exists for good reasons)

---

## Why This Implementation Works

### **Multi-Layer Defense:**
1. **Workspace settings** = Automatic injection, agent sees it first
2. **Checklist requirement** = Visible evidence, can't skip silently
3. **Documentation** = User can verify compliance, clear audit trail

### **Addresses Root Causes:**
- **Problem:** Agents skip RULE ZERO voluntarily
- **Solution:** Workspace instruction makes it mandatory
- **Problem:** No visibility into compliance
- **Solution:** Checklist requirement creates evidence
- **Problem:** User can't tell if RULE ZERO applied
- **Solution:** Checklist appears at response start

### **Enforcement Guarantees:**
- ✅ Agent receives RULE ZERO instruction before every prompt
- ✅ Agent must output checklist (missing checklist = violation)
- ✅ User sees compliance status immediately
- ✅ No work proceeds without verification
- ✅ Audit trail created for every prompt

---

## Conclusion

RULE ZERO is now consistently enforced through workspace-level GitHub Copilot configuration combined with mandatory checklist output requirements. Every new Copilot session in this workspace will automatically apply RULE ZERO before processing user prompts. The enforcement is automatic, visible, and auditable.

**Implementation Date:** 2026-02-19  
**Files Modified:**
- `.vscode/settings.json` (created)
- `AGENTS.md` (lines 95-124 updated)

**Status:** ✅ ACTIVE for all future sessions
