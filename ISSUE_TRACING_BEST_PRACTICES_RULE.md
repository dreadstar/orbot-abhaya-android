# ISSUE/PROBLEM AND CODE TRACING BEST PRACTICES RULE

## General Principles
- Always perform literal, end-to-end tracing of the full code path from user action (e.g., button press) to the final effect (e.g., UI update, notification, data write).
- Enumerate every function, method, and logic block involved in the lifecycle of the feature or bug under investigation.
- Use both static code analysis (reading code, searching for references, call graphs) and dynamic analysis (logging, breakpoints, stack traces, runtime inspection).
- Document every step, including all intermediate layers (UI, API, manager, handler, router, packet processor, etc.).
- Never assume the cause based on symptoms alone—always verify by tracing the actual code and data flow.
- For each step, record the file, function, and line number, and describe the logic and data transformations.
- Use automated tools (internal serach tools, IDE search, call hierarchy, code navigation, static analyzers) to ensure completeness.
- starting from the function containing  the effect at issue and moving backwards along the chain of cuncitions invovled, check all the uses of the functions to check those other uses as being invovled in the issue being investigated
- After identifying the function or code block where the effect or symptom occurs, perform a backward trace:
    - For each function involved, search the codebase for all usages (calls, references, invocations).
    - For every usage found, analyze whether it could contribute to or trigger the issue being investigated.
    - Repeat this process recursively for each upstream function, ensuring no possible path or trigger is missed.
    - Document each usage and its relevance to the issue, including file, function, and line number.
    - Continue until all possible sources and triggers for the effect are fully mapped and understood.
- Always check for indirect effects (side effects, callbacks, listeners, observers, background jobs).
- When in doubt, over-document rather than under-document.

## Kotlin/Java App Issue Tracing Strategy
- Start with the user-facing symptom or bug report.
- Identify the UI entry point (Activity, Fragment, View, or Composable) and trace all event handlers (e.g., setOnClickListener, setOnCheckedChangeListener).
- Follow the call chain through ViewModels, Presenters, or Controllers, noting all data/state propagation.
- Trace through all API/service calls, including asynchronous flows (coroutines, LiveData, StateFlow, RxJava, callbacks).
- For each function, check for:
  - Direct calls
  - Indirect triggers (observers, listeners, event buses)
  - Background/worker threads
- Use logging and breakpoints to confirm runtime execution order and data values.
- For notification, broadcast, or event-driven features, enumerate all registration and dispatch points (e.g., registerListener, addObserver, subscribe).
- Always check for legacy or compatibility code that may alter the flow (but see project-specific rules for compatibility handling).
- Document all findings in a stepwise, reproducible format, suitable for peer review.

## Industry Best Practices
- Use version control to track all changes and facilitate blame/annotate for historical bug tracing.
- Write and maintain automated tests to catch regressions and verify fixes.
- Use code review and pair programming to catch missed paths and logic errors.
- Prefer explicit, readable code over clever but opaque logic.
- Keep documentation and tracing artifacts up to date with code changes.

**Intent:**
Ensure all agents and developers follow a rigorous, industry-standard approach to issue tracing and code path analysis, with special focus on Kotlin/Java app workflows.
