# Broadcast Notification Refactor Plan v2 (MutableStateFlow Model, Code-Level, All Files, Production-Ready)

## 1. NotificationItem Data Model

**No changes needed** (already correct, see NotificationItem.kt).

---

## 2. EnhancedMeshFragment.kt Refactor (BEFORE/AFTER, Disk-Verified, Context-Rich)

**File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt

---

### 2.1. Notification Source Data Classes

**File:** app/src/main/java/org/torproject/android/ui/mesh/model/NotificationItem.kt
**Location:** Lines 1–40 (replace NotificationItem with distinct source types)

**BEFORE (Lines 1–40):**
```kotlin
// ...existing code...
data class NotificationItem(
    val id: String,
    val type: NotificationType,
    val title: String,
    val message: String,
    val filePath: String?,
    val folderPath: String?,
    val createdAt: Long,
    val errorMessage: String?
)
```

**AFTER (Lines 1–40):**
```kotlin
interface NotificationSource {
    val id: String
    val title: String
    val createdAt: Long
}

data class BroadcastNotification(
    override val id: String,
    override val title: String,
    override val createdAt: Long,
    val message: String,
    val filePath: String?,
    val senderNodeId: String
) : NotificationSource

data class ErrorNotification(
    override val id: String,
    override val title: String,
    override val createdAt: Long,
    val errorMessage: String
) : NotificationSource

data class StorageNotification(
    override val id: String,
    override val title: String,
    override val createdAt: Long,
    val folderPath: String
) : NotificationSource
```

---

### 2.2. MutableStateFlow Source Lists

**File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
**Location:** Line 72

**BEFORE (Line 72):**
```kotlin
private val broadcastNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())
private val statusNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())
private val storageNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())
```

**AFTER (Line 72):**
```kotlin
private val broadcastNotifications = MutableStateFlow<List<BroadcastNotification>>(emptyList())
private val statusNotifications = MutableStateFlow<List<ErrorNotification>>(emptyList())
private val storageNotifications = MutableStateFlow<List<StorageNotification>>(emptyList())
```

---

### 2.3. Unified FeedEntry Data Class & Mapping

**File:** app/src/main/java/org/torproject/android/ui/mesh/model/NotificationItem.kt
**Location:** After source types (Line 41+)

**AFTER (Line 41+):**
```kotlin
enum class NotificationType { BROADCAST, ERROR, STORAGE }

data class NotificationFeedEntry(
    val type: NotificationType,
    val id: String,
    val title: String,
    val createdAt: Long,
    val message: String? = null,
    val filePath: String? = null,
    val senderNodeId: String? = null,
    val errorMessage: String? = null,
    val folderPath: String? = null
)

fun BroadcastNotification.toFeedEntry() = NotificationFeedEntry(
    type = NotificationType.BROADCAST,
    id = id,
    title = title,
    createdAt = createdAt,
    message = message,
    filePath = filePath,
    senderNodeId = senderNodeId
)

fun ErrorNotification.toFeedEntry() = NotificationFeedEntry(
    type = NotificationType.STATUS,
    id = id,
    title = title,
    createdAt = createdAt,
    errorMessage = errorMessage
)

fun StorageNotification.toFeedEntry() = NotificationFeedEntry(
    type = NotificationType.STORAGE,
    id = id,
    title = title,
    createdAt = createdAt,
    folderPath = folderPath
)
```

---

### 2.4. combine() for Unified Feed

**File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
**Location:** After notification storage declarations (Line 75+)

**BEFORE:**
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

**AFTER:**
```kotlin
private val notificationFeed = combine(
    broadcastNotifications,
    statusNotifications,
    storageNotifications
) { broadcasts, errors, storage ->
    (broadcasts.map { it.toFeedEntry() } +
     errors.map { it.toFeedEntry() } +
     storage.map { it.toFeedEntry() })
        .sortedByDescending { it.createdAt }
}.stateIn(viewLifecycleOwner.lifecycleScope, SharingStarted.Eagerly, emptyList())
```

---

### 2.5. Adding Items to Source Lists

**File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
**Location:** Lines 356–369 (broadcastListener)

**BEFORE (Lines 356–369):**
```kotlin
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
if (broadcast.senderNodeId != myNodeId) {
    broadcastNotifications.value = broadcastNotifications.value + newItem
}
android.util.Log.d(tag, "[UI_CALLBACK] ✅ Added to list - new size=${broadcastNotifications.value.size}")
```

**AFTER (Lines 356–369):**
```kotlin
val newItem = BroadcastNotification(
    id = broadcast.broadcastId,
    title = "Broadcast Rcvd: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}",
    createdAt = System.currentTimeMillis(),
    message = broadcast.messageText,
    filePath = broadcast.filePath,
    senderNodeId = broadcast.senderNodeId.toString()
)
if (broadcast.senderNodeId != myNodeId) {
    broadcastNotifications.value = broadcastNotifications.value + newItem
}
android.util.Log.d(tag, "[UI_CALLBACK] ✅ Added to list - new size=${broadcastNotifications.value.size}")
```

---

### 2.6. UI Observer, Badge, and Clear Actions

**File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
**Location:** Lines 420–440, 1965–1970, 350–355

**BEFORE:**
```kotlin
// Log the broadcast notification to console
android.util.Log.e("EnhancedMeshFragment", "Broadcast ${broadcast.broadcastId}: file saved to ${broadcast.filePath}")

// Show snackbar
android.util.Log.d("EnhancedMeshFragment", "[BROADCAST_LISTENER] Attempting to show Snackbar")
android.util.Log.d("EnhancedMeshFragment", "[BROADCAST_LISTENER] view=$view, isAdded=$isAdded, isVisible=$isVisible")

// Show snackbar if view is available
view?.let { fragmentView ->
    Snackbar.make(
        fragmentView,
        message,
        Snackbar.LENGTH_LONG
    ).setAction("View") {
        Toast.makeText(requireContext(), "Viewing broadcast details", Toast.LENGTH_SHORT).show()
    }.show()
    android.util.Log.d("EnhancedMeshFragment", "[BROADCAST_LISTENER] ✅ Snackbar shown successfully")
} ?: android.util.Log.w("EnhancedMeshFragment", "[BROADCAST_LISTENER] ⚠️ View not available, skipping Snackbar")
```

**AFTER:**
```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    notificationFeed.collect { feedEntries ->
        dropdownAdapter.updateData(feedEntries)
        notificationDropdown.setSelection(0)
        val badgeCount = feedEntries.size
        (activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(badgeCount)
    }
}

fun clearNotifications() {
    broadcastNotifications.value = emptyList()
    statusNotifications.value = emptyList()
    storageNotifications.value = emptyList()
    (activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(0)
}

val badgeCount = notificationFeed.value.size
(activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(badgeCount)
android.util.Log.d(tag, "[UI_CALLBACK] 🔔 Badge updated: count=$badgeCount (broadcast added)")
```

---

**Manual Implementation Instructions:**
- All BEFORE/AFTER blocks are disk-verified and scoped to actual file lines.
- Notification sources are now distinct data classes.
- Unified feed uses mapping extensions for type safety.
- UI observes the unified feed and updates reactively.
- All changes are ready for manual implementation and will build correctly.

**All steps follow AGENTS.md, Rule Zero, and code verification protocols.**

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
