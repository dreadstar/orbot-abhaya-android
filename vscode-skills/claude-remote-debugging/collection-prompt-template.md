# Collection Prompt Template
# Remote Debug Skill — references/collection-prompt-template.md
#
# Usage: copy this template, fill in every [PLACEHOLDER], delete this header.
# Deliver the filled template as a .md file via present_files.

---

# [SESSION_NAME]
## Remote Agent Data Collection Prompt

---

> **Instructions for use:** Paste everything below the horizontal rule
> into [REMOTE_AGENT_NAME]. The agent will collect the required source
> files and write all results to `[SESSION_NAME].md`.

---

## OUTPUT INSTRUCTION — DO THIS FIRST

Before collecting any files, create a new markdown file named:

```
[SESSION_NAME].md
```

Write ALL collected file contents into that file as you go.
The file must contain every line of every collected file verbatim.
Do not summarize, truncate, or paraphrase any file content.
Structure the file exactly as:

```
# [SESSION_NAME]

## FILE: <full/path/FileName.kt>
<complete file contents>

## FILE: <full/path/NextFile.kt>
<complete file contents>
```

When all files are collected, end the document with:

```
## COLLECTION COMPLETE
Total files collected: N
```

Write to the file continuously as you collect — do not batch at the end.

---

## DO NOT RE-COLLECT

The following files were already fully studied in a prior session and
must NOT be collected again:

<!-- List every file studied in previous sessions. If PT1, write "N/A — first session." -->
- `[PreviouslyStudiedFile1.kt]`
- `[PreviouslyStudiedFile2.kt]`

---

## CONFIRMED EVIDENCE — DO NOT RE-DERIVE

<!-- Populate from log grep results and prior session reports.
     Never leave this section blank. Every fact must cite its source. -->

**FACT 1** — [State what IS working / what data IS arriving correctly]
```
[Verbatim log line or confirmed behavior]
```
Source: [log tag / prior session report section]

**FACT 2** — [State what is NOT happening despite FACT 1]
```
[Verbatim log line showing absence, or "No log line for X ever appears"]
```

**FACT 3** — [Any other confirmed state: connection status, role, mode]
```
[Verbatim log line]
```

<!-- Add FACT N as needed. Keep facts atomic — one observation per fact. -->

---

## INVESTIGATION FOCUS

<!-- State the gap in one crisp paragraph. Name the producer, the missing
     link, and the consumer. Do not speculate beyond what the evidence shows. -->

**BUG 1 — [SHORT NAME]:** [DataProducer] emits [X] correctly (FACT 1)
but [DataConsumer] never reacts (FACT 2). The missing link: [describe
the code path that should connect them].

**BUG 2 — [SHORT NAME] (if applicable):** [Describe second bug using
same producer/consumer/missing-link structure.]

---

## COLLECT THESE FILES IN FULL — NO OTHERS

### 1. `[ClassName or filename]`
*(search path hint if location unknown)*

- Why needed: [one sentence — what specific question this file answers]
- Key methods/regions: [method names, field names, or search terms]

### 2. `[ClassName or filename]`
*(search path hint if location unknown)*

- Why needed: [one sentence]
- Key methods/regions: [method names, field names, or search terms]

### 3. `[ClassName or filename]`

- Why needed: [one sentence]
- Key methods/regions: [method names, field names, or search terms]

<!-- Add items up to a maximum of 8. If more are needed, plan a PTN+1 session. -->

---

## OUTPUT FORMAT

For each file, append to `[SESSION_NAME].md` using the format
specified at the top of this prompt.

**No analysis. No summaries. No commentary. Raw file contents only.**
