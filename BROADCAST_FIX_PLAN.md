# BROADCAST FUNCTIONALITY FIX PLAN
**Date:** 2026-02-04
**Scope:** Fix drop folder validation logic, file name display, and font size adjustments

## ISSUE SUMMARY

### Issue #1: Drop Folder Logic Error
**Problem:** Library code throws IllegalStateException when `getDropFolder()` returns null during `sendBroadcast()`, blocking all broadcast sends.  
**Root Cause:** Drop folder is only needed to RECEIVE broadcasts with files, not SEND them. The validation at line 1839 of `MeshrabiyaApiImpl.kt` is architecturally incorrect - it checks during send rather than during receive.  
**Impact:** Users cannot send broadcasts even when they don't intend to receive files.  
**Correct Architecture:** Drop folder should only be validated when a node RECEIVES a broadcast containing a file. If not set, show error toast and notification with "no storage folder set" message instead of file link.

### Issue #2: File Name Not Displayed
**Problem:** After selecting a file to broadcast, the filename does not appear under the file selection button in the dialog.  
**Location:** `dialog_broadcast.xml` has `selectedFileNameText` TextView and parent LinearLayout both with `visibility="gone"`, but callback sets text and visibility correctly.  
**Root Cause:** Parent LinearLayout (lines 64-89 of `dialog_broadcast.xml`) has `android:visibility="gone"` - even when child TextView visibility is set to VISIBLE, parent container remains hidden.

### Issue #3: Font Size Too Large
**Problem:** Bold nodes count and bit rates in Network Overview card use `TextAppearance.Material3.TitleLarge` which is too prominent.  
**Location:** Lines 378-384, 403-409, 425-431 of `fragment_mesh_enhanced.xml`  
**Desired:** Reduce by one step to `TextAppearance.Material3.TitleMedium`

---

## DETAILED IMPLEMENTATION PLAN

### FIX #1: Move Drop Folder Validation from SEND to RECEIVE

**Architectural Goal:** Drop folder should ONLY be checked when receiving a broadcast with a file, never when sending.

**Affected Files:**
1. `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt` (1881 lines - LIBRARY CODE)
2. `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt` (329 lines - LIBRARY CODE)
3. `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/ext/BroadcastDtos.kt` (87 lines - LIBRARY CODE)
4. `/Users/dreadstar/workspace/orbot-android/app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt` (1831 lines - UI CODE)
5. `/Users/dreadstar/workspace/orbot-android/app/src/main/res/layout/fragment_mesh_enhanced_deferred.xml` (UI XML)

---

#### **PART A: Remove Send-Time Validation (Library)**

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`  
**Location:** Lines 1830-1865  
**Change:** Remove drop folder validation check entirely from sendBroadcast()

**BEFORE (Lines 1830-1847):**
```kotlin
        if (messageText.length > MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH) {
            val error = IllegalArgumentException(
                "Message exceeds ${MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH} character limit"
            )
            onBroadcastFailed?.invoke("", error)
            throw error
        }
        
        // Validate drop folder is set
        val dropFolder = getDropFolder()
        if (dropFolder == null) {
            val error = IllegalStateException(
                "Please select a drop folder to receive file broadcasts"
            )
            onBroadcastFailed?.invoke("", error)
            throw error
        }
```

**AFTER (Lines 1830-1837):**
```kotlin
        if (messageText.length > MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH) {
            val error = IllegalArgumentException(
                "Message exceeds ${MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH} character limit"
            )
            onBroadcastFailed?.invoke("", error)
            throw error
        }
        
        // Drop folder validation removed - only needed when RECEIVING files, not sending
```

---

#### **PART B: Add Receive-Time Error Handling (Library)**

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt`  
**Location:** Lines 230-260 (onReceiveBroadcastPacket method, file reassembly section)  
**Change:** Catch IllegalStateException from writeBroadcastFile and create error notification

**BEFORE (Lines 230-256):**
```kotlin
                    logger(Log.INFO, "$TAG Broadcast $broadcastId: all chunks received, reassembling")
                    
                    // Reassemble file
                    val fileBytes = state.reassemble()
                    
                    // Write to Shared/ folder
                    val filePath = writeBroadcastFile(state.metadata.fileName, fileBytes)
                    
                    // Notify listeners
                    val notification = com.ustadmobile.meshrabiya.ext.BroadcastReceivedDto(
                        broadcastId = broadcastId,
                        messageText = state.messageText,
                        fileId = state.metadata.fileId,
                        fileName = state.metadata.fileName,
                        filePath = filePath,
                        senderNodeId = state.senderNodeId,
                        receivedAt = System.currentTimeMillis()
                    )
                    
                    synchronized(receiveListeners) {
                        receiveListeners.forEach { it(notification) }
                    }
                    
                    logger(Log.INFO, "$TAG Broadcast $broadcastId: complete, file written to $filePath")
                    
                    // Cleanup
                    incomingBroadcasts.remove(broadcastId)
```

**AFTER (Lines 230-270):**
```kotlin
                    logger(Log.INFO, "$TAG Broadcast $broadcastId: all chunks received, reassembling")
                    
                    // Reassemble file
                    val fileBytes = state.reassemble()
                    
                    // Try to write to Shared/ folder
                    val filePath: String?
                    val hasError: Boolean
                    try {
                        filePath = writeBroadcastFile(state.metadata.fileName, fileBytes)
                        hasError = false
                        logger(Log.INFO, "$TAG Broadcast $broadcastId: complete, file written to $filePath")
                    } catch (e: IllegalStateException) {
                        // Drop folder not set - create error notification instead
                        filePath = null
                        hasError = true
                        logger(Log.WARN, "$TAG Broadcast $broadcastId: drop folder not set, file cannot be saved", e)
                    }
                    
                    // Notify listeners (with or without file path)
                    val notification = com.ustadmobile.meshrabiya.ext.BroadcastReceivedDto(
                        broadcastId = broadcastId,
                        messageText = state.messageText,
                        fileId = state.metadata.fileId,
                        fileName = state.metadata.fileName,
                        filePath = filePath ?: "",  // Empty string if error
                        senderNodeId = state.senderNodeId,
                        receivedAt = System.currentTimeMillis(),
                        hasError = hasError,
                        errorMessage = if (hasError) "No storage folder set" else null
                    )
                    
                    synchronized(receiveListeners) {
                        receiveListeners.forEach { it(notification) }
                    }
                    
                    // Cleanup
                    incomingBroadcasts.remove(broadcastId)
```

---

#### **PART C: Update DTO for Error State (Library)**

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/ext/BroadcastDtos.kt`  
**Location:** Lines 22-29  
**Change:** Add hasError and errorMessage fields to BroadcastReceivedDto

**BEFORE (Lines 22-29):**
```kotlin
data class BroadcastReceivedDto(
    val broadcastId: String,
    val messageText: String,
    val fileId: String,
    val fileName: String,
    val filePath: String,  // Path in Shared/ folder
    val senderNodeId: Int,
    val receivedAt: Long = System.currentTimeMillis()
)
```

**AFTER (Lines 22-31):**
```kotlin
data class BroadcastReceivedDto(
    val broadcastId: String,
    val messageText: String,
    val fileId: String,
    val fileName: String,
    val filePath: String,  // Path in Shared/ folder, empty string if hasError=true
    val senderNodeId: Int,
    val receivedAt: Long = System.currentTimeMillis(),
    val hasError: Boolean = false,
    val errorMessage: String? = null  // "No storage folder set" when drop folder missing
)
```

---

#### **PART D: Handle Error in UI Broadcast Listener (UI)**

**File:** `/Users/dreadstar/workspace/orbot-android/app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Location:** Lines 302-330 (broadcastListener lambda)  
**Change:** Check for hasError flag, show toast and create notification with error message

**BEFORE (Lines 302-330):**
```kotlin
		broadcastListener = { broadcastDto ->
			activity?.runOnUiThread {
				val message = if (broadcastDto.messageText.isNotEmpty()) {
					"📡 Broadcast from Node ${broadcastDto.senderNodeId}: ${broadcastDto.messageText}"
				} else {
					"📡 File received from Node ${broadcastDto.senderNodeId}: ${broadcastDto.fileName}"
				}
				
				view?.let { v ->
					Snackbar.make(v, message, Snackbar.LENGTH_LONG)
						.setAction("View") {
							val filePath = broadcastDto.filePath
							try {
								val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
								val file = java.io.File(filePath)
								val uri = androidx.core.content.FileProvider.getUriForFile(
									requireContext(),
									"${requireContext().packageName}.fileprovider",
									file
								)
								intent.setDataAndType(uri, requireContext().contentResolver.getType(uri))
								intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
								startActivity(intent)
							} catch (e: Exception) {
								Snackbar.make(v, "Cannot open file: ${e.message}", Snackbar.LENGTH_SHORT).show()
							}
						}
						.show()
				}
```

**AFTER (Lines 302-350):**
```kotlin
		broadcastListener = { broadcastDto ->
			activity?.runOnUiThread {
				// Check for receive error (e.g., no drop folder set)
				if (broadcastDto.hasError) {
					// Show error toast
					Toast.makeText(
						requireContext(),
						"⚠️ Broadcast received but file could not be saved: ${broadcastDto.errorMessage}",
						Toast.LENGTH_LONG
					).show()
					
					// Create notification with error message
					val notificationMessage = buildString {
						append("📡 Broadcast from Node ${broadcastDto.senderNodeId}")
						if (broadcastDto.messageText.isNotEmpty()) {
							append("\nMessage: ${broadcastDto.messageText}")
						}
						append("\n\n⚠️ File: ${broadcastDto.fileName}")
						append("\nError: ${broadcastDto.errorMessage}")
					}
					
					// TODO: Add to notification dropdown (requires notification system implementation)
					android.util.Log.w("EnhancedMeshFragment", "Broadcast receive error: $notificationMessage")
					
					// Show snackbar with action to open storage settings
					view?.let { v ->
						Snackbar.make(v, "Broadcast file error: ${broadcastDto.errorMessage}", Snackbar.LENGTH_LONG)
							.setAction("Fix") {
								// Scroll to Storage Drop Folder card
								// TODO: Implement scroll to card or open settings
							}
							.show()
					}
				} else {
					// Normal broadcast receive - no error
					val message = if (broadcastDto.messageText.isNotEmpty()) {
						"📡 Broadcast from Node ${broadcastDto.senderNodeId}: ${broadcastDto.messageText}"
					} else {
						"📡 File received from Node ${broadcastDto.senderNodeId}: ${broadcastDto.fileName}"
					}
					
					view?.let { v ->
						Snackbar.make(v, message, Snackbar.LENGTH_LONG)
							.setAction("View") {
								val filePath = broadcastDto.filePath
								try {
									val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
									val file = java.io.File(filePath)
									val uri = androidx.core.content.FileProvider.getUriForFile(
										requireContext(),
										"${requireContext().packageName}.fileprovider",
										file
									)
									intent.setDataAndType(uri, requireContext().contentResolver.getType(uri))
									intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
									startActivity(intent)
								} catch (e: Exception) {
									Snackbar.make(v, "Cannot open file: ${e.message}", Snackbar.LENGTH_SHORT).show()
								}
							}
							.show()
					}
				}
```

---

#### **PART E: Add Warning to Storage Drop Folder Card (UI)**

**File:** `/Users/dreadstar/workspace/orbot-android/app/src/main/res/layout/fragment_mesh_enhanced_deferred.xml`  
**Location:** After line 280 (after title TextView), before folder selection buttons  
**Change:** Add warning TextView that shows when no folder is selected

**XML Addition (insert after line 280):**
```xml
                android:layout_marginBottom="12dp" />

            <!-- Warning: No folder selected -->
            <TextView
                android:id="@+id/dropFolderWarningText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="⚠️ No drop folder selected. You can send broadcasts, but won't be able to receive file broadcasts until a folder is selected."
                android:textAppearance="@style/TextAppearance.Material3.BodySmall"
                android:textColor="@android:color/holo_orange_dark"
                android:background="@color/warning_background"
                android:padding="12dp"
                android:layout_marginBottom="12dp"
                android:visibility="gone" />

            <!-- Folder Selection Buttons -->
```

**File:** `/Users/dreadstar/workspace/orbot-android/app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Location:** In `updateDeferredCardUI()` method (around lines 860-890)  
**Change:** Show/hide warning based on drop folder status

Add to MeshUIBindings.kt (after line 69, in bindDeferredViews):
```kotlin
lateinit var dropFolderWarningText: TextView
```

Then in bindDeferredViews method (after line 145):
```kotlin
dropFolderWarningText = view.findViewById(R.id.dropFolderWarningText)
```

Then in updateDeferredCardUI() (around line 870):
```kotlin
// Show warning if no drop folder is configured
val dropFolder = meshrabiyaApi.getDropFolder()
if (dropFolder == null) {
    MeshUIBindings.dropFolderWarningText.visibility = View.VISIBLE
} else {
    MeshUIBindings.dropFolderWarningText.visibility = View.GONE
}
```

---

### FIX #2: Display Selected File Name in Broadcast Dialog

**Problem:** Parent LinearLayout container has `android:visibility="gone"`, preventing child TextView from being visible.

**File:** `/Users/dreadstar/workspace/orbot-android/app/src/main/res/layout/dialog_broadcast.xml`  
**Location:** Lines 63-89  
**Change:** Store reference to parent container and set visibility when file is selected

**BEFORE (Lines 63-89):**
```xml
    <!-- Selected File Display -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:visibility="gone">

        <TextView
            android:id="@+id/selectedFileNameText"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textAppearance="@style/TextAppearance.Material3.BodySmall"
            android:textColor="?android:attr/textColorPrimary"
            android:ellipsize="middle"
            android:singleLine="true"
            tools:text="example_file.pdf" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/clearFileButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Clear"
            style="@style/Widget.Material3.Button.TextButton" />

    </LinearLayout>
```

**AFTER (Lines 63-89):**
```xml
    <!-- Selected File Display -->
    <LinearLayout
        android:id="@+id/selectedFileContainer"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:visibility="gone">

        <TextView
            android:id="@+id/selectedFileNameText"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textAppearance="@style/TextAppearance.Material3.BodySmall"
          3 (Font Size)** - Highest Priority, simple XML change, no dependencies
2. **FIX #2 (File Name Display)** - High Priority, straightforward XML + Kotlin change
3. **FIX #1 (Drop Folder Logic)** - Critical Priority, multi-part architectural change:
   - **Part A**: Remove send-time validation (library code)
   - **Part B**: Add receive-time error handling (library code)
   - **Part C**: Update DTO for error state (library code)
   - **Part D**: Handle errors in UI broadcast listener (UI code)
   - **Part E**: Add warning in Storage Drop Folder card (UI coden
            android:id="@+id/clearFileButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
### Fix #1: Drop Folder Logic
- [ ] Send message-only broadcast WITHOUT selecting drop folder → should succeed
- [ ] Send file broadcast WITHOUT selecting drop folder → should succeed (send works)
- [ ] RECEIVE file broadcast WITHOUT selecting drop folder → should show error toast
- [ ] RECEIVE file broadcast WITHOUT selecting drop folder → notification shows message + error
- [ ] Storage Drop Folder card → warning appears when no folder selected
- [ ] Select drop folder → warning disappears
- [ ] RECEIVE file broadcast WITH drop folder selected → file saved, no error

### Fix #2: File Name Display
- [ ] Select file for broadcast → filename appears under button in dialog
- [ ] Clear selected file → filename disappears

### Fix #3: Font Size
- [ ] Network Overview card → metrics display in smaller font size (TitleMedium)ndroid/app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Location:** Lines 1080-1160 (showBroadcastDialog method)  
**Change:** Get reference to parent container and set its visibility

**BEFORE (Lines 1084-1090):**
```kotlin
		// Find views
		val messageInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.broadcastMessageInput)
		val messageCounterText = dialogView.findViewById<TextView>(R.id.messageCharacterCounter)
		val fileNameText = dialogView.findViewById<TextView>(R.id.selectedFileNameText)
		val selectFileButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.selectFileButton)
		val clearFileButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.clearFileButton)
		val sendButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.sendBroadcastDialogButton)
```

**AFTER (Lines 1084-1091):**
```kotlin
		// Find views
		val messageInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.broadcastMessageInput)
		val messageCounterText = dialogView.findViewById<TextView>(R.id.messageCharacterCounter)
		val selectedFileContainer = dialogView.findViewById<LinearLayout>(R.id.selectedFileContainer)
		val fileNameText = dialogView.findViewById<TextView>(R.id.selectedFileNameText)
		val selectFileButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.selectFileButton)
		val clearFileButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.clearFileButton)
		val sendButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.sendBroadcastDialogButton)
```

**BEFORE (Lines 1108-1116):**
```kotlin
		pendingFileCallback = { uri ->
            android.util.Log.d("EnhancedMeshFragment", "File selection callback invoked, URI: $uri")
            selectedFileUri = uri
            // Get file name from URI
            val docFile = DocumentFile.fromSingleUri(requireContext(), uri)
            android.util.Log.d("EnhancedMeshFragment", "DocumentFile: $docFile, name: ${docFile?.name}")
            val fileName = docFile?.name ?: "Unknown file"
            android.util.Log.d("EnhancedMeshFragment", "Setting fileName text to: $fileName")
            fileNameText.text = fileName
            fileNameText.visibility = View.VISIBLE
            clearFileButton.visibility = View.VISIBLE
            android.util.Log.d("EnhancedMeshFragment", "File name display updated, visibility: ${fileNameText.visibility}")
            updateSendButtonState()
        }
```

**AFTER (Lines 1109-1118):**
```kotlin
		pendingFileCallback = { uri ->
            android.util.Log.d("EnhancedMeshFragment", "File selection callback invoked, URI: $uri")
            selectedFileUri = uri
            // Get file name from URI
            val docFile = DocumentFile.fromSingleUri(requireContext(), uri)
            android.util.Log.d("EnhancedMeshFragment", "DocumentFile: $docFile, name: ${docFile?.name}")
            val fileName = docFile?.name ?: "Unknown file"
            android.util.Log.d("EnhancedMeshFragment", "Setting fileName text to: $fileName")
            fileNameText.text = fileName
            selectedFileContainer.visibility = View.VISIBLE  // Show parent container
            android.util.Log.d("EnhancedMeshFragment", "File name display updated, container visibility: ${selectedFileContainer.visibility}")
            updateSendButtonState()
        }
```

**BEFORE (Lines 1149-1153):**
```kotlin
		clearFileButton.setOnClickListener {
			selectedFileUri = null
			fileNameText.visibility = View.GONE
			clearFileButton.visibility = View.GONE
			updateSendButtonState()
		}
```

**AFTER (Lines 1150-1153):**
```kotlin
		clearFileButton.setOnClickListener {
			selectedFileUri = null
			selectedFileContainer.visibility = View.GONE  // Hide parent container
			updateSendButtonState()
		}
```

---

### FIX #3: Reduce Font Size for Bold Metrics

**File:** `/Users/dreadstar/workspace/orbot-android/app/src/main/res/layout/fragment_mesh_enhanced.xml`  
**Locations:** Lines 382, 407, 429 (three TextViews)  
**Change:** Replace `TextAppearance.Material3.TitleLarge` with `TextAppearance.Material3.TitleMedium`

**Line 378-384 BEFORE:**
```xml
                        <TextView
                            android:id="@+id/text_active_node_count"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="0 nodes"
                            android:textAppearance="@style/TextAppearance.Material3.TitleLarge"
                            android:textStyle="bold" />
```

**Line 378-384 AFTER:**
```xml
                        <TextView
                            android:id="@+id/text_active_node_count"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="0 nodes"
                            android:textAppearance="@style/TextAppearance.Material3.TitleMedium"
                            android:textStyle="bold" />
```

**Line 403-409 BEFORE:**
```xml
                        <TextView
                            android:id="@+id/text_upload_bitrate"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="0 Bps"
                            android:textAppearance="@style/TextAppearance.Material3.TitleLarge"
                            android:textStyle="bold" />
```

**Line 403-409 AFTER:**
```xml
                        <TextView
                            android:id="@+id/text_upload_bitrate"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="0 Bps"
                            android:textAppearance="@style/TextAppearance.Material3.TitleMedium"
                            android:textStyle="bold" />
```

**Line 425-431 BEFORE:**
```xml
                        <TextView
                            android:id="@+id/text_download_bitrate"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="0 Bps"
                            android:textAppearance="@style/TextAppearance.Material3.TitleLarge"
                            android:textStyle="bold" />
```

**Line 425-431 AFTER:**
```xml
                        <TextView
                            android:id="@+id/text_download_bitrate"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="0 Bps"
                            android:textAppearance="@style/TextAppearance.Material3.TitleMedium"
                            android:textStyle="bold" />
```onReceiveBroadcastPacket()` | Method | BroadcastMessageHandler.kt | 189 | ✅ |
| `writeBroadcastFile()` | Method | BroadcastMessageHandler.kt | 272 | ✅ |
| `BroadcastReceivedDto` | Data Class | BroadcastDtos.kt | 22 | ✅ |
| `BroadcastReceivedDto.hasError` | Property | BroadcastDtos.kt | N/A | ⚠️ MUST ADD |
| `BroadcastReceivedDto.errorMessage` | Property | BroadcastDtos.kt | N/A | ⚠️ MUST ADD |
| `broadcastListener` | Lambda | EnhancedMeshFragment.kt | 302 | ✅ |
| `selectedFileContainer` | View ID | dialog_broadcast.xml | 64 | ⚠️ MUST ADD |
| `selectedFileNameText` | View ID | dialog_broadcast.xml | 72 | ✅ |
| `clearFileButton` | View ID | dialog_broadcast.xml | 84 | ✅ |
| `text_active_node_count` | View ID | fragment_mesh_enhanced.xml | 378 | ✅ |
| `text_upload_bitrate` | View ID | fragment_mesh_enhanced.xml | 403 | ✅ |
| `text_download_bitrate` | View ID | fragment_mesh_enhanced.xml | 425 | ✅ |
| `dropFolderWarningText` | View ID | fragment_mesh_enhanced_deferred.xml | N/A | ⚠️ MUST ADD |
| `selectedFolderText` | TextView | fragment_mesh_enhanced_deferred.xml | 310 | ✅ |
| `MeshUIBindings.selectedFolderText` | Property | MeshUIBindings.kt | 69 | ✅ |
| `MeshUIBindings.dropFolderWarningText` | Property | MeshUIBindings.kt | N/A | ⚠️ MUST ADD
| `getDropFolder()` | Method | MeshrabiyaApiImpl.kt | 1294 | ✅ |
| `selectedFileContainer` | View ID | dialog_broadcast.xml | 64 | ⚠️ MUST ADD |
| `selectedFileNameText` | View ID | dialog_broadcast.xml | 72 | ✅ |
| `clearFileButton` | View ID | dialog_broadcast.xml | 84 | ✅ |
| `text_active_node_count` | View ID | fragment_mesh_enhanced.xml | 378 | ✅ |
| `text_upload_bitrate` | View ID | fragment_mesh_enhanced.xml | 403 | ✅ |
| `text_download_bitrate` | View ID | fragment_mesh_enhanced.xml | 425 | ✅ |
| `dropFolderWarningText` | View ID | fragment_mesh_enhanced_deferred.xml | N/A | ⚠️ MUST ADD |
| `selectedFolderText` | TextView | fragment_mesh_enhanced_deferred.xml | 310 | ✅ |
| `MeshUIBindings.selectedFolderText` | Property | MeshUIBindings.kt | 69 | ✅ |
| `TextAppearance.Material3.TitleMedium` | Style | Material3 | N/A | ✅ |

---

## IMPLEMENTATION PRIORITY

1. **FIX #2 (File Name Display)** - High Priority, straightforward XML + Kotlin change
2. **FIX #3 (Font Size)** - High Priority, simple XML change
3. **FIX #1 (Drop Folder Logic)** - Critical Priority but requires decision:
   - Option A: Modify library code (preferred, architecturally correct)
   - Option B: Workaround with default folder (simpler, no library changes)
   - Option 1B: Add warning UI (nice-to-have, improves UX)

---

## TESTING CHECKLIST
s (MeshrabiyaApiImpl.kt, BroadcastMessageHandler.kt, BroadcastDtos.kt) are architecturally necessary
- Drop folder warning color requires adding to colors.xml: `<color name="warning_background">#FFF3E0</color>`
- Fix #1 is multi-part: 3 library files + 2 UI files must be changed together for proper functionality
- Notification system integration (TODO in Part D) requires separate implementation
- [ ] Send file broadcast WITHOUT selecting drop folder → should show error
- [ ] Select file for broadcast → filename appears under button
- [ ] Clear selected file → filename disappears
- [ ] Network Overview card → metrics display in smaller font size
- [ ] Storage Drop Folder card → warning appears when no folder selected
- [ ] Select drop folder → warning disappears

---

## NOTES

- EnhancedMeshFragment.kt is 1831 lines - subject to Large File Manual Edit Rule (>800 lines)
- All code changes with context provided for manual implementation
- Library modification (MeshrabiyaApiImpl.kt) may require approval - workaround provided
- Drop folder warning color requires adding to colors.xml: `<color name="warning_background">#FFF3E0</color>`
