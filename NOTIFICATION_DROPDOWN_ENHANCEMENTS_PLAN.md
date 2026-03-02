# Notification Dropdown Enhancements Plan

*Created: 2026-02-28*  
This document outlines four requested improvements to the notification
feed dropdown.  All analysis is derived from literal file readings of whatever
code exists on disk at the time of writing.  No modifications have been made
to project source; the plan provides exact before/after snippets ready for
manual implementation.

---

## Issue 1: Close button on each notification card

### Goal
Add a clickable close icon in the top right corner of each item.  When
pressed it must remove the corresponding entry from the `MutableStateFlow`
(`broadcastNotifications`, `statusNotifications`, `storageNotifications`)
and thus make the item disappear from the UI.

### Files impacted
* `app/src/main/res/layout/item_notification.xml`
* `app/src/main/java/org/torproject/android/ui/mesh/NotificationsAdapter.kt`
* `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`

### Detailed changes

#### Layout modification

**Before** (`item_notification.xml`, lines ~3-17):
```xml
<com.google.android.material.card.MaterialCardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginStart="16dp"
    android:layout_marginEnd="16dp"
    android:layout_marginTop="4dp"
    android:layout_marginBottom="4dp"
    app:cardElevation="2dp"
    app:cardCornerRadius="8dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="12dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">

            <TextView
                android:id="@+id/notificationSenderText"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="From: Node 123"
                android:textStyle="bold"
                android:textSize="14sp" />

            <TextView
                android:id="@+id/notificationTimestampText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Feb 08, 15:18"
                android:textSize="12sp"
                android:textColor="?android:attr/textColorSecondary" />

        </LinearLayout>
```

**After**: insert an `ImageButton` for close next to the timestamp.
```xml
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">

            <TextView
                android:id="@+id/notificationSenderText"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="From: Node 123"
                android:textStyle="bold"
                android:textSize="14sp" />

            <ImageButton
                android:id="@+id/notificationCloseButton"
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:src="@android:drawable/ic_menu_close_clear_cancel"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="Close notification" />

            <TextView
                android:id="@+id/notificationTimestampText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Feb 08, 15:18"
                android:textSize="12sp"
                android:textColor="?android:attr/textColorSecondary" />

        </LinearLayout>
```

#### Adapter modifications

**Before** (`NotificationsAdapter.kt` constructor/viewholder):
```kotlin
class NotificationsAdapter(
    private val notifications: List<NotificationFeedEntry>
) : RecyclerView.Adapter<NotificationsAdapter.NotificationViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    inner class NotificationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val senderText: TextView = itemView.findViewById(R.id.notificationSenderText)
        val messageText: TextView = itemView.findViewById(R.id.notificationMessageText)
        val locationText: TextView = itemView.findViewById(R.id.notificationLocationText)
        val fileText: TextView = itemView.findViewById(R.id.notificationFileText)
        val timestampText: TextView = itemView.findViewById(R.id.notificationTimestampText)
        val errorText: TextView = itemView.findViewById(R.id.notificationErrorText)
    }
```

**After** (add callback and close button reference):
```kotlin
class NotificationsAdapter(
    private val notifications: List<NotificationFeedEntry>,
    private val onDismiss: (NotificationFeedEntry) -> Unit
) : RecyclerView.Adapter<NotificationsAdapter.NotificationViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    inner class NotificationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val senderText: TextView = itemView.findViewById(R.id.notificationSenderText)
        val closeButton: android.widget.ImageButton = itemView.findViewById(R.id.notificationCloseButton)
        val messageText: TextView = itemView.findViewById(R.id.notificationMessageText)
        val locationText: TextView = itemView.findViewById(R.id.notificationLocationText)
        val fileText: TextView = itemView.findViewById(R.id.notificationFileText)
        val timestampText: TextView = itemView.findViewById(R.id.notificationTimestampText)
        val errorText: TextView = itemView.findViewById(R.id.notificationErrorText)
    }
```

Also add to `onBindViewHolder` inside the `BROADCAST` case:
```kotlin
holder.closeButton.setOnClickListener { onDismiss(notification) }
```

#### Fragment helper

Add method to `EnhancedMeshFragment`:
```kotlin
private fun removeNotification(entry: NotificationFeedEntry) {
    when (entry.type) {
        NotificationType.BROADCAST ->
            broadcastNotifications.value = broadcastNotifications.value.filter { it.id != entry.id }
        NotificationType.STATUS ->
            statusNotifications.value = statusNotifications.value.filter { it.id != entry.id }
        NotificationType.STORAGE ->
            storageNotifications.value = storageNotifications.value.filter { it.id != entry.id }
        else -> {}
    }
}
```

And instantiate adapter with the callback:
```kotlin
notificationsAdapter = NotificationsAdapter(emptyList()) { entry -> removeNotification(entry) }
``` 

---

## Issue 2: File path formatting

### (unrelated section placeholder, will be removed later)



### Goal
Display a human‑readable filesystem path instead of the raw `content://` URI.

### Change required
Modify the binding logic in `NotificationsAdapter.onBindViewHolder` within
`NotificationType.BROADCAST` section.

**Before**:
```kotlin
if (!notification.filePath.isNullOrBlank()) {
    holder.fileText.visibility = View.VISIBLE
    holder.fileText.text = "📎 ${notification.filePath}"
} else {
    holder.fileText.visibility = View.GONE
}
```

**After**:
```kotlin
if (!notification.filePath.isNullOrBlank()) {
    holder.fileText.visibility = View.VISIBLE
    val displayPath = try {
        (holder.itemView.context as? EnhancedMeshFragmentHost)
            ?.getFilePathFromUri(Uri.parse(notification.filePath))
            ?: notification.filePath
    } catch (_: Exception) {
        notification.filePath
    }
    holder.fileText.text = "📎 $displayPath"
} else {
    holder.fileText.visibility = View.GONE
}
```

*Note:* define `interface EnhancedMeshFragmentHost { fun getFilePathFromUri(uri: Uri): String? }` in `EnhancedMeshFragment.kt` and have the fragment implement it.

**Implementation details:**

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`

**Before** (near imports and class header):
```kotlin
import kotlinx.coroutines.flow.stateIn

/**
 * EnhancedMeshFragment: Mesh UI fragment using MeshrabiyaApi for all mesh logic.
 */
class EnhancedMeshFragment : Fragment() {
```

**After:**
```kotlin
import kotlinx.coroutines.flow.stateIn

interface EnhancedMeshFragmentHost {
    fun getFilePathFromUri(uri: Uri): String?
}

/**
 * EnhancedMeshFragment: Mesh UI fragment using MeshrabiyaApi for all mesh logic.
 */
class EnhancedMeshFragment : Fragment(), EnhancedMeshFragmentHost {
```

Also update the existing helper within the class:

**Before:**
```kotlin
    /**
     * Convert content:// URI to file system path
     * Required for configuring drop folder with meshrabiya API
     */
    private fun getFilePathFromUri(uri: Uri): String? {
```

**After:**
```kotlin
    /**
     * Convert content:// URI to file system path
     * Required for configuring drop folder with meshrabiya API
     */
    override fun getFilePathFromUri(uri: Uri): String? {
```

This ensures the fragment satisfies the interface contract so the adapter's
cast will succeed.


---

## Issue 3: Open folder button

### Goal
Add a button next to the file text that launches the system file manager at the
containing folder.

### Changes
1. Add `ImageButton` with id `notificationOpenButton` to the layout just after
   the file TextView.
2. Add field in `NotificationViewHolder` and initialize it.
3. Populate in `onBindViewHolder` with click listener shown earlier.

**Layout before/after** (snippet around file text):
```xml
        <TextView
            android:id="@+id/notificationFileText"
            ... />
```
becomes:
```xml
        <TextView
            android:id="@+id/notificationFileText"
            ... />

        <ImageButton
            android:id="@+id/notificationOpenButton"
            android:layout_width="24dp"
            android:layout_height="24dp"
            android:src="@android:drawable/ic_menu_view"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Open folder"
            android:visibility="gone" />
``` 

**Adapter binding snippet** (after fileText handling):
```kotlin
if (!notification.filePath.isNullOrBlank()) {
    holder.openButton.visibility = View.VISIBLE
    holder.openButton.setOnClickListener {
        try {
            val fileUri = Uri.parse(notification.filePath)
            val folderUri = fileUri.buildUpon()
                .path(fileUri.path?.substringBeforeLast('/'))
                .build()
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(folderUri, "resource/folder")
                flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            holder.itemView.context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("NotificationsAdapter", "open folder failed", e)
        }
    }
} else {
    holder.openButton.visibility = View.GONE
}
```

---

## Issue 4: Copy coordinates button

### Goal
Provide a button that copies `latitude,longitude` to clipboard.

### Changes
1. Add `ImageButton` with id `notificationCopyButton` next to
   `notificationLocationText` in the layout.
2. Add field in `NotificationViewHolder`.
3. Update binding logic:
```kotlin
if (notification.latitude != null && notification.longitude != null) {
    holder.locationText.visibility = View.VISIBLE
    holder.locationText.text = "📍 ${notification.latitude}, ${notification.longitude}"
    holder.copyButton.visibility = View.VISIBLE
    holder.copyButton.setOnClickListener {
        val clip = ClipData.newPlainText(
            "coords","${notification.latitude},${notification.longitude}")
        val cm = holder.itemView.context
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(clip)
        Toast.makeText(holder.itemView.context,
            "Coordinates copied",Toast.LENGTH_SHORT).show()
    }
} else {
    holder.locationText.visibility = View.GONE
    holder.copyButton.visibility = View.GONE
}
```

---

## StateFlow pattern

The `notificationFeed` flow is created by `combine(broadcastNotifications,
statusNotifications, storageNotifications)` and therefore automatically
reflects any filtering performed by the removal helper described above.  The
adapter’s `submitList` method writes the new list back into its private field
and calls `notifyDataSetChanged()`.  No changes to the flow logic are needed.

---

Each of the before/after code snippets above provides the precise context and
should be sufficient for you to implement the fixes reliably.  Once you apply
these patches, rebuild and verify the new buttons and formatting in the app.

*End of plan.*
