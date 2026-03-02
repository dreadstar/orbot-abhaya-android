# Broadcast Notification Refactor Plan v2 (MutableStateFlow Model)

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

## 2. Source Lists as MutableStateFlow<List<T>>

**File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt

### Notification Sources
- Each notification source (broadcasts, errors, storage, etc.) is a `MutableStateFlow<List<NotificationItem>>`.
- Example:

```kotlin
val broadcastNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())
val statusNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())
val storageNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())
```

---

## 3. Adding Items (Immutable List Update)

- To add a notification, replace the list value:

```kotlin
broadcastNotifications.value = broadcastNotifications.value + newItem
```
- Never mutate the list directly.

---

## 4. Unified Notification Feed with combine()

- Use `combine()` to merge all source flows into a unified feed:

```kotlin
val notificationFeed = combine(
    broadcastNotifications,
    statusNotifications,
    storageNotifications
) { broadcasts, errors, storage ->
    (broadcasts + errors + storage)
        .sortedByDescending { it.createdAt }
}
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```
- The feed is always current and reverse-chronological.

---

## 5. UI Dropdown and Dialog Refactor

- UI observes `notificationFeed` (StateFlow<List<NotificationItem>>).
- Each item: show `title`, clear button, and on click, show detail dialog with layout based on `NotificationType`.
- Add clear-all and clear-single notification actions by updating the relevant source flow.

---

## 6. Sender Exclusion Logic

- When adding a broadcast notification, check senderNodeId:

```kotlin
if (broadcast.senderNodeId != myNodeId) {
    broadcastNotifications.value = broadcastNotifications.value + newItem
}
```

---

## 7. Enhanced Logging and Permission Checks

- Add explicit permission checks before file/subfolder creation.
- Log all relevant DocumentFile properties before and after mkdir.
- Log exact error and state if mkdir fails.

---

## 8. Gradle Dependency

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}
```

---

## Manual Implementation Instructions

- Replace all mutable lists with `MutableStateFlow<List<NotificationItem>>`.
- Update all add/remove logic to replace the list value.
- Use `combine()` to merge flows for the unified feed.
- Update UI to observe the unified feed.

---

## Verification Checklist

- [ ] Test all notification types and sender exclusion.
- [ ] Confirm dropdown and badge behavior.
- [ ] Confirm enhanced logging and permission checks.
- [ ] Confirm reactivity and correctness of the unified feed.

---

**All steps follow AGENTS.md, Rule Zero, and code verification protocols.**
