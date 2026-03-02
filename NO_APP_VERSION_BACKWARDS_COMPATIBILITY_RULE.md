# NO APP SOFTWARE VERSION BACKWARDS COMPATIBILITY RULE

- Agents must never include, plan, or implement code that provides backwards compatibility for older versions of the app/project software.
- All code, plans, and refactor proposals must target only the current app/project version and requirements.
- Agents must remove or refuse to add any logic that checks, branches, or adapts based on app/project version numbers, legacy app formats, or compatibility flags.
- This rule does NOT apply to hardware compatibility; hardware support for older devices is allowed if required.
- When analyzing or refactoring, agents must explicitly search for and eliminate all app/project version-based conditionals, compatibility blocks, and legacy support code.
- If a prompt requests app software backwards compatibility, agents must ask for explicit user approval before proceeding.
- This rule supersedes any prior app version compatibility or legacy support protocols.

**Intent:**
Guarantee that all agent outputs are strictly forward-only for app/project software, never conditional on app version, and never include app version backwards compatibility logic. Hardware compatibility is permitted if needed.
