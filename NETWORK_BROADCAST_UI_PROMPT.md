# NETWORK_BROADCAST_UI_IMPLEMENTATION_PROMPT

**Date:** 2026-02-01  
**Agent Task:** Implement UI layer for broadcast message+file feature  
**Output Document:** NETWORK_BROADCAST_UI_PLAN_v1.md  

---

## BACKGROUND CONTEXT

The Meshrabiya library broadcast feature has been **fully implemented** at the API level (NETWORK_BROADCAST_v2.md). The following components exist and are verified to compile:

### Existing API (Implemented):
```kotlin
// File: Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt
fun broadcastMessageAndFile(
    messageText: String,
    filePath: String,
    callback: (Result<BroadcastResultDto>) -> Unit
)
fun registerBroadcastListener(listener: (BroadcastReceivedDto) -> Unit)
fun unregisterBroadcastListener(listener: (BroadcastReceivedDto) -> Unit)
```

### Existing DTOs (Implemented):
```kotlin
// File: Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/ext/BroadcastDtos.kt
data class BroadcastResultDto(
    val broadcastId: String,
    val messageText: String,
    val fileId: String,
    val fileName: String,
    val totalChunks: Int,
    val sentAt: Long
)

data class BroadcastReceivedDto(
    val broadcastId: String,
    val messageText: String,
    val fileId: String,
    val fileName: String,
    val filePath: String,
    val senderNodeId: Int,
    val receivedAt: Long
)
```

### Drop Folder Management (Implemented):
```kotlin
// File: Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt
fun selectDropFolder(path: String, callback: (Result<Unit>) -> Unit)
fun getDropFolder(): File?
```

**Status:** All library code compiles successfully. Ready for UI integration.

---

## TASK REQUIREMENTS

You are an expert Android/Kotlin developer tasked with implementing the UI layer for the broadcast feature in the Orbot main app. Your task is to **analyze the existing codebase thoroughly** and create a **comprehensive, code-verified implementation plan**.

### Primary Goals:

#### 1. Add "Send Broadcast" Button to Mesh Tab
- **Location:** Next to existing "Refresh Status" button
- **Initial State:** Disabled
- **Enabled When:** Network Status == "CONNECTED"
- **Must Verify:**
  - Exact file path of Mesh tab UI component
  - Current button layout structure (XML or Compose)
  - How network status is observed in UI
  - Existing button styling patterns

#### 2. Implement Broadcast Dialog
When "Send Broadcast" button is clicked, open a dialog with:
- **Message Input:** Text area for optional message (max 500 chars per API validation)
- **File Selector:** Button to pick optional file using Android file picker
- **Send Button:** 
  - Enabled only if: message is not empty OR file has been selected
  - On click: Call `broadcastMessageAndFile()` API
  - Show progress indicator during send
  - Close dialog on success
  - Show error message on failure (stay open)
- **Cancel Button:** Close dialog without sending

**Must Verify:**
- Existing dialog patterns in the app (Material, custom, Compose Dialog)
- File picker implementation patterns used elsewhere
- Form validation patterns
- Error display patterns
- Loading state patterns

#### 3. Refactor API to Use Coroutines and Event Handlers
**Current API:** Uses callback pattern
```kotlin
fun broadcastMessageAndFile(
    messageText: String,
    filePath: String,
    callback: (Result<BroadcastResultDto>) -> Unit
)
```

**Required Changes:**
- Convert to suspend function (no callback parameter)
- Success results returned via registered success handler
- Failures returned via registered failure handler
- Implementation runs as coroutine
- messageText and FilePath both can be optional but if both are null or empty function should trigger registered failure handler 

**Must Verify:**
- Existing event handler patterns in MeshrabiyaApi (e.g., `setOnFileStored`, `setOnOperationFailed`)
- Coroutine patterns used in MeshrabiyaApiImpl
- How other operations report success/failure
- Thread safety considerations

#### 4. Register Broadcast Listener in UI
- Register listener when Mesh tab is visible
- Unregister when Mesh tab is hidden/destroyed
- Display received broadcasts (notification, toast, or list update)
- **Must Verify:**
  - UI lifecycle patterns (Fragment/Activity/Compose)
  - How other events are displayed to user
  - Notification patterns if used

---

## MANDATORY ANALYSIS STEPS

You **MUST** perform the following analysis using **direct literal file reads** before creating the plan:

### Step 1: Locate Mesh Tab UI Implementation
**Search for:**
- Main app module structure
- Mesh tab Fragment/Activity/Composable
- Layout files (XML or Compose)
- "Refresh Status" button location

**Verification Required:**
- Read actual file to confirm structure
- Document exact file paths
- Note UI framework (XML Views vs Jetpack Compose)
- Identify ViewModel/state management patterns

### Step 2: Analyze MeshrabiyaApi Usage Patterns
**Search for:**
- How UI code currently calls MeshrabiyaApi
- API instance access pattern (singleton, injection, etc.)
- Example of other API calls with callbacks
- Error handling patterns

**Verification Required:**
- Read files that call MeshrabiyaApi methods
- Document exact call patterns
- Verify thread handling (UI thread vs background)

### Step 3: Analyze Existing Dialog Patterns
**Search for:**
- Other dialogs in the app (file selection, settings, etc.)
- Dialog base classes or builders
- Material Dialog usage
- Compose Dialog usage if applicable

**Verification Required:**
- Read actual dialog implementation files
- Document dialog creation patterns
- Note styling and theming approach

### Step 4: Analyze File Picker Patterns
**Search for:**
- Existing file picker implementations
- File selection result handling
- Permissions handling for file access
- File path retrieval patterns

**Verification Required:**
- Read actual file picker code
- Document launcher patterns (ActivityResultContract)
- Verify Android 11+ scoped storage handling

### Step 5: Analyze Event Handler Patterns in API
**Search for:**
- Existing `setOn*` methods in MeshrabiyaApi
- How handlers are stored and invoked
- Thread safety patterns for handlers
- Example of event handler registration in UI

**Verification Required:**
- Read MeshrabiyaApiImpl.kt for handler patterns
- Document handler storage (volatile, thread-safe collections)
- Verify invocation patterns (main thread vs background)

### Step 6: Analyze Coroutine Patterns in API
**Search for:**
- Existing suspend functions in MeshrabiyaApi
- CoroutineScope usage in MeshrabiyaApiImpl
- Dispatcher choices for operations
- Error handling in coroutines

**Verification Required:**
- Read MeshrabiyaApiImpl.kt for coroutine examples
- Document scope management
- Verify exception handling patterns

### Step 7: Analyze Network Status Observation
**Search for:**
- How Mesh tab observes network status
- MeshStateDto enum values
- StateFlow/LiveData patterns for status
- Where CONNECTED state is checked

**Verification Required:**
- Read UI code for status observation
- Document exact state property name and type
- Verify update mechanism

---

## RESEARCH REQUIREMENTS

Use the **runSubagent** tool with **falsification validation** for:

1. **Best Practices for Android File Picker (2024-2026)**
   - Latest ActivityResultContract patterns
   - Scoped storage best practices
   - Permission handling (Android 11+)

2. **Best Practices for Dialog Implementation**
   - Material Design 3 patterns
   - Form validation in dialogs
   - Loading states during async operations

3. **Best Practices for Coroutine API Design**
   - Converting callback APIs to suspend functions
   - Event handler patterns with coroutines
   - Error propagation patterns

4. **Best Practices for Button State Management**
   - Enabling/disabling based on observed state
   - Jetpack Compose vs XML patterns
   - Accessibility considerations

---

## FALSIFICATION VALIDATION RULES

Apply **AGENTS.md** falsification rules rigorously:

### Rule 1: No Assumptions About Existence
- **NEVER** assume a class, method, or property exists
- **ALWAYS** perform codebase-wide text search for exact definitions
- **DOCUMENT** file path, line number, and exact signature for every reference

### Rule 2: Literal File Read for All Signatures
- **BEFORE** using any method in the plan, read the file containing it
- **VERIFY** parameter names, types, and order
- **VERIFY** return types and nullability
- **VERIFY** suspend vs regular function
- **DOCUMENT** discrepancies between plan assumptions and reality

### Rule 3: API Usage Verification
- **SEARCH** for existing usage patterns in codebase
- **READ** actual usage to verify correct patterns
- **DOCUMENT** examples with file paths and line numbers

### Rule 4: Import Verification
- **VERIFY** exact package names for all types used
- **CHECK** if imports resolve (class exists in package)
- **DOCUMENT** full import statements in plan

---

## PLAN REQUIREMENTS

Your output document **NETWORK_BROADCAST_UI_PLAN_v1.md** must include:

### Section 1: Codebase Analysis Results
- Document exact file paths for all components analyzed
- Include file locations, line numbers, and signatures
- Document UI framework used (XML/Compose)
- Document existing patterns with code examples

### Section 2: API Refactoring Specification
- **Before:** Current callback-based signature
- **After:** New suspend function signature
- Event handler signatures (e.g., `setOnBroadcastSent`, `setOnBroadcastFailed`)
- Implementation changes in MeshrabiyaApiImpl.kt
- Thread safety considerations
- Migration path (if old callback needed for compatibility)

### Section 3: Button Implementation
- Exact file path and line number for button insertion
- Complete button XML or Compose code
- Network status observation code
- Button enable/disable logic with StateFlow/LiveData
- Code context (surrounding code for patch anchoring)

### Section 4: Dialog Implementation
- Dialog class/function definition (complete code)
- Message input field with character counter
- File picker integration (ActivityResultContract)
- Form validation logic
- Send button state management
- Error display mechanism
- Loading state during send
- Complete code with all imports

### Section 5: Broadcast Listener Registration
- Where to register/unregister listener
- Lifecycle-aware registration pattern
- Display mechanism for received broadcasts
- Complete code with lifecycle hooks

### Section 6: Integration Checklist
- [ ] All file paths verified with literal reads
- [ ] All method signatures verified against actual code
- [ ] All imports verified to exist
- [ ] All Android APIs verified for current targetSdk
- [ ] Thread safety verified for all UI updates
- [ ] Error handling verified for all failure modes
- [ ] Accessibility considered for all UI elements

### Section 7: Remaining Ambiguities
- Document any ambiguities that could not be resolved
- Provide options with trade-offs for each
- Mark decisions that require user input

### Section 8: Testing Strategy
- Unit tests for ViewModel/state logic
- UI tests for button enable/disable
- UI tests for dialog validation
- Integration tests for end-to-end broadcast

---

## CRITICAL RULES FROM AGENTS.MD

### Pre-Implementation Verification Protocol
**BEFORE writing any code in the plan, you MUST:**

1. ✅ Read the current state of ALL files you will modify
2. ✅ Verify the signature of EVERY method/property you will call
3. ✅ Confirm EVERY data class property name and type
4. ✅ Check if methods are suspend functions vs regular functions
5. ✅ Verify return types match (callbacks vs direct returns)
6. ✅ Confirm parameter names and types for ALL API calls
7. ✅ Document ANY discrepancies between assumptions and reality

### Patch Anchoring Rules
- ALL code patches MUST anchor to correct syntactic location
- Include package declaration context in patches
- Never insert imports before package declaration
- Use 5+ lines of context before and after target code

### Statement Veracity Rule
- NEVER present a claim about code as fact unless verified by direct search/read
- If uncertain, document the uncertainty explicitly
- Support all statements with file path + line number evidence

---

## EXAMPLE VERIFICATION OUTPUT

When you verify a component, document it like this:

```
✅ VERIFIED: Mesh Tab UI Component
File: app/src/main/java/org/torproject/android/ui/mesh/MeshFragment.kt
Line: 45-120
Structure: Fragment with XML layout
Layout File: app/src/main/res/layout/fragment_mesh.xml
Refresh Button ID: refresh_status_button (Line 78)
Network Status Observer: Line 95-102 (observes meshStatusFlow)
UI Framework: XML Views with ViewBinding
ViewModel: MeshViewModel (accessed via viewModels() delegate)
```

```
✅ VERIFIED: MeshrabiyaApi Access Pattern
File: app/src/main/java/org/torproject/android/ui/mesh/MeshViewModel.kt
Line: 30
Pattern: private val api = MeshrabiyaApiImpl.getInstance()
Thread Handling: viewModelScope.launch(Dispatchers.IO) for API calls
State Updates: MutableStateFlow properties with UI dispatcher
```

---

## OUTPUT FORMAT

Create **NETWORK_BROADCAST_UI_PLAN_v1.md** with:

1. **Executive Summary** (what will be implemented)
2. **Codebase Verification Results** (all analysis findings)
3. **API Refactoring Plan** (complete specification)
4. **UI Implementation Plan** (complete code for all components)
5. **Integration Steps** (order of implementation)
6. **Testing Strategy** (unit + integration tests)
7. **Remaining Ambiguities** (document unknowns)
8. **Verification Checklist** (mark as complete after each verification)

---

## SUCCESS CRITERIA

Your plan is complete when:

- ✅ Every file path verified with literal read
- ✅ Every method signature verified against actual code
- ✅ Every type/class verified to exist
- ✅ All imports verified to resolve
- ✅ All code anchored to verified locations
- ✅ All ambiguities documented with options
- ✅ Plan includes enough context for direct implementation
- ✅ No assumptions remain unverified

---

**BEGIN YOUR ANALYSIS NOW**

Start by using `grep_search` and `read_file` to locate and analyze the Mesh tab UI implementation. Follow the mandatory analysis steps in order. Apply falsification validation at every step. Produce a comprehensive, code-verified plan.
