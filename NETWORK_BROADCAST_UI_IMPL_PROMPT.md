# NETWORK_BROADCAST_UI_IMPLEMENTATION_PROMPT

**Date:** 2026-02-01  
**Task:** Implement broadcast message+file UI layer following NETWORK_BROADCAST_UI_PLAN_v1.md  
**Progress Tracking:** Update plan document with completion checkmarks as you proceed  

---

## PRIMARY DIRECTIVE

You are an expert Android/Kotlin developer tasked with implementing the broadcast UI layer for Orbot's mesh network feature. Your implementation must follow **NETWORK_BROADCAST_UI_PLAN_v1.md** exactly, completing all 5 phases in order and tracking progress in the plan document itself.

---

## CRITICAL RULES

### 1. FOLLOW THE PLAN EXACTLY
- Read **NETWORK_BROADCAST_UI_PLAN_v1.md** in full before starting
- Implement all 5 phases in the specified order
- Use the exact code provided in the plan (all code is production-ready)
- Do NOT deviate from specifications without documenting reasons

### 2. TRACK PROGRESS IN PLAN DOCUMENT
**MANDATORY:** After completing each step, update NETWORK_BROADCAST_UI_PLAN_v1.md with completion markers:

**Replace:**
```markdown
### 7.3. Implementation Order

1. **Phase 1: API Refactoring (Library Layer)**
   - Modify MeshrabiyaApi.kt (add suspend fun, new handlers)
   - Modify MeshrabiyaApiImpl.kt (implement handlers, refactor broadcastMessageAndFile)
   - Compile library module
   - Verify no compilation errors
```

**With:**
```markdown
### 7.3. Implementation Order

1. **Phase 1: API Refactoring (Library Layer)** ✅ COMPLETED 2026-02-01
   - ✅ Modify MeshrabiyaApi.kt (add suspend fun, new handlers)
   - ✅ Modify MeshrabiyaApiImpl.kt (implement handlers, refactor broadcastMessageAndFile)
   - ✅ Compile library module
   - ✅ Verify no compilation errors
```

### 3. USE MeshrabiyaConstants.kt FOR ALL CONSTANTS
**CRITICAL:** The plan references several broadcast constants that ALREADY EXIST in MeshrabiyaConstants.kt:

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaConstants.kt`

**Existing Constants (DO NOT CREATE DUPLICATES):**
```kotlin
const val BROADCAST_CHUNK_SIZE = 1024
const val MMCP_TYPE_BROADCAST_MESSAGE = 6
const val BROADCAST_TIMEOUT_MS = 30_000L
const val MAX_BROADCAST_MESSAGE_LENGTH = 500
```

**When you see references to these constants in the plan:**
- Use `MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH` (already exists)
- Use `MeshrabiyaConstants.BROADCAST_CHUNK_SIZE` (already exists)
- Use `MeshrabiyaConstants.BROADCAST_TIMEOUT_MS` (already exists)
- DO NOT create new constants or duplicate definitions

### 4. COMPILATION VERIFICATION AFTER EACH PHASE
After completing each phase, run:
```bash
: > build_output.log && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew :Meshrabiya:lib-meshrabiya:compileDebugKotlin --console=plain 2>&1 | tee build_output.log
```

For app module changes (Phases 2-4):
```bash
: > build_output.log && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tee build_output.log
```

**NEVER proceed to next phase if compilation fails.**

### 5. APPLY AGENTS.MD PROTOCOLS
All rules from AGENTS.md apply:
- **Pre-Implementation Verification Protocol**: Already completed in plan - use verified code directly
- **Patch Anchoring Rules**: All code in plan includes 5+ lines of context
- **Import Style Rule**: Use import + short name (never fully qualified)
- **Statement Veracity Rule**: All signatures already verified - trust the plan
- **Canonical Command Formats**: Use exact build commands shown above

---

## IMPLEMENTATION PHASES (From Section 7.3 of Plan)

### PHASE 1: API Refactoring (Library Layer)

**Files to Modify:**
1. `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt`
2. `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`

**Implementation Steps:**

#### Step 1.1: Modify MeshrabiyaApi.kt
**Reference:** Section 3.2 and 3.3 of plan

**Action A:** Change `broadcastMessageAndFile()` signature (Lines 204-250)
- Replace callback-based signature with suspend function
- Add default parameters for messageText and filePath
- Copy exact signature from Section 3.2

**Action B:** Add new handler methods (after Line 250)
- Add `setOnBroadcastSent(handler: (BroadcastResultDto) -> Unit)`
- Add `setOnBroadcastFailed(handler: (broadcastId: String, error: Throwable) -> Unit)`
- Copy exact signatures from Section 3.3

#### Step 1.2: Modify MeshrabiyaApiImpl.kt
**Reference:** Section 3.4 of plan

**Action A:** Add handler properties (after Line 1558)
```kotlin
// Broadcast event handlers (added 2026-02-01)
private var onBroadcastSent: ((BroadcastResultDto) -> Unit)? = null
private var onBroadcastFailed: ((broadcastId: String, error: Throwable) -> Unit)? = null
```

**Action B:** Implement setter methods (after Line 1596)
- Copy exact code from Section 3.4, Step 2

**Action C:** Add getter methods
- Copy exact code from Section 3.4, Step 3

**Action D:** Refactor `broadcastMessageAndFile()` (Lines 1798-1827)
- Replace entire method implementation with suspend version
- Copy exact code from Section 3.4, Step 4
- **CRITICAL:** Use `MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH` (not a literal value)

#### Step 1.3: Compile Library Module
```bash
: > build_output.log && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew :Meshrabiya:lib-meshrabiya:compileDebugKotlin --console=plain 2>&1 | tee build_output.log
```

**Verify:** Exit code 0, no compilation errors

#### Step 1.4: Update Plan Document
Mark Phase 1 as complete in Section 7.3 with ✅ and timestamp

---

### PHASE 2: Button Implementation (UI Layer)

**Files to Modify:**
1. `app/src/main/res/layout/fragment_mesh_enhanced.xml`
2. `app/src/main/res/values/strings.xml`
3. `app/src/main/res/drawable/ic_broadcast.xml` (create)
4. `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`

**Implementation Steps:**

#### Step 2.1: Add Button to Layout
**Reference:** Section 4.1 of plan

**File:** `app/src/main/res/layout/fragment_mesh_enhanced.xml`  
**Location:** Lines 304-324 (after `lastUpdateText`, before closing LinearLayout)

- Replace single `refreshButton` with horizontal LinearLayout containing both buttons
- Copy exact XML from Section 4.1 (NEW CODE block)

#### Step 2.2: Add String Resource
**Reference:** Section 4.2 of plan

**File:** `app/src/main/res/values/strings.xml`  
**Location:** After Line 238 (`refresh_mesh_status`)

Add:
```xml
<string name="send_broadcast">Send Broadcast</string>
```

#### Step 2.3: Create Icon Drawable (Optional but Recommended)
**Reference:** Section 4.3 of plan

**File:** `app/src/main/res/drawable/ic_broadcast.xml` (create new file)

- Copy exact XML from Section 4.3, Option 2
- OR use `android:icon="@android:drawable/ic_menu_send"` in layout

#### Step 2.4: Bind Button in MeshUIBindings
**Reference:** Section 4.4 of plan

**Action:** Search for `MeshUIBindings` definition (likely in EnhancedMeshFragment.kt)
- Add property: `lateinit var sendBroadcastButton: com.google.android.material.button.MaterialButton`
- Bind in `bindImmediateViews()`: `sendBroadcastButton = view.findViewById(R.id.sendBroadcastButton)`

**VERIFICATION STEP:** Use grep to find MeshUIBindings definition:
```bash
grep -n "object MeshUIBindings\|class MeshUIBindings" app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
```

#### Step 2.5: Setup Button Listener
**Reference:** Section 4.5 of plan

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Location:** In `setupListeners()` method (after Line 463)

Add:
```kotlin
// Send Broadcast button
MeshUIBindings.sendBroadcastButton.setOnClickListener {
    showBroadcastDialog()
}
```

#### Step 2.6: Update Button State Logic
**Reference:** Section 4.6 of plan

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Location:** In `updateButtonStates(meshStatus: MeshStateDto)` method (Lines 873-913)

**Modify all cases:**
- CONNECTED: `MeshUIBindings.sendBroadcastButton.isEnabled = true`
- DISCONNECTED, CONNECTING, INITIALIZING, ERROR, UNKNOWN: `MeshUIBindings.sendBroadcastButton.isEnabled = false`

#### Step 2.7: Compile App Module
```bash
: > build_output.log && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tee build_output.log
```

**Verify:** Exit code 0, button appears in layout preview

#### Step 2.8: Update Plan Document
Mark Phase 2 as complete in Section 7.3 with ✅ and timestamp

---

### PHASE 3: Dialog Implementation

**Files to Create/Modify:**
1. `app/src/main/res/layout/dialog_broadcast.xml` (create)
2. `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt` (modify)

**Implementation Steps:**

#### Step 3.1: Create Dialog Layout
**Reference:** Section 5.2 of plan

**File:** `app/src/main/res/layout/dialog_broadcast.xml` (create new file)

- Copy ENTIRE layout XML from Section 5.2
- Verify all Material3 components used

#### Step 3.2: Add Required Imports to Fragment
**Reference:** Section 5.3 of plan

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`

Add imports (if not already present):
```kotlin
import android.widget.TextView
import android.view.View
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
```

**Note:** Uri, ActivityResultContracts already imported (verified in Section 2.4)

#### Step 3.3: Implement showBroadcastDialog() Method
**Reference:** Section 5.1 of plan

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Location:** Add method after `updateButtonStates()` (around Line 920)

- Copy ENTIRE method from Section 5.1 (production-ready, ~150 lines)
- Verify all findViewById IDs match dialog_broadcast.xml
- **CRITICAL:** Method uses `MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH` constant

#### Step 3.4: Compile and Test
```bash
: > build_output.log && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tee build_output.log
```

**Manual Test (if compilation succeeds):**
- Build APK: `./gradlew :app:assembleDebug`
- Install: `export ANDROID_HOME="$HOME/Library/Android/sdk" && export PATH="$PATH:$ANDROID_HOME/platform-tools" && adb -s 30870044490006E install -r app/build/outputs/apk/debug/app-debug.apk`
- Test: Click "Send Broadcast" button, verify dialog opens

#### Step 3.5: Update Plan Document
Mark Phase 3 as complete in Section 7.3 with ✅ and timestamp

---

### PHASE 4: Broadcast Listener Integration

**Files to Modify:**
1. `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`

**Implementation Steps:**

#### Step 4.1: Add Listener Property
**Reference:** Section 6.2 of plan

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Location:** After `meshrabiyaApi` property (around Line 63)

Add:
```kotlin
private lateinit var broadcastListener: (com.ustadmobile.meshrabiya.ext.BroadcastReceivedDto) -> Unit
```

#### Step 4.2: Register Listener in onViewCreated()
**Reference:** Section 6.1 of plan

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Location:** After role observer setup (around Line 202)

- Copy ENTIRE listener registration code from Section 6.1
- Includes Snackbar display with "View" action
- Includes FileProvider integration for opening files

#### Step 4.3: Register Success/Failure Handlers
**Reference:** Section 6.4 of plan

**Location:** After broadcast listener registration in onViewCreated()

Add:
```kotlin
// Register broadcast success handler
meshrabiyaApi.setOnBroadcastSent { result ->
    activity?.runOnUiThread {
        android.util.Log.d("EnhancedMeshFragment", "Broadcast sent: ${result.broadcastId}, ${result.successNodeIds.size} nodes reached")
    }
}

// Register broadcast failure handler
meshrabiyaApi.setOnBroadcastFailed { broadcastId, error ->
    activity?.runOnUiThread {
        android.util.Log.e("EnhancedMeshFragment", "Broadcast failed: $broadcastId", error)
        view?.let { v ->
            Snackbar.make(v, "Broadcast failed: ${error.message}", Snackbar.LENGTH_LONG).show()
        }
    }
}
```

#### Step 4.4: Unregister Listener in onDestroyView()
**Reference:** Section 6.3 of plan

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Location:** In `onDestroyView()` method (after camera cleanup, around Line 279)

Add:
```kotlin
// Unregister broadcast listener
if (this::broadcastListener.isInitialized) {
    meshrabiyaApi.unregisterBroadcastListener(broadcastListener)
}
```

#### Step 4.5: Compile and Verify
```bash
: > build_output.log && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tee build_output.log
```

**Verify:** Exit code 0, all lifecycle hooks correct

#### Step 4.6: Update Plan Document
Mark Phase 4 as complete in Section 7.3 with ✅ and timestamp

---

### PHASE 5: End-to-End Testing

**Reference:** Section 8 of plan (Testing Strategy)

**Prerequisites:**
- Phases 1-4 completed successfully
- APK built and installed on test device
- Two devices with mesh running for integration tests

**Testing Steps:**

#### Step 5.1: Manual Functional Testing

**Test 1: Button State Management**
- Start app, navigate to Mesh tab
- Verify "Send Broadcast" button is disabled (mesh not running)
- Start mesh, wait for CONNECTED state
- Verify "Send Broadcast" button is enabled
- Stop mesh
- Verify "Send Broadcast" button is disabled

**Test 2: Dialog Validation**
- Start mesh, connect
- Click "Send Broadcast" button
- Verify dialog opens
- Type 501 characters in message field
- Click Send
- Verify error message "Message exceeds 500 character limit"
- Clear message
- Click Send (no message, no file)
- Verify error message "Please enter a message or select a file"
- Type short message
- Verify Send button enabled
- Clear message
- Click "Select File", pick any file
- Verify Send button enabled

**Test 3: Broadcast Send (Message Only)**
- Enter message "Test broadcast 123"
- Click Send
- Verify progress indicator appears
- Verify dialog closes on success
- Verify snackbar shows "Broadcast sent successfully"
- Check logs for "Broadcast sent: ..."

**Test 4: Broadcast Send (File Only)**
- Click "Select File", pick test file
- Verify file name displayed
- Click Send
- Verify success

**Test 5: Broadcast Send (Message + File)**
- Enter message "Test with file"
- Click "Select File", pick test file
- Click Send
- Verify success

**Test 6: Broadcast Receive (Two Devices)**
- Device A: Send broadcast "Hello from A"
- Device B: Verify snackbar appears "📡 Broadcast from Node X: Hello from A"
- Click "View" action
- Verify file opens (if file included)

#### Step 5.2: Error Condition Testing

**Test 7: No Drop Folder Selected**
- Fresh install (or clear app data)
- Start mesh, connect
- Try to send broadcast
- Verify error: "Please select a drop folder to receive file broadcasts"

**Test 8: Mesh Not Running**
- Ensure mesh is stopped
- Button should be disabled (cannot click)

**Test 9: File Access Error**
- Revoke file access permissions
- Try to send broadcast with file
- Verify error shown

#### Step 5.3: Automated Testing (Optional)

**Unit Tests:** Run existing tests (if any)
```bash
./gradlew :app:testDebugUnitTest
```

**UI Tests:** Run automated tests (if implemented from Section 8.2)
```bash
./gradlew :app:connectedDebugAndroidTest
```

#### Step 5.4: Final Verification Checklist

Review Section 10 of plan (Verification Checklist):

- [ ] All file paths verified with literal reads (already done in plan)
- [ ] All method signatures verified against actual code (already done in plan)
- [ ] All data class properties verified (already done in plan)
- [ ] All imports verified to exist (already done in plan)
- [ ] Button appears in correct location in UI
- [ ] Button enables/disables correctly based on mesh state
- [ ] Dialog opens when button clicked
- [ ] Dialog validates input correctly
- [ ] Character counter updates correctly
- [ ] File picker works
- [ ] Broadcast sends successfully (message only)
- [ ] Broadcast sends successfully (file only)
- [ ] Broadcast sends successfully (message + file)
- [ ] Received broadcasts show snackbar
- [ ] Error messages display correctly
- [ ] No memory leaks (listener unregistered)
- [ ] No crashes during testing

#### Step 5.5: Update Plan Document
Mark Phase 5 as complete in Section 7.3 with ✅ and timestamp

---

## PROGRESS TRACKING TEMPLATE

**When updating NETWORK_BROADCAST_UI_PLAN_v1.md after each phase:**

```markdown
### 7.3. Implementation Order

1. **Phase 1: API Refactoring (Library Layer)** ✅ COMPLETED 2026-02-01 14:23
   - ✅ Modify MeshrabiyaApi.kt (add suspend fun, new handlers)
   - ✅ Modify MeshrabiyaApiImpl.kt (implement handlers, refactor broadcastMessageAndFile)
   - ✅ Compile library module: SUCCESS (Exit code 0)
   - ✅ Verify no compilation errors: VERIFIED

2. **Phase 2: Button Implementation (UI Layer)** ✅ COMPLETED 2026-02-01 14:45
   - ✅ Add button to fragment_mesh_enhanced.xml
   - ✅ Add string resource to strings.xml
   - ✅ Create icon drawable (ic_broadcast.xml)
   - ✅ Bind button in MeshUIBindings
   - ✅ Add click listener in setupListeners()
   - ✅ Update button state logic in updateButtonStates()
   - ✅ Compile app module: SUCCESS (Exit code 0)
   - ✅ Test button appears and disables/enables correctly: PASS

[... continue for all phases ...]
```

---

## ERROR RECOVERY PROTOCOLS

### If Compilation Fails:

1. **Read build_output.log** - Full error details
2. **Check for:**
   - Import errors → Verify all imports from Section 5.3
   - Syntax errors → Compare your code to plan code exactly
   - Type mismatches → Verify signatures match Section 2.8
   - Missing resources → Verify strings.xml, drawable created
   - Constant errors → Verify using `MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH` not literal

3. **Fix errors** - Use plan code as reference
4. **Recompile** - Repeat until success
5. **Document issues** - Add note to plan if significant deviation needed

### If Tests Fail:

1. **Check logs** - Use `adb logcat` to see runtime errors
2. **Verify lifecycle** - Listener registered in onViewCreated, unregistered in onDestroyView
3. **Verify thread safety** - All UI updates wrapped in `activity?.runOnUiThread { ... }`
4. **Check drop folder** - Must be selected before sending broadcasts
5. **Verify mesh state** - Must be CONNECTED to send broadcasts

---

## COMPLETION CRITERIA

**Implementation is COMPLETE when:**

1. ✅ All 5 phases marked complete in plan document with timestamps
2. ✅ All compilation commands succeed (exit code 0)
3. ✅ All manual tests pass (Section 5.1-5.2)
4. ✅ No crashes or errors during testing
5. ✅ Received broadcasts display correctly
6. ✅ Final verification checklist (Section 5.4) fully checked

**Final Action:**
- Update INTERIM_COMMIT_LOG.md with summary of changes
- Commit all changes with message: "feat: implement broadcast message+file UI layer (NETWORK_BROADCAST_v2)"

---

## CRITICAL REMINDERS

1. **USE MeshrabiyaConstants.kt** - All broadcast constants already exist there
2. **TRACK PROGRESS** - Update plan document after EACH phase
3. **VERIFY COMPILATION** - After EACH phase before proceeding
4. **FOLLOW PLAN EXACTLY** - All code is production-ready, use as-is
5. **APPLY AGENTS.MD** - All protocols apply (import style, patch anchoring, etc.)
6. **NEVER SKIP STEPS** - Each step builds on previous steps

---

**BEGIN IMPLEMENTATION NOW**

Start with Phase 1, update progress as you go, and work systematically through all 5 phases.
