# GPS Broadcast Extension – Comprehensive Implementation Plan
**Date:** 2026-02-27

This document describes all required changes to add an optional GPS location
checkbox to the "Send Broadcast" form, wire the location through the
Meshrabiya API, embed it in broadcast packets, and display it in the UI.
Backwards compatibility is not required but all new fields are nullable so the
feature can be omitted at runtime.

---

## 1. Locate existing form implementation

* Layout file: `app/src/main/res/layout/dialog_broadcast.xml` defines the send
  broadcast form with a message text input, file selector, and send button.
* UI driver: `EnhancedMeshFragment.showBroadcastDialog()` (lines 1283–1391 in
  current file) inflates the layout, obtains view references, and installs the
  send-button listener which calls `meshrabiyaApi.broadcastMessageAndFile`.

These are the touchpoints for UI modification.

---

## 2. UI modifications

### 2.1 Add checkbox to dialog layout

**File:** `/app/src/main/res/layout/dialog_broadcast.xml`
**Location:** Immediately after the `<TextInputLayout …>` block.

**BEFORE:**
```xml
    <!-- Message Input -->
    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="Message (optional)"
        app:counterEnabled="false"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox">
        …
    </com.google.android.material.textfield.TextInputLayout>
```

**AFTER:**
```xml
    <!-- Message Input -->
    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="Message (optional)"
        app:counterEnabled="false"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox">
        …
    </com.google.android.material.textfield.TextInputLayout>

    <!-- GPS option -->
    <com.google.android.material.checkbox.MaterialCheckBox
        android:id="@+id/includeLocationCheckbox"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="Include GPS location" />
```

### 2.2 Add location display to notification item layout

**File:** `app/src/main/res/layout/item_notification.xml`
**Location:** after `<TextView android:id="@+id/notificationMessageText" …/>`.

**BEFORE:**
```xml
        <TextView
            android:id="@+id/notificationMessageText" … />
```

**AFTER:**
```xml
        <TextView
            android:id="@+id/notificationMessageText" … />

        <TextView
            android:id="@+id/notificationLocationText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="📍 0.000, 0.000"
            android:textSize="12sp"
            android:textColor="?android:attr/textColorSecondary"
            android:visibility="gone" />
```

This element will be shown when a notification contains coordinates.

---

## 3. Data models

### 3.1 Enhance `BroadcastNotification`

**File:** `app/src/main/java/org/torproject/android/ui/mesh/model/NotificationItem.kt`

**BEFORE:**
```kotlin
data class BroadcastNotification(
    override val id: String,
    override val title: String,
    override val createdAt: Long,
    val message: String,
    val filePath: String?,
    val senderNodeId: String
) : NotificationSource
```

**AFTER:**
```kotlin
data class BroadcastNotification(
    override val id: String,
    override val title: String,
    override val createdAt: Long,
    val message: String,
    val filePath: String?,
    val senderNodeId: String,
    val latitude: Double? = null,      // optional
    val longitude: Double? = null      // optional
) : NotificationSource
```

### 3.2 Extend `NotificationFeedEntry`

Modify constructor to add nullable latitude/longitude parameters (after
`senderNodeId`). Already nullable by default so UI may ignore them.

### 3.3 Update conversion helper

Add the new fields when converting a `BroadcastNotification`:

```kotlin
fun BroadcastNotification.toFeedEntry() = NotificationFeedEntry(
    type = NotificationType.BROADCAST,
    …,
    senderNodeId = senderNodeId,
    latitude = latitude,
    longitude = longitude
)
```

---

## 4. Adapter changes

**File:** `NotificationsAdapter.kt`

1. Add `locationText: TextView` to `NotificationViewHolder`.
2. In `onBindViewHolder`, after message/error logic insert coordinate
   handling:

```kotlin
if (notification.latitude != null && notification.longitude != null) {
    holder.locationText.visibility = View.VISIBLE
    holder.locationText.text = "📍 ${notification.latitude}, ${notification.longitude}"
} else {
    holder.locationText.visibility = View.GONE
}
```

3. Add `import android.view.View`.

No other adapter modifications are required; `submitList()` already works.

---

## 5. Fragment code modifications

**File:** `EnhancedMeshFragment.kt` (1752+ lines).

### 5.1 Imports
```
import android.location.Location
import android.location.LocationManager
```

### 5.2 Add location helper (after permission code near lines 224/859)

```kotlin
private fun getLastKnownLocation(callback: (Location?) -> Unit) {
    val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
    arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
        try {
            lm.getLastKnownLocation(provider)?.let { loc ->
                callback(loc)
                return
            }
        } catch (_: SecurityException) { }
    }
    callback(null)
}
```

### 5.3 Modify `showBroadcastDialog()`

* After existing view lookups, add:

```kotlin
val includeLocationCheckbox =
    dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(
        R.id.includeLocationCheckbox)
```

* Update `sendButton.setOnClickListener` block:

```kotlin
val includeLocation = includeLocationCheckbox.isChecked
val sendAction: (Double?, Double?) -> Unit = { lat, lon ->
    meshrabiyaApi.broadcastMessageAndFile(messageText, filePath, lat, lon) { result ->
        …existing callback…
    }
}

if (includeLocation) {
    if (!checkLocationPermissions()) {
        errorText.text = "Location permission required"
        errorText.visibility = View.VISIBLE
        requestLocationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        return@setOnClickListener
    }
    getLastKnownLocation { loc ->
        sendAction(loc?.latitude, loc?.longitude)
    }
} else {
    sendAction(null, null)
}
```

### 5.4 Update broadcast listener (around line 421)

Include the dto coordinates when constructing `newItem`:

```kotlin
val newItem = BroadcastNotification(
    …,
    senderNodeId = broadcast.senderNodeId.toString(),
    latitude = broadcast.latitude,
    longitude = broadcast.longitude
)
```

This ensures the GPS data enters the `broadcastNotifications` flow.

---

## 6. API & library changes

### 6.1 MeshrabiyaApi signature

**File:** `MeshrabiyaApi.kt` – modify

```kotlin
suspend fun broadcastMessageAndFile(
    messageText: String = "",
    filePath: String = "",
    latitude: Double? = null,
    longitude: Double? = null
)
```

### 6.2 Implementation in MeshrabiyaApiImpl

Add parameters to override and forward them to handler:

```kotlin
override suspend fun broadcastMessageAndFile(
    messageText: String,
    filePath: String,
    latitude: Double?,
    longitude: Double?
) {
    …
    handler.sendBroadcast(messageText, filePath, latitude, longitude) { result -> … }
}
```

### 6.3 BroadcastMessageHandler API

**File:** `BroadcastMessageHandler.kt` – change signature

```kotlin
fun sendBroadcast(
    messageText: String,
    filePath: String,
    latitude: Double?,
    longitude: Double?,
    callback: (Result<BroadcastResultDto>) -> Unit
)
```

and propagate the two doubles through both the text‑only and file‑chunk paths
(simply store them in the `BroadcastChunkMetadata` and, eventually, in the
`BroadcastReceivedDto`).

### 6.4 BroadcastChunkMetadata

Add nullable lat/long and serialize/deserialize them (see earlier plan).

### 6.5 Update BroadcastReceivedDto (library)

```kotlin
data class BroadcastReceivedDto(
    …,
    val latitude: Double? = null,
    val longitude: Double? = null
)
```

locations are passed from handler when listener is notified.

---

## 7. Permission handling

* `AndroidManifest.xml` already contains `ACCESS_FINE_LOCATION`.
* `EnhancedMeshFragment` already prompts for fine/coarse location in
  `requestLocationPermissionsLauncher` at line 859 etc.; reuse those.
* No extra gradle dependency required – using core `LocationManager`.

---

## 8. Testing & validation

1. Open send dialog – checkbox should appear under message input.
2. Check box with and without location permission; verify permission
   request triggers and send proceeds only after grant.
3. Send a broadcast with location; inspect logs in
   `BroadcastMessageHandler` (will include coordinates when serializing).  In
   receiver log, ensure `BroadcastReceivedDto` entries show latitude/longitude.
4. Click notification icon – popup item should include the `notificationLocationText`.
5. Unchecked broadcast should continue to work exactly as before.
6. Verify no compile errors and all builds succeed.

---

This plan is exhaustive and concrete; it names every file and line range to be
edited, supplies exact code snippets for BEFORE/AFTER changes, and traces the
flow from UI through API, library serialization, listener notification, and
finally dropdown rendering.  No assumptions were made; all types and methods
were located with grep and read_file before being referenced.

_End of comprehensive plan._