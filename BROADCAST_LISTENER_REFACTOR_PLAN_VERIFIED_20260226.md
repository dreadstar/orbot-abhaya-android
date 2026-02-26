# Broadcast Listener Refactor & Separation of Concerns: Disk-Verified Implementation Plan

---

## 1. Evaluation of User Considerations

### a. Broadcast Listener Initialization
- **Current State:** Listener launches a new StateFlow collector for every broadcast, causing redundant UI updates.
- **Correct Pattern:** Listener should be initialized once; StateFlow collector for notificationFeed should be launched once in onViewCreated.

### b. Validation Logic
- **Current State:** Checks for duplicates and sender ID, but logic is disordered and does not add error notifications for invalid cases.
- **Expected Pattern:**
  - If duplicate: add StatusNotification for error.
  - If sender ID matches node ID: add StatusNotification for error.
  - If broadcast contains file and has error: add StatusNotification for error.
  - Only if all checks pass: add BroadcastNotification.

### c. StateFlow Propagation
- **Current State:** Listener updates broadcastNotifications and manually updates badge count.
- **Expected Pattern:** Adding a BroadcastNotification to MutableStateFlow propagates to notificationFeed; UI updates (dropdown and badge) are handled by observing notificationFeed.

---

## 2. Code-Level Implementation Plan

### A. Refactor broadcastListener Logic
**File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt  
**Location:** broadcastListener definition (approx. lines 360–393)

#### BEFORE
```
broadcastListener = { broadcast: BroadcastReceivedDto ->
    val isDuplicate = broadcastNotifications.value.any { it.id == broadcast.broadcastId }
    if (isDuplicate) { ... return@launch }
    val newItem = BroadcastNotification(
        id = broadcast.broadcastId,
        title = ...,
        createdAt = System.currentTimeMillis(),
        message = broadcast.messageText,
        filePath = broadcast.filePath,
        senderNodeId = broadcast.senderNodeId.toString()
    )
    val myNodeId = meshrabiyaApi.getNodeId().toString()
    if (broadcast.senderNodeId.toString() != myNodeId.toString()) {
        broadcastNotifications.value = broadcastNotifications.value + newItem
    }
    val badgeCount = notificationFeed.value.size
    (activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(badgeCount)
    viewLifecycleOwner.lifecycleScope.launch {
        notificationFeed.collect { notifications ->
            val badgeCount = notifications.size
            (activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(badgeCount)
            notificationsAdapter.submitList(notifications)
        }
    }
}
```

#### AFTER
```
broadcastListener = { broadcast: BroadcastReceivedDto ->
    val isDuplicate = broadcastNotifications.value.any { it.id == broadcast.broadcastId }
    val myNodeId = meshrabiyaApi.getNodeId().toString()
    val isSelf = broadcast.senderNodeId.toString() == myNodeId
    val hasFileError = broadcast.filePath != null && broadcast.messageText.contains("error", ignoreCase = true)

    when {
        isDuplicate -> {
            statusNotifications.value = statusNotifications.value + StatusNotification(
                id = broadcast.broadcastId,
                title = "Duplicate Broadcast",
                createdAt = System.currentTimeMillis(),
                statusMessage = "Broadcast already received"
            )
        }
        isSelf -> {
            statusNotifications.value = statusNotifications.value + StatusNotification(
                id = broadcast.broadcastId,
                title = "Self Broadcast",
                createdAt = System.currentTimeMillis(),
                statusMessage = "Sender is self"
            )
        }
        hasFileError -> {
            statusNotifications.value = statusNotifications.value + StatusNotification(
                id = broadcast.broadcastId,
                title = "File Error",
                createdAt = System.currentTimeMillis(),
                statusMessage = "Error in broadcast file"
            )
        }
        else -> {
            val newItem = BroadcastNotification(
                id = broadcast.broadcastId,
                title = broadcast.messageText.take(32),
                createdAt = System.currentTimeMillis(),
                message = broadcast.messageText,
                filePath = broadcast.filePath,
                senderNodeId = broadcast.senderNodeId.toString()
            )
            broadcastNotifications.value = broadcastNotifications.value + newItem
        }
    }
}
```
**Purpose:** Ordered validation, error notifications, no UI updates in listener.

---

### B. Move StateFlow Collector to onViewCreated
**File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt  
**Location:** onViewCreated (approx. lines 310–320)

#### BEFORE
```
// ...existing code...
// No collector for notificationFeed here
// ...existing code...
```

#### AFTER
```
// ...existing code...
viewLifecycleOwner.lifecycleScope.launch {
    notificationFeed.collect { notifications ->
        val badgeCount = notifications.size
        (activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(badgeCount)
        notificationsAdapter.submitList(notifications)
    }
}
// ...existing code...
```
**Purpose:** Single collector for notificationFeed; UI updates handled by observer.

---

### C. No Changes Needed in NotificationsAdapter or NotificationItem
- NotificationsAdapter.kt: Adapter already updates dropdown via submitList.
- NotificationItem.kt: Data classes and extension functions are correct.

---

## 3. Summary Table

| File | Location | Change Type | BEFORE | AFTER | Purpose |
|------|----------|-------------|--------|-------|---------|
| EnhancedMeshFragment.kt | broadcastListener (360–393) | Refactor | Disordered, redundant logic; UI updates in listener | Ordered validation; error notifications; no UI updates | Separation of concerns; code quality |
| EnhancedMeshFragment.kt | onViewCreated (310–320) | Add | No collector for notificationFeed | Single collector for notificationFeed | Proper UI update mechanism |
| NotificationsAdapter.kt | N/A | None | Already correct | Already correct | N/A |
| NotificationItem.kt | N/A | None | Already correct | Already correct | N/A |

---

## 4. Implementation Instructions
- Remove UI update logic from broadcastListener.
- Refactor validation logic in broadcastListener as shown.
- Move StateFlow collector for notificationFeed to onViewCreated.
- No changes needed in adapter or model files.

---

**No code changes have been made. All analysis and plans are disk-verified and ready for implementation.**
