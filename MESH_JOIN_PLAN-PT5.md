# MESH JOIN PLAN - PART 5: FRAGMENT LOGIC & QR/CAMERA

## Button Handlers

### Join Mesh Button Handler

**Add to EnhancedMeshFragment.kt after existing button setup (~line 381):**

```kotlin
// ========================================
// JOIN MESH BUTTON HANDLER
// ========================================
bindings.joinMeshButton.setOnClickListener {
    android.util.Log.d("EnhancedMeshFragment", "Join Mesh button clicked")
    
    // Set mode: Join (not merge)
    isJoinMeshMode = true
    isMergeMeshMode = false
    
    // Expand pane and start camera for QR scanning
    expandPane(showCamera = true)
    startQRScanning()
}

// ========================================
// MERGE MESH BUTTON HANDLER
// ========================================
bindings.mergeMeshButton.setOnClickListener {
    android.util.Log.d("EnhancedMeshFragment", "Merge Mesh button clicked")
    
    val meshStatus = meshrabiyaApi.getMeshStatus()
    
    // Safety check: Should only be enabled when CONNECTED, but verify
    if (meshStatus != MeshStateDto.CONNECTED) {
        android.util.Log.w("EnhancedMeshFragment", "Merge Mesh clicked but not CONNECTED (status=$meshStatus)")
        showToast("Cannot merge - not connected to a mesh")
        return@setOnClickListener
    }
    
    // Set mode: Merge (not join)
    isJoinMeshMode = false
    isMergeMeshMode = true
    
    // Expand pane and start camera for QR scanning
    expandPane(showCamera = true)
    startQRScanning()
}

// ========================================
// CANCEL SCAN BUTTON HANDLER
// ========================================
bindings.cancelScanButton.setOnClickListener {
    android.util.Log.d("EnhancedMeshFragment", "Cancel scan button clicked")
    collapsePane()
}

// ========================================
// TOGGLE FLASHLIGHT BUTTON HANDLER
// ========================================
bindings.toggleFlashlightButton.setOnClickListener {
    toggleFlashlight()
}

// ========================================
// COPY NETWORK INFO BUTTON HANDLER
// ========================================
bindings.copyNetworkInfoButton.setOnClickListener {
    copyNetworkInfoToClipboard()
}

// ========================================
// HEADER CLICK TO TOGGLE EXPANSION
// ========================================
bindings.meshControlHeader.setOnClickListener {
    // Only allow expansion if mesh is CONNECTED or CONNECTING
    val meshStatus = meshrabiyaApi.getMeshStatus()
    if (meshStatus == MeshStateDto.CONNECTED || meshStatus == MeshStateDto.CONNECTING) {
        if (bindings.meshExpandableContent.visibility == View.VISIBLE) {
            collapsePane()
        } else {
            // Show QR code (not camera)
            expandPane(showCamera = false)
            showCurrentNetworkQR()
        }
    }
}
```

---

## Pane Control Methods

### Expand and Collapse

**Add after button handlers:**

```kotlin
/**
 * Expand the mesh control pane to show QR code or camera
 * @param showCamera If true, show camera preview. If false, show QR code.
 */
private fun expandPane(showCamera: Boolean) {
    android.util.Log.d("EnhancedMeshFragment", "expandPane(showCamera=$showCamera)")
    
    // Show expandable content
    MeshUIBindings.meshExpandableContent.visibility = View.VISIBLE
    
    // Show appropriate container
    if (showCamera) {
        MeshUIBindings.qrCodeContainer.visibility = View.GONE
        MeshUIBindings.cameraPreviewContainer.visibility = View.VISIBLE
        MeshUIBindings.expandCollapseIndicator.rotation = 180f  // Point up
    } else {
        MeshUIBindings.cameraPreviewContainer.visibility = View.GONE
        MeshUIBindings.qrCodeContainer.visibility = View.VISIBLE
        MeshUIBindings.expandCollapseIndicator.rotation = 180f  // Point up
    }
    
    // Show expand/collapse indicator
    MeshUIBindings.expandCollapseIndicator.visibility = View.VISIBLE
}

/**
 * Collapse the mesh control pane
 */
private fun collapsePane() {
    android.util.Log.d("EnhancedMeshFragment", "collapsePane()")
    
    // Stop camera if active
    if (isCameraActive) {
        stopQRScanning()
    }
    
    // Hide expandable content
    bindings.meshExpandableContent.visibility = View.GONE
    bindings.expandCollapseIndicator.rotation = 0f  // Point down
    
    // Reset mode flags
    isJoinMeshMode = false
    isMergeMeshMode = false
}
```

---

## QR Code Generation

### Generate and Display QR Code

**Add helper method:**

```kotlin
/**
 * Generate mesh-wide QR code for joining
 * Uses custom JSON format for mesh discovery (not standard WiFi QR)
 * Enables resilient joining - device scans for ANY mesh hotspot, not just one specific SSID
 */
private fun generateAndDisplayQRCode(currentSsid: String, password: String) {
    android.util.Log.d("EnhancedMeshFragment", "generateAndDisplayQRCode(currentSsid=$currentSsid)")
    
    try {
        // Create mesh discovery QR code data
        // Format: JSON containing shared password and SSID pattern
        // This allows devices to connect to ANY mesh hotspot, not just this one
        val qrData = buildString {
            append("{")
            append("\"type\":\"mesh_join\",")  // Identifies this as mesh joining QR
            append("\"password\":\"$password\",")  // Shared password for all mesh hotspots
            append("\"ssidPattern\":\"meshr-*\",")  // Pattern to match (for WiFi scanning)
            append("\"bootstrapSSID\":\"$currentSsid\"")
            append("}")
        }
        
        android.util.Log.d("EnhancedMeshFragment", "QR data: $qrData")
        
        // Generate QR code using qrcode-kotlin library
        val qrCode = QRCode(qrData)
        
        // Render to byte array (25 pixels per module, 4 module margin)
        val qrBytes = qrCode.render(
            cellSize = 25,   // Pixel size of each QR module
            margin = 4,      // Quiet zone (modules)
            brightColor = AndroidColor.WHITE,
            darkColor = AndroidColor.BLACK
        )
        
        // Convert byte array to Bitmap
        val qrSize = qrCode.computeImageSize(cellSize = 25, margin = 4)
        val bitmap = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.RGB_565)
        
        var byteIndex = 0
        for (y in 0 until qrSize) {
            for (x in 0 until qrSize) {
                if (byteIndex < qrBytes.size) {
                    // qrBytes contains RGB values
                    val r = qrBytes[byteIndex++].toInt() and 0xFF
                    val g = qrBytes[byteIndex++].toInt() and 0xFF
                    val b = qrBytes[byteIndex++].toInt() and 0xFF
                    val color = AndroidColor.rgb(r, g, b)
                    bitmap.setPixel(x, y, color)
                }
            }
        }
        
        // Display on UI thread
        activity?.runOnUiThread {
            MeshUIBindings.qrCodeImageView.setImageBitmap(bitmap)
            MeshUIBindings.qrCodeNetworkInfo.text = "Mesh Password: $password\nCurrent Hotspot: $currentSsid"
            
            // Update title based on context
            if (isJoinMeshMode) {
                MeshUIBindings.qrCodeTitle.text = "Joined Mesh Network"
                MeshUIBindings.qrCodeSubtitle.text = "Connected - will auto-reconnect if hotspot changes"
            } else {
                MeshUIBindings.qrCodeTitle.text = "Scan to Join Mesh"
                MeshUIBindings.qrCodeSubtitle.text = "Works with any available hotspot - automatic failover"
            }
        }
        
        android.util.Log.d("EnhancedMeshFragment", "QR code generated successfully")
        
    } catch (e: Exception) {
        android.util.Log.e("EnhancedMeshFragment", "Error generating QR code", e)
        activity?.runOnUiThread {
            Snackbar.make(
                requireView(),
                "Failed to generate QR code: ${e.message}",
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }
}

/**
 * Show QR code for mesh-wide joining (not just current hotspot)
 */
private fun showCurrentNetworkQR() {
    android.util.Log.d("EnhancedMeshFragment", "showCurrentNetworkQR()")
    
    lifecycleScope.launch {
        try {
            val hotspotInfo = meshrabiyaApi.getHotspotInfo()
            
            if (hotspotInfo != null) {
                android.util.Log.d("EnhancedMeshFragment", 
                    "Got hotspot info: ssid=${hotspotInfo.ssid}")
                // Generate mesh-wide QR (not device-specific)
                generateAndDisplayQRCode(hotspotInfo.ssid, hotspotInfo.password)
            } else {
                android.util.Log.w("EnhancedMeshFragment", 
                    "No hotspot info available (mesh may not be running)")
                activity?.runOnUiThread {
                    Snackbar.make(
                        requireView(),
                        "No mesh network active",
                        Snackbar.LENGTH_SHORT
                    ).show()
                    collapsePane()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("EnhancedMeshFragment", "Error getting hotspot info", e)
            activity?.runOnUiThread {
                Snackbar.make(
                    requireView(),
                    "Failed to get network info: ${e.message}",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }
}

/**
 * Copy network info to clipboard
 */
private fun copyNetworkInfoToClipboard() {
    android.util.Log.d("EnhancedMeshFragment", "copyNetworkInfoToClipboard()")
    
    lifecycleScope.launch {
        try {
            val hotspotInfo = meshrabiyaApi.getHotspotInfo()
            
            if (hotspotInfo != null) {
                val networkInfo = "SSID: ${hotspotInfo.ssid}\nPassword: ${hotspotInfo.password}"
                
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) 
                    as ClipboardManager
                val clip = ClipData.newPlainText("Mesh Network Info", networkInfo)
                clipboard.setPrimaryClip(clip)
                
                activity?.runOnUiThread {
                    Snackbar.make(
                        requireView(),
                        "Network info copied to clipboard",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("EnhancedMeshFragment", "Error copying network info", e)
        }
    }
}
```

---

## Camera Scanning Implementation

### Start QR Scanning

**Add camera control methods:**

```kotlin
/**
 * Start camera preview and QR code scanning
 */
private fun startQRScanning() {
    android.util.Log.d("EnhancedMeshFragment", "startQRScanning()")
    
    // Check camera permission
    if (!hasCameraPermission()) {
        android.util.Log.w("EnhancedMeshFragment", "Camera permission not granted")
        requestCameraPermission()
        return
    }
    
    val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
    
    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()
            
            // Preview use case
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(MeshUIBindings.cameraPreviewView.surfaceProvider)
                }
            
            // Image analysis for barcode scanning
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QRCodeAnalyzer { qrData ->
                        handleScannedQRCode(qrData)
                    })
                }
            
            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()
            
            // Bind use cases to camera
            currentCamera = cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            
            isCameraActive = true
            android.util.Log.d("EnhancedMeshFragment", "Camera started successfully")
            
            // Update UI
            activity?.runOnUiThread {
                MeshUIBindings.scanningStatusText.text = "Ready to scan"
            }
            
        } catch (e: Exception) {
            android.util.Log.e("EnhancedMeshFragment", "Camera binding failed", e)
            activity?.runOnUiThread {
                Snackbar.make(
                    requireView(),
                    "Failed to start camera: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
                collapsePane()
            }
        }
    }, AndroidContextCompat.getMainExecutor(requireContext()))
}

/**
 * Stop camera preview and scanning
 */
private fun stopQRScanning() {
    android.util.Log.d("EnhancedMeshFragment", "stopQRScanning()")
    
    try {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
            isCameraActive = false
            currentCamera = null
            isFlashlightOn = false
            android.util.Log.d("EnhancedMeshFragment", "Camera stopped")
        }, AndroidContextCompat.getMainExecutor(requireContext()))
    } catch (e: Exception) {
        android.util.Log.e("EnhancedMeshFragment", "Error stopping camera", e)
    }
}

/**
 * Toggle camera flashlight
 */
private fun toggleFlashlight() {
    android.util.Log.d("EnhancedMeshFragment", "toggleFlashlight()")
    
    currentCamera?.let { camera ->
        if (camera.cameraInfo.hasFlashUnit()) {
            isFlashlightOn = !isFlashlightOn
            camera.cameraControl.enableTorch(isFlashlightOn)
            
            MeshUIBindings.toggleFlashlightButton.text = if (isFlashlightOn) {
                "Flashlight: ON"
            } else {
                "Flashlight: OFF"
            }
            
            android.util.Log.d("EnhancedMeshFragment", "Flashlight toggled: $isFlashlightOn")
        } else {
            Snackbar.make(
                requireView(),
                "Device has no flashlight",
                Snackbar.LENGTH_SHORT
            ).show()
        }
    } ?: run {
        android.util.Log.w("EnhancedMeshFragment", "Camera not active, cannot toggle flashlight")
    }
}
```

### QR Code Analyzer (ML Kit)

**Add inner class:**

```kotlin
/**
 * ML Kit barcode analyzer for QR code scanning
 * Detects QR codes in camera frames and extracts data
 */
private inner class QRCodeAnalyzer(
    private val onQRCodeDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {
    
    private val scanner = BarcodeScanning.getClient()
    private var lastAnalysisTime = 0L
    private val analysisInterval = 300L  // Analyze every 300ms (not every frame)
    
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        
        // Throttle analysis to avoid overprocessing
        if (currentTime - lastAnalysisTime < analysisInterval) {
            imageProxy.close()
            return
        }
        
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )
            
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        if (barcode.format == Barcode.FORMAT_QR_CODE) {
                            barcode.rawValue?.let { qrData ->
                                lastAnalysisTime = currentTime
                                android.util.Log.d("QRCodeAnalyzer", "QR code detected: $qrData")
                                onQRCodeDetected(qrData)
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    android.util.Log.e("QRCodeAnalyzer", "Barcode scanning failed", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}
```

### Handle Scanned QR Code

**Add handler method:**

```kotlin
/**
 * Handle QR code data after successful scan
 * Parses mesh join JSON format and initiates mesh-wide discovery
 */
private fun handleScannedQRCode(qrData: String) {
    android.util.Log.d("EnhancedMeshFragment", "handleScannedQRCode: $qrData")
    
    // Check cooldown to prevent duplicate scans
    val currentTime = System.currentTimeMillis()
    if (currentTime < scanCooldownEndTime) {
        android.util.Log.d("EnhancedMeshFragment", "Scan in cooldown, ignoring")
        return
    }
    
    // Check if this is the same QR we just scanned
    if (qrData == lastScannedQRCode) {
        android.util.Log.d("EnhancedMeshFragment", "Same QR code, ignoring duplicate")
        return
    }
    
    // Parse mesh join JSON format: {"type":"mesh_join","password":"...","ssidPattern":"meshr-*","bootstrapSSID":"..."}
    try {
        val qrJson = org.json.JSONObject(qrData)
        val type = qrJson.optString("type", "")
        
        if (type != "mesh_join") {
            android.util.Log.w("EnhancedMeshFragment", "Invalid QR code type: $type")
            activity?.runOnUiThread {
                Snackbar.make(
                    requireView(),
                    "Invalid mesh QR code - wrong type",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            return
        }
        
        val password = qrJson.getString("password")
        val bootstrapSsid = qrJson.optString("bootstrapSSID", "unknown")
        
        android.util.Log.d("EnhancedMeshFragment", "Parsed mesh join QR - password: $password, bootstrap: $bootstrapSsid")
        
        // Set cooldown and remember this QR
        scanCooldownEndTime = currentTime + 3000L  // 3 second cooldown
        lastScannedQRCode = qrData
        
        // Update UI on main thread
        activity?.runOnUiThread {
            MeshUIBindings.scanningStatusText.text = "Scanning for mesh hotspots..."
            MeshUIBindings.scanningOverlay.visibility = View.VISIBLE
        }
        
        // Stop camera
        stopQRScanning()
        
        // Determine which API to call based on mode
        lifecycleScope.launch {
            try {
                if (isMergeMeshMode) {
                    // MERGE MODE: User is on a mesh and wants to merge with another
                    android.util.Log.d("EnhancedMeshFragment", "Calling meshrabiyaApi.mergeMesh() with JSON data")
                    
                    meshrabiyaApi.mergeMesh(qrData) { result ->
                        activity?.runOnUiThread {
                            if (result.isSuccess) {
                                android.util.Log.d("EnhancedMeshFragment", "Merge mesh succeeded - announcement broadcast and connected")
                                
                                Snackbar.make(
                                    requireView(),
                                    "Successfully merged meshes! Other devices will join automatically.",
                                    Snackbar.LENGTH_LONG
                                ).show()
                                
                                // Collapse camera pane
                                collapsePane()
                                updateUI()
                                
                            } else {
                                android.util.Log.e("EnhancedMeshFragment", 
                                    "Merge mesh failed: ${result.exceptionOrNull()?.message}")
                                
                                Snackbar.make(
                                    requireView(),
                                    "Mesh merge failed. ${result.exceptionOrNull()?.message}",
                                    Snackbar.LENGTH_LONG
                                ).show()
                                
                                collapsePane()
                                lastScannedQRCode = null
                            }
                        }
                    }
                    
                } else {
                    // JOIN MODE: User is not on a mesh, joining a new one
                    android.util.Log.d("EnhancedMeshFragment", "Calling meshrabiyaApi.joinMesh() with JSON data")
                    
                    meshrabiyaApi.joinMesh(qrData) { result ->
                        activity?.runOnUiThread {
                            if (result.isSuccess) {
                                android.util.Log.d("EnhancedMeshFragment", "Join mesh succeeded - connected to available hotspot")
                                
                                Snackbar.make(
                                    requireView(),
                                    "Successfully joined mesh network!",
                                    Snackbar.LENGTH_LONG
                                ).show()
                                
                                // Collapse camera pane
                                collapsePane()
                                
                                // Update UI to reflect new connection
                                updateUI()
                                
                                // After brief delay, show QR of connected network
                                // (displays current hotspot SSID for informational purposes)
                                lifecycleScope.launch {
                                    delay(1000L)
                                    expandPane(showCamera = false)
                                    showCurrentNetworkQR()
                                }
                                
                            } else {
                                android.util.Log.e("EnhancedMeshFragment", 
                                    "Join mesh failed: ${result.exceptionOrNull()?.message}")
                                
                                Snackbar.make(
                                    requireView(),
                                    "No mesh hotspots found. Try again later.",
                                    Snackbar.LENGTH_LONG
                                ).show()
                                
                                // Reset state
                                collapsePane()
                                lastScannedQRCode = null
                            }
                        }
                    }
                }
                
            } catch (e: Exception) {
                android.util.Log.e("EnhancedMeshFragment", "Exception during mesh operation: ${e.message}")
                activity?.runOnUiThread {
                    Snackbar.make(
                        requireView(),
                        "Error: ${e.message}",
                        Snackbar.LENGTH_LONG
                    ).show()
                    collapsePane()
                }
                            scanCooldownEndTime = 0
                        }
                    }
                }
                
            } catch (e: Exception) {
                android.util.Log.e("EnhancedMeshFragment", "Exception joining mesh", e)
                activity?.runOnUiThread {
                    Snackbar.make(
                        requireView(),
                        "Error joining mesh: ${e.message}",
                        Snackbar.LENGTH_LONG
                    ).show()
                    collapsePane()
                }
            }
        }
        
    } catch (e: org.json.JSONException) {
        android.util.Log.w("EnhancedMeshFragment", "Invalid JSON in QR code", e)
        
        activity?.runOnUiThread {
            MeshUIBindings.scanningStatusText.text = "Invalid mesh QR code. Try again."
            
            Snackbar.make(
                requireView(),
                "Invalid QR code format",
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }
}
```

---

## Permission Handling

### Camera Permission

**Add to EnhancedMeshFragment:**

```kotlin
companion object {
    private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
}

/**
 * Check if camera permission is granted
 */
private fun hasCameraPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        requireContext(),
        android.Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
}

/**
 * Request camera permission
 */
private fun requestCameraPermission() {
    android.util.Log.d("EnhancedMeshFragment", "Requesting camera permission")
    
    requestPermissions(
        arrayOf(android.Manifest.permission.CAMERA),
        CAMERA_PERMISSION_REQUEST_CODE
    )
}

/**
 * Handle permission request result
 */
override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    
    when (requestCode) {
        CAMERA_PERMISSION_REQUEST_CODE -> {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("EnhancedMeshFragment", "Camera permission granted")
                
                // Permission granted - start scanning
                if (isJoinMeshMode) {
                    startQRScanning()
                }
            } else {
                android.util.Log.w("EnhancedMeshFragment", "Camera permission denied")
                
                Snackbar.make(
                    requireView(),
                    "Camera permission required to scan QR codes",
                    Snackbar.LENGTH_LONG
                ).setAction("Settings") {
                    // Open app settings
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", requireContext().packageName, null)
                    }
                    startActivity(intent)
                }.show()
                
                collapsePane()
            }
        }
    }
}
```

### Add to AndroidManifest.xml

**File:** `app/src/main/AndroidManifest.xml`

**Add camera permission (if not already present):**
```xml
<!-- Camera permission for QR code scanning -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
```

---

## Updated updateUI() Method

### Show QR Automatically When Connected

**Modify updateUI() method (around line 460):**

```kotlin
// Update button states based on mesh status
val meshActive = meshState == MeshStateDto.CONNECTED || meshState == MeshStateDto.CONNECTING
MeshUIBindings.meshToggleButton.text = if (meshActive) "Stop Mesh" else "Start Mesh"

// Enable Join Mesh button when mesh is active (or always enabled per user request)
// User clarification: Join should work even from DISCONNECTED state
MeshUIBindings.joinMeshButton.isEnabled = true

// Show expand indicator when mesh is active
MeshUIBindings.expandCollapseIndicator.visibility = if (meshActive) View.VISIBLE else View.GONE

// Auto-show QR code when mesh becomes active (if pane not already expanded)
if (meshActive && 
    !isJoinMeshMode && 
    MeshUIBindings.meshExpandableContent.visibility != View.VISIBLE) {
    
    lifecycleScope.launch {
        // Brief delay to let hotspot fully start
        delay(500L)
        
        val hotspotInfo = meshrabiyaApi.getHotspotInfo()
        if (hotspotInfo != null) {
            activity?.runOnUiThread {
                expandPane(showCamera = false)
                generateAndDisplayQRCode(hotspotInfo.ssid, hotspotInfo.password)
            }
        }
    }
}

// Collapse pane if mesh stopped
if (!meshActive && MeshUIBindings.meshExpandableContent.visibility == View.VISIBLE) {
    collapsePane()
}
```

---

## Summary

### UI Components Added

✅ Expandable MaterialCardView for mesh controls  
✅ Join Mesh button next to Start/Stop  
✅ QR code display container with network info  
✅ Camera preview container with scanning overlay  
✅ Copy network info button  
✅ Flashlight toggle button  
✅ Cancel scan button  

### Functionality Implemented

✅ QR code generation (WiFi format)  
✅ QR code display for own/joined network  
✅ Camera preview with ML Kit barcode scanning  
✅ QR code parsing and validation  
✅ Mesh join via scanned credentials  
✅ Camera permission handling  
✅ Flashlight control  
✅ Clipboard copy for network info  
✅ Scan cooldown to prevent duplicates  
✅ Graceful error handling  

### Next Steps

See MESH_JOIN_PLAN-PT6.md for API implementation (MeshrabiyaApi interface, MeshrabiyaApiImpl, DTOs).
