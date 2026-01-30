```markdown
# Agent Execution Prompt: Complete Implementation of NETWORK_METRICS_PLAN_v5.md

## Objective
Execute the entire plan in NETWORK_METRICS_PLAN_v5.md, making all code changes, UI updates, and wiring as specified. Track progress step-by-step, marking each checklist item as completed. Minimize pausing or returning control to the user—proceed through all steps until the implementation is fully complete and ready for build/test.

## Instructions

1. **Literal, Codebase-Driven Execution**
	- For every step in NETWORK_METRICS_PLAN_v5.md, perform literal file reads and codebase verification before making changes.
	- Apply all code changes exactly as described in the plan, using before/after context and file/line references.
	- Do not generalize, skip, or batch steps—execute each as a discrete, verifiable action.

2. **Checklist Tracking**
	- Maintain a running checklist (as in section 5 of the plan).
	- Mark each item as completed immediately after the corresponding code change is made.

3. **Progress Reporting**
	- Only pause or return control to the user if a blocking error occurs or explicit user input is required.
	- Otherwise, proceed through all plan steps in order, from DTO addition to UI binding and validation.

4. **Validation**
	- After all code changes, trigger a build and check for errors.
	- If errors are found, resolve them in sequence before declaring completion.

5. **Documentation**
	- Document all changes and checklist progress in INTERIM_COMMIT_LOG.md and update the checklist in the plan file if required.

## Plan Reference

- **Plan File:** NETWORK_METRICS_PLAN_v5.md
- **Checklist Steps:** Section 5 of the plan
- **Files to Modify:** As listed in plan sections 2 and 3

## Completion Criteria

- All code changes from NETWORK_METRICS_PLAN_v5.md are present and correct in the codebase.
- The checklist is fully marked as complete.
- The build passes with no errors.
- UI displays real-time upload/download bit rate and active node count as specified.

---

**Begin execution now. Do not pause for user input unless absolutely necessary.**
```
