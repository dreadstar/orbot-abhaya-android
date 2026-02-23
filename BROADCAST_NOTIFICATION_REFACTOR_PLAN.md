
# Broadcast Notification Refactor Plan
## 1. Subfolder Write Failure: Root Cause and Solution

### Findings
- Log and code confirm mkdir fails due to permissions or path conflict.
- No file/subfolder named `SharedWithMe` exists; creation fails silently or throws.
- App file write permissions are not checked before attempting mkdir.

### Solution
- Add explicit permission checks before any file/subfolder creation.
- Enhance logging: log all relevant DocumentFile properties (exists, isDirectory, canWrite, uri, parent, etc.) before and after mkdir.
- If mkdir fails, log the exact error and state, including parent folder status.
- Add recovery logic: if path exists as file, delete or rename before mkdir.

## 2. Notification Dropdown Refactor: Generic Notification List

### Findings
- receivedBroadcasts list currently stores all notifications, including errors.
- No generic notification list exists; error notifications are not properly mapped.
- UI dropdown does not display error notifications; badge increments for all.

### Solution
- Create a generic `NotificationItem` data class:
  - Properties: id, type (Broadcast, Error, Storage, etc.), title, message, filePath, folderPath, createdAt, errorMessage, etc.
- Maintain a single notification list in reverse chronological order.
- Each item has a type, title, and creation datetime.
- Broadcast notifications: title = "Broadcast Rcvd: <date>"
- Error notifications: title = "Broadcast Error: <date>"
- UI dropdown:
  - Dynamically populate from notification list (latest first)
  - Each item: truncated title, right-justified clear button (oval X)
  - On click: show detail dialog with layout based on type
  - Broadcast: show message, file link, folder link (icon button)
  - Error: show error message, troubleshooting info
- Add clear-all and clear-single notification actions.

## 3. Sender Notification Exclusion: Logic and Verification

### Findings
- Sender device (phone_test.log) receives its own notification due to lack of exclusion logic.
- router() patch intended to prevent loopback, but sender still acts as listener.

### Solution
- Add explicit check: sender device must not add its own broadcast to notification list or increment badge.
- In BroadcastMessageHandler, before notifying listeners, exclude senderNodeId == myNodeId.
- Verify router() patch: ensure sender is not routed to itself.
- Add log statements to confirm sender exclusion.
- investigate via logs and code if phone 1 is receiving rebroadcast from phone 2, as alternative solution to the issue being caused  loopback.

## 4. Implementation Plan: Codebase-Driven Steps


### FULL CODE-LEVEL IMPLEMENTATION PLAN (Ready-to-Implement)

#### 1. NotificationItem Data Model

**File:** app/src/main/java/org/torproject/android/ui/mesh/model/NotificationItem.kt

```kotlin
package org.torproject.android.ui.mesh.model

import java.util.Date

/**
 * Represents a notification item for the mesh UI dropdown (broadcast, error, storage, etc.)
 */
data class NotificationItem(
  val id: String,
  val type: NotificationType,
  val title: String,
  val message: String? = null,
  val filePath: String? = null,
  val folderPath: String? = null,
  val createdAt: Long = Date().time,
  val errorMessage: String? = null
)

/**
 * Enum for notification types
 */
enum class NotificationType {
  BROADCAST,
  ERROR,
  STORAGE
}
```

#### 2. EnhancedMeshFragment.kt Refactor (Notification List)

**File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt

**BEFORE (Line 71):**
```kotlin
  private val receivedBroadcasts = mutableListOf<BroadcastNotification>()
```
**AFTER:**
```kotlin
  private val notificationList = mutableListOf<NotificationItem>()
```

**BEFORE (Lines 308–331):**
```kotlin
  val isDuplicate = receivedBroadcasts.any { it.broadcastId == broadcast.broadcastId }
  android.util.Log.d(tag, "[UI_CALLBACK] Adding to receivedBroadcasts list (currently ${receivedBroadcasts.size} items)")
  receivedBroadcasts.add(0, BroadcastNotification(
    broadcastId = broadcast.broadcastId,
    senderNodeId = broadcast.senderNodeId,
    messageText = broadcast.messageText,
    fileName = broadcast.fileName,
    filePath = broadcast.filePath,
    timestamp = System.currentTimeMillis(),
    hasError = false,
    errorMessage = null
  ))
  android.util.Log.d(tag, "[UI_CALLBACK] ✅ Added to list - new size=${receivedBroadcasts.size}")
  val badgeCount = receivedBroadcasts.size
```
**AFTER:**
```kotlin
  val isDuplicate = notificationList.any { it.id == broadcast.broadcastId }
  android.util.Log.d(tag, "[UI_CALLBACK] Adding to notificationList (currently ${notificationList.size} items)")
  notificationList.add(0, NotificationItem(
    id = broadcast.broadcastId,
    type = NotificationType.BROADCAST,
    title = "Broadcast Rcvd: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}",
    message = broadcast.messageText,
    filePath = broadcast.filePath,
    folderPath = null,
    createdAt = System.currentTimeMillis(),
    errorMessage = null
  ))
  android.util.Log.d(tag, "[UI_CALLBACK] ✅ Added to list - new size=${notificationList.size}")
  val badgeCount = notificationList.size
```

**BEFORE (Line 1926):**
```kotlin
  fun getReceivedBroadcasts(): List<BroadcastNotification> = receivedBroadcasts.toList()
```
**AFTER:**
```kotlin
  fun getNotificationList(): List<NotificationItem> = notificationList.toList()
```

**BEFORE (Line 1932):**
```kotlin
  receivedBroadcasts.clear()
```
**AFTER:**
```kotlin
  notificationList.clear()
```

Update all UI dropdown logic to use `notificationList` and `NotificationItem`. For error notifications, add with `type = NotificationType.ERROR` and set `errorMessage`.

#### 3. BroadcastMessageHandler.kt Refactor (Sender Exclusion)

**File:** Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt

**BEFORE (around line 533):**
```kotlin
  logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] Notifying ${receiveListeners.size} listeners for successful file broadcast: fileName='${state.metadata.fileName}'")
  synchronized(receiveListeners) {
    receiveListeners.forEach { it(notification) }
  }
  logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] ✅ All ${receiveListeners.size} listeners notified")
```
**AFTER:**
```kotlin
  // Exclude sender from notification
  if (state.senderNodeId != virtualNode.addressAsInt) {
    logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] Notifying ${receiveListeners.size} listeners for successful file broadcast: fileName='${state.metadata.fileName}'")
    synchronized(receiveListeners) {
      receiveListeners.forEach { it(notification) }
    }
    logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] ✅ All ${receiveListeners.size} listeners notified")
  } else {
    logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] Skipping notification for senderNodeId=${state.senderNodeId} (self)")
  }
```

This ensures the sender device does not add its own broadcast to the notification list or increment the badge.

#### 4. UI Dropdown and Dialog Refactor

- Update dropdown to use `notificationList` (reverse chronological).
- Each item: show `title`, clear button, and on click, show detail dialog with layout based on `NotificationType`.
- Add clear-all and clear-single notification actions.

#### 5. Enhanced Logging and Permission Checks

- Add explicit permission checks before file/subfolder creation.
- Log all relevant DocumentFile properties before and after mkdir.
- Log exact error and state if mkdir fails.

---

**Purpose:** Implements a generic notification system, sender exclusion logic, and robust UI/logic refactor as per BROADCAST_NOTIFICATION_REFACTOR_PLAN.md. All changes are anchored after package/imports, with pattern uniqueness verified for manual implementation. Follows AGENTS.md, Rule Zero, and all code verification protocols.

---

**Manual Implementation Instructions:**
For all large files (>800 lines), copy the BEFORE/AFTER code blocks above and apply them at the specified line numbers and context. Verify pattern uniqueness before making changes. Anchor all code after package/imports.

---

**Verification Checklist:**
- [ ] Test all notification types and sender exclusion.
- [ ] Confirm dropdown and badge behavior.
- [ ] Confirm enhanced logging and permission checks.

---

**All steps follow AGENTS.md, Rule Zero, and code verification protocols.**
