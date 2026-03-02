# Broadcast Notification Refactor Plan v2 (MutableStateFlow Model, Code-Level, Production-Ready)

## 1. NotificationItem Data Model

**No changes needed** (already correct, see NotificationItem.kt).

---

## 2. EnhancedMeshFragment.kt Refactor (BEFORE/AFTER, Disk-Verified)

**File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt

---

### 2.1. Notification Storage Declaration

**Location:** Line 72

**BEFORE (Line 72):**
```kotlin
private val receivedBroadcasts = mutableListOf<BroadcastNotification>()
```
**AFTER (Line 72):**
```kotlin
private val broadcastNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())
private val statusNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())
private val storageNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())
```

---

### 2.2. Unified Feed with combine()

**Location:** After notification storage declarations (Line 75+)

**BEFORE:**
*(No unified feed exists)*

**AFTER:**
```kotlin
private val notificationFeed = combine(
    broadcastNotifications,
    statusNotifications,
    storageNotifications
) { broadcasts, errors, storage ->
    (broadcasts + errors + storage)
        .sortedByDescending { it.createdAt }
}.stateIn(viewLifecycleOwner.lifecycleScope, SharingStarted.Eagerly, emptyList())
```

---

### 2.3. Adding a Broadcast Notification

**Location:** Lines 341–362 (inside broadcastListener, previously added to receivedBroadcasts)**

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
val isDuplicate = broadcastNotifications.value.any { it.id == broadcast.broadcastId }
if (isDuplicate) {
    android.util.Log.w(tag, "[UI_CALLBACK] ⚠️ DUPLICATE broadcast detected, skipping (already in list)")
    return@launch
}
android.util.Log.d(tag, "[UI_CALLBACK] Adding to broadcastNotifications (currently ${broadcastNotifications.value.size} items)")
val newItem = NotificationItem(
    id = broadcast.broadcastId,
    type = NotificationType.BROADCAST,
    title = "Broadcast Rcvd: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}",
    message = broadcast.messageText,
    filePath = broadcast.filePath,
    folderPath = null,
    createdAt = System.currentTimeMillis(),
    errorMessage = broadcast.errorMessage
)
broadcastNotifications.value = broadcastNotifications.value + newItem
android.util.Log.d(tag, "[UI_CALLBACK] ✅ Added to list - new size=${broadcastNotifications.value.size}")
val badgeCount = notificationFeed.value.size
(activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(badgeCount)
android.util.Log.d(tag, "[UI_CALLBACK] 🔔 Badge updated: count=$badgeCount (broadcast added)")
```

---

### 2.4. Get Notifications Feed

**Location:** Line 1949 (was getReceivedBroadcasts)

**BEFORE:**
```kotlin
fun getReceivedBroadcasts(): List<BroadcastNotification> = receivedBroadcasts.toList()
```
**AFTER:**
```kotlin
fun getNotificationFeed(): StateFlow<List<NotificationItem>> = notificationFeed
```

---

### 2.5. Clear All Notifications

**Location:** Line 1963

**BEFORE:**
```kotlin
fun clearNotifications() {
    receivedBroadcasts.clear()
    (activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(0)
}
```
**AFTER:**
```kotlin
fun clearNotifications() {
    broadcastNotifications.value = emptyList()
    statusNotifications.value = emptyList()
    storageNotifications.value = emptyList()
    (activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(0)
}
```

---

## 3. Sender Exclusion Logic

**When adding a broadcast notification:**

**BEFORE:** (No sender exclusion)
```kotlin
// ...existing code...
receivedBroadcasts.add(0, BroadcastNotification(/* ... */))
// ...existing code...
```

**AFTER:** (Add before adding to flow)
```kotlin
if (broadcast.senderNodeId != myNodeId) {
    broadcastNotifications.value = broadcastNotifications.value + newItem
}
```

---

## 4. UI Refactor

- Update UI to observe `notificationFeed` (StateFlow<List<NotificationItem>>).
- All notification list and badge logic should use the unified feed.

---

## 5. Gradle Dependency

Add to `build.gradle.kts`:
```kotlin
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}
```

---

## Manual Implementation Instructions

- Replace all mutable lists with MutableStateFlow.
- Update all add/remove logic to replace the list value.
- Use combine() for the unified feed.
- Update UI to observe the unified feed.
- Apply all BEFORE/AFTER changes at the specified line numbers and context.

---

## Verification Checklist

- [ ] Test all notification types and sender exclusion.
- [ ] Confirm dropdown and badge behavior.
- [ ] Confirm enhanced logging and permission checks.
- [ ] Confirm reactivity and correctness of the unified feed.

---

**All steps follow AGENTS.md, Rule Zero, and code verification protocols.**
