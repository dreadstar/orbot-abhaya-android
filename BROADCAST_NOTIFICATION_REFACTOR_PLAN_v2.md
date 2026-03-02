# Broadcast Notification Refactor Plan v2

## 1. NotificationItem Data Model (Disk-Verified)

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
    BROADCAST, // Received Broadcasts (Message and/or File )
    STATUS, // Error and Completion messages from Broadcast, Compute, Storage, Contacts
    STORAGE, // Notification of Files shared via Distributed Store
    COMPUTE, // Notification of Compute Task completion and results delivery
    CONTACTS // Notification of Contacts Messages  and Contact added/removed
}
```

---

## 2. EnhancedMeshFragment.kt Refactor (Unified Notification List)

**File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt

### Manual Edit Instructions (Large File >800 lines)

#### Location: Line 72

**BEFORE:**
```kotlin
private val receivedBroadcasts = mutableListOf<BroadcastNotification>()
```
**AFTER:**
```kotlin
private val notificationList = mutableListOf<NotificationItem>()
```

---

#### Location: Lines 335–366 (Broadcast listener logic)

**BEFORE:**
```kotlin
val isDuplicate = receivedBroadcasts.any { it.broadcastId == broadcast.broadcastId }
if (isDuplicate) {
    android.util.Log.w(tag, "[UI_CALLBACK] ⚠️ DUPLICATE broadcast detected, skipping (already in list)")
    return@launch
}
android.util.Log.d(tag, "[UI_CALLBACK] Adding to receivedBroadcasts list (currently ${receivedBroadcasts.size} items)")
receivedBroadcasts.add(0, BroadcastNotification(
    broadcastId = broadcast.broadcastId,
    senderNodeId = broadcast.senderNodeId.toString(),
    messageText = broadcast.messageText,
    fileName = broadcast.fileName,
    filePath = broadcast.filePath,
    timestamp = System.currentTimeMillis(),
    hasError = broadcast.hasError,
    errorMessage = broadcast.errorMessage
))
android.util.Log.d(tag, "[UI_CALLBACK] ✅ Added to list - new size=${receivedBroadcasts.size}")
val badgeCount = receivedBroadcasts.size
(activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(badgeCount)
android.util.Log.d(tag, "[UI_CALLBACK] 🔔 Badge updated: count=$badgeCount (broadcast added)")
```
**AFTER:**
```kotlin
val isDuplicate = notificationList.any { it.id == broadcast.broadcastId }
if (isDuplicate) {
    android.util.Log.w(tag, "[UI_CALLBACK] ⚠️ DUPLICATE broadcast detected, skipping (already in list)")
    return@launch
}
android.util.Log.d(tag, "[UI_CALLBACK] Adding to notificationList (currently ${notificationList.size} items)")
notificationList.add(0, NotificationItem(
    id = broadcast.broadcastId,
    type = NotificationType.BROADCAST,
    title = "Broadcast Rcvd: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}",
    message = broadcast.messageText,
    filePath = broadcast.filePath,
    folderPath = null,
    createdAt = System.currentTimeMillis(),
    errorMessage = broadcast.errorMessage
))
android.util.Log.d(tag, "[UI_CALLBACK] ✅ Added to list - new size=${notificationList.size}")
val badgeCount = notificationList.size
(activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(badgeCount)
android.util.Log.d(tag, "[UI_CALLBACK] 🔔 Badge updated: count=$badgeCount (broadcast added)")
```

---

#### Location: Line 1926

**BEFORE:**
```kotlin
fun getReceivedBroadcasts(): List<BroadcastNotification> = receivedBroadcasts.toList()
```
**AFTER:**
```kotlin
fun getNotificationList(): List<NotificationItem> = notificationList.toList()
```

---

#### Location: Line 1932

**BEFORE:**
```kotlin
receivedBroadcasts.clear()
```
**AFTER:**
```kotlin
notificationList.clear()
```

---

**Purpose:** Implements unified notification list using NotificationItem. Updates all logic to use notificationList. Ensures sender exclusion and badge logic are correct.

---

## 3. BroadcastMessageHandler.kt Refactor (Sender Exclusion)

**File:** Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt

#### Location: Around line 533

**BEFORE:**
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

---

**Purpose:** Ensures sender device does not add its own broadcast to notification list or increment badge.

---

## 4. UI Dropdown and Dialog Refactor

- Update dropdown to use `notificationList` (reverse chronological).
- Each item: show `title`, clear button, and on click, show detail dialog with layout based on `NotificationType`.
- Add clear-all and clear-single notification actions.

---

## 5. Enhanced Logging and Permission Checks

- Add explicit permission checks before file/subfolder creation.
- Log all relevant DocumentFile properties before and after mkdir.
- Log exact error and state if mkdir fails.

---

## Manual Implementation Instructions

- For EnhancedMeshFragment.kt (>800 lines), apply changes manually at specified line numbers and context.
- For BroadcastMessageHandler.kt, update sender exclusion logic as shown.

---

## Verification Checklist

- [ ] Test all notification types and sender exclusion.
- [ ] Confirm dropdown and badge behavior.
- [ ] Confirm enhanced logging and permission checks.

---

**All steps follow AGENTS.md, Rule Zero, and code verification protocols.**
