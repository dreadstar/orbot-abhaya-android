# MESH JOIN PLAN - PART 4: UI IMPLEMENTATION

## Overview

This part details the UI changes needed to add QR code joining functionality to the mesh network interface.

### Design Requirements

✅ Move "Mesh Start/Stop" button inside expanding pane header  
✅ Add "Join Mesh" button next to Start/Stop  
✅ Expandable pane shows QR code OR camera preview (mutually exclusive)  
✅ QR code displayed when mesh CONNECTED/CONNECTING  
✅ Camera preview displayed when "Join Mesh" pressed  
✅ Request camera permission upfront in permissions flow  
✅ After successful join, show QR of joined network  

---

## Layout Changes

### Current Layout

**File:** `app/src/main/res/layout/fragment_enhanced_mesh.xml`

**Lines 260-277: Current Mesh Button (Standalone)**
```xml
<!-- Mesh Control Button -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:layout_marginBottom="16dp"
    android:gravity="center">

    <com.google.android.material.button.MaterialButton
        android:id="@+id/meshToggleButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Start Mesh"
        android:paddingHorizontal="32dp"
        android:textColor="@android:color/white"
        app:backgroundTint="#BB86FC"
        app:cornerRadius="8dp"
        style="@style/Widget.Material3.Button" />

</LinearLayout>
```

### New Layout with Expandable Pane

**Replace Lines 260-277 with:**

```xml
<!-- Mesh Control Card with Expandable QR/Camera Pane -->
<com.google.android.material.card.MaterialCardView
    android:id="@+id/meshControlCard"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:cardCornerRadius="16dp"
    app:cardElevation="4dp"
    app:cardBackgroundColor="?attr/colorSurface"
    android:layout_marginBottom="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <!-- Header with Buttons (Always Visible) -->
        <LinearLayout
            android:id="@+id/meshControlHeader"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:padding="16dp"
            android:gravity="center_vertical"
            android:background="?attr/selectableItemBackground">

            <!-- Start/Stop Mesh Button -->
            <com.google.android.material.button.MaterialButton
                android:id="@+id/meshToggleButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Start Mesh"
                android:textColor="@android:color/white"
                app:backgroundTint="#BB86FC"
                app:cornerRadius="8dp"
                android:paddingVertical="12dp"
                style="@style/Widget.Material3.Button" />

            <!-- Join Mesh Button (enabled when mesh DISCONNECTED) -->
            <com.google.android.material.button.MaterialButton
                android:id="@+id/joinMeshButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="8dp"
                android:text="Join Mesh"
                android:enabled="false"
                android:paddingVertical="12dp"
                app:cornerRadius="8dp"
                app:strokeColor="?attr/colorPrimary"
                app:strokeWidth="1dp"
                style="@style/Widget.Material3.Button.OutlinedButton" />

            <!-- Merge Mesh Button (enabled when mesh CONNECTED) -->
            <com.google.android.material.button.MaterialButton
                android:id="@+id/mergeMeshButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="8dp"
                android:text="Merge Mesh"
                android:enabled="false"
                android:paddingVertical="12dp"
                app:cornerRadius="8dp"
                app:strokeColor="?attr/colorSecondary"
                app:strokeWidth="1dp"
                style="@style/Widget.Material3.Button.OutlinedButton" />

            <!-- Expand/Collapse Indicator -->
            <ImageView
                android:id="@+id/expandCollapseIndicator"
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:layout_marginStart="8dp"
                android:src="@drawable/ic_expand_more"
                android:contentDescription="Expand/Collapse"
                android:visibility="gone"
                app:tint="?attr/colorOnSurface" />

        </LinearLayout>

        <!-- Expandable Content (QR or Camera) -->
        <LinearLayout
            android:id="@+id/meshExpandableContent"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="16dp"
            android:visibility="gone"
            android:animateLayoutChanges="true">

            <!-- QR Code Display Container -->
            <LinearLayout
                android:id="@+id/qrCodeContainer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:gravity="center"
                android:visibility="gone">

                <TextView
                    android:id="@+id/qrCodeTitle"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Scan to Join Network"
                    android:textAppearance="@style/TextAppearance.Material3.TitleMedium"
                    android:textColor="?attr/colorOnSurface"
                    android:layout_marginBottom="8dp" />

                <TextView
                    android:id="@+id/qrCodeSubtitle"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Other devices can scan this code to join"
                    android:textAppearance="@style/TextAppearance.Material3.BodySmall"
                    android:textColor="?attr/colorOnSurfaceVariant"
                    android:layout_marginBottom="16dp" />

                <!-- QR Code Image -->
                <com.google.android.material.card.MaterialCardView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    app:cardCornerRadius="12dp"
                    app:cardElevation="2dp"
                    app:cardBackgroundColor="@android:color/white">

                    <ImageView
                        android:id="@+id/qrCodeImageView"
                        android:layout_width="280dp"
                        android:layout_height="280dp"
                        android:scaleType="fitCenter"
                        android:padding="16dp"
                        android:contentDescription="QR Code for mesh network" />

                </com.google.android.material.card.MaterialCardView>

                <!-- Network Info -->
                <TextView
                    android:id="@+id/qrCodeNetworkInfo"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="SSID: --"
                    android:textAppearance="@style/TextAppearance.Material3.BodyMedium"
                    android:textColor="?attr/colorOnSurface"
                    android:layout_marginTop="16dp"
                    android:fontFamily="monospace" />

                <!-- Copy Info Button -->
                <com.google.android.material.button.MaterialButton
                    android:id="@+id/copyNetworkInfoButton"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:text="Copy Network Info"
                    app:icon="@drawable/ic_content_copy"
                    style="@style/Widget.Material3.Button.TextButton" />

            </LinearLayout>

            <!-- Camera Preview Container -->
            <LinearLayout
                android:id="@+id/cameraPreviewContainer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:visibility="gone">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Scan QR Code to Join"
                    android:textAppearance="@style/TextAppearance.Material3.TitleMedium"
                    android:textColor="?attr/colorOnSurface"
                    android:layout_marginBottom="8dp"
                    android:layout_gravity="center" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Point camera at another device's QR code"
                    android:textAppearance="@style/TextAppearance.Material3.BodySmall"
                    android:textColor="?attr/colorOnSurfaceVariant"
                    android:layout_marginBottom="16dp"
                    android:layout_gravity="center" />

                <!-- Camera Preview -->
                <com.google.android.material.card.MaterialCardView
                    android:layout_width="match_parent"
                    android:layout_height="300dp"
                    app:cardCornerRadius="12dp"
                    app:cardElevation="2dp"
                    android:layout_gravity="center">

                    <androidx.camera.view.PreviewView
                        android:id="@+id/cameraPreviewView"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        android:scaleType="centerCrop" />

                    <!-- Scanning Overlay -->
                    <View
                        android:id="@+id/scanningOverlay"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        android:background="@drawable/qr_scan_overlay"
                        android:visibility="gone" />

                </com.google.android.material.card.MaterialCardView>

                <!-- Scanning Status -->
                <TextView
                    android:id="@+id/scanningStatusText"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:text="Ready to scan"
                    android:textAppearance="@style/TextAppearance.Material3.BodyMedium"
                    android:textColor="?attr/colorOnSurfaceVariant"
                    android:layout_gravity="center" />

                <!-- Action Buttons -->
                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center"
                    android:layout_marginTop="16dp"
                    android:orientation="horizontal">

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/cancelScanButton"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="Cancel"
                        style="@style/Widget.Material3.Button.TextButton" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/toggleFlashlightButton"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="12dp"
                        android:text="Flashlight"
                        app:icon="@drawable/ic_flashlight_on"
                        style="@style/Widget.Material3.Button.TextButton" />

                </LinearLayout>

            </LinearLayout>

        </LinearLayout>

    </LinearLayout>

</com.google.android.material.card.MaterialCardView>
```

### Required Drawable Resources

**Create:** `app/src/main/res/drawable/ic_expand_more.xml`
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z"/>
</vector>
```

**Create:** `app/src/main/res/drawable/ic_content_copy.xml`
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z"/>
</vector>
```

**Create:** `app/src/main/res/drawable/ic_flashlight_on.xml`
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M6,2l0.01,6L10,12v10h4V12l3.99,-4L18,2H6zM12,11.5L9.5,8.5V4h5v4.5L12,11.5z"/>
</vector>
```

**Create:** `app/src/main/res/drawable/qr_scan_overlay.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Semi-transparent background -->
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#80000000"/>
        </shape>
    </item>
    <!-- Scanning frame -->
    <item
        android:top="50dp"
        android:bottom="50dp"
        android:left="50dp"
        android:right="50dp">
        <shape android:shape="rectangle">
            <stroke
                android:width="4dp"
                android:color="#4CAF50"/>
            <corners android:radius="12dp"/>
        </shape>
    </item>
</layer-list>
```

---

---

## Button State Management

The three-button layout has distinct state rules based on mesh connection status:

| Mesh Status | Start/Stop Button | Join Mesh Button | Merge Mesh Button |
|-------------|------------------|------------------|-------------------|
| **DISCONNECTED** | "Start Mesh" (enabled) | **Enabled** | Disabled |
| **CONNECTING** | "Stop Mesh" (enabled) | Disabled | Disabled |
| **CONNECTED** | "Stop Mesh" (enabled) | Disabled | **Enabled** |

### UX Intent

- **Join Mesh**: Used when NOT on a mesh - scans QR to join a new mesh network
- **Merge Mesh**: Used when ALREADY on a mesh - scans QR to merge current mesh with target mesh

### State Update Logic

**Add to EnhancedMeshFragment:**
```kotlin
private fun updateButtonStates(meshStatus: MeshStatus) {
    when (meshStatus) {
        MeshStatus.DISCONNECTED -> {
            bindings.startStopButton.text = "Start Mesh"
            bindings.startStopButton.isEnabled = true
            bindings.joinMeshButton.isEnabled = true  // Can join when disconnected
            bindings.mergeMeshButton.isEnabled = false // Cannot merge when not on mesh
        }
        MeshStatus.CONNECTING -> {
            bindings.startStopButton.text = "Stop Mesh"
            bindings.startStopButton.isEnabled = true
            bindings.joinMeshButton.isEnabled = false  // Busy connecting
            bindings.mergeMeshButton.isEnabled = false // Busy connecting
        }
        MeshStatus.CONNECTED -> {
            bindings.startStopButton.text = "Stop Mesh"
            bindings.startStopButton.isEnabled = true
            bindings.joinMeshButton.isEnabled = false  // Already on mesh
            bindings.mergeMeshButton.isEnabled = true  // Can merge with another mesh
        }
    }
}
```

**Call from `onMeshStatusChanged()`:**
```kotlin
override fun onMeshStatusChanged(status: MeshStatus) {
    lifecycleScope.launch(Dispatchers.Main) {
        updateButtonStates(status) // Update Join/Merge button states
        // ... rest of status update logic
    }
}
```

---

## Fragment Implementation

### MeshUIBindings Updates

**File:** `app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt`

**Add New View References (after line 24):**
```kotlin
// Mesh control card
lateinit var meshControlCard: MaterialCardView
lateinit var meshControlHeader: LinearLayout
lateinit var expandCollapseIndicator: ImageView

// Mesh control buttons
lateinit var joinMeshButton: MaterialButton
lateinit var mergeMeshButton: MaterialButton  // NEW: Separate merge button

// Expandable content
lateinit var meshExpandableContent: LinearLayout

// QR code container
lateinit var qrCodeContainer: LinearLayout
lateinit var qrCodeTitle: TextView
lateinit var qrCodeSubtitle: TextView
lateinit var qrCodeImageView: ImageView
lateinit var qrCodeNetworkInfo: TextView
lateinit var copyNetworkInfoButton: MaterialButton

// Camera preview container
lateinit var cameraPreviewContainer: LinearLayout
lateinit var cameraPreviewView: androidx.camera.view.PreviewView
lateinit var scanningOverlay: View
lateinit var scanningStatusText: TextView
lateinit var cancelScanButton: MaterialButton
lateinit var toggleFlashlightButton: MaterialButton
```

**Add Binding Initialization (after line 61):**
```kotlin
// Mesh control card
meshControlCard = view.findViewById(R.id.meshControlCard)
meshControlHeader = view.findViewById(R.id.meshControlHeader)
expandCollapseIndicator = view.findViewById(R.id.expandCollapseIndicator)

// Mesh control buttons
joinMeshButton = view.findViewById(R.id.joinMeshButton)
mergeMeshButton = view.findViewById(R.id.mergeMeshButton)  // NEW: Merge button binding

// Expandable content
meshExpandableContent = view.findViewById(R.id.meshExpandableContent)

// QR code container
qrCodeContainer = view.findViewById(R.id.qrCodeContainer)
qrCodeTitle = view.findViewById(R.id.qrCodeTitle)
qrCodeSubtitle = view.findViewById(R.id.qrCodeSubtitle)
qrCodeImageView = view.findViewById(R.id.qrCodeImageView)
qrCodeNetworkInfo = view.findViewById(R.id.qrCodeNetworkInfo)
copyNetworkInfoButton = view.findViewById(R.id.copyNetworkInfoButton)

// Camera preview container
cameraPreviewContainer = view.findViewById(R.id.cameraPreviewContainer)
cameraPreviewView = view.findViewById(R.id.cameraPreviewView)
scanningOverlay = view.findViewById(R.id.scanningOverlay)
scanningStatusText = view.findViewById(R.id.scanningStatusText)
cancelScanButton = view.findViewById(R.id.cancelScanButton)
toggleFlashlightButton = view.findViewById(R.id.toggleFlashlightButton)
```

### EnhancedMeshFragment Changes

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`

**New Imports (add after existing imports):**
```kotlin
// QR Code generation
import io.github.g0dkar.qrcode.QRCode
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor

// Camera and ML Kit
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat as AndroidContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

// System
import android.widget.ImageView
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.ByteArrayOutputStream
```

**New Class Properties (add after line 49):**
```kotlin
// QR code and camera support
private lateinit var cameraExecutor: ExecutorService
private var isCameraActive = false
private var isJoinMeshMode = false     // True = Join (DISCONNECTED), False = Merge (CONNECTED)
private var isMergeMeshMode = false    // NEW: Track if scanning for merge vs. join
private var isFlashlightOn = false
private var currentCamera: androidx.camera.core.Camera? = null
private var lastScannedQRCode: String? = null
private var scanCooldownEndTime: Long = 0
```

**Modified onCreate() - Initialize camera executor (add after line 103):**
```kotlin
// Initialize camera executor for QR scanning
cameraExecutor = Executors.newSingleThreadExecutor()
```

**Add onDestroyView() for cleanup:**
```kotlin
override fun onDestroyView() {
    super.onDestroyView()
    
    if (isCameraActive) {
        stopQRScanning()
    }
    
    cameraExecutor.shutdown()
}
```

---

## Next Steps

See MESH_JOIN_PLAN-PT5.md for button handlers, QR generation, and camera scanning implementation.
