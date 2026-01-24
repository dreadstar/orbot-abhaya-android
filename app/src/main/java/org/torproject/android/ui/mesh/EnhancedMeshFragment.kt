package org.torproject.android.ui.mesh

import org.torproject.android.R
// import com.ustadmobile.meshrabiya.model.MeshState

import android.Manifest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.ustadmobile.meshrabiya.api.MeshrabiyaApi
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import com.ustadmobile.meshrabiya.api.model.MeshStateDto
import com.ustadmobile.meshrabiya.api.model.DropFolderItemDto
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.documentfile.provider.DocumentFile

// QR Code generation and Camera scanning
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import org.json.JSONObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/**
 * EnhancedMeshFragment: Mesh UI fragment using MeshrabiyaApi for all mesh logic.
 * All mesh operations are routed through MeshrabiyaApi. No deprecated logic is used.
 */
class EnhancedMeshFragment : Fragment() {

	private lateinit var meshrabiyaApi: MeshrabiyaApi
	
	// Track whether deferred views (cards 4-9) have been initialized via ViewStub
	private var deferredViewsInitialized = false
	
	// Folder picker for storage allocation
	private lateinit var folderPickerLauncher: ActivityResultLauncher<Uri?>
	private var selectedFolderUri: Uri? = null
	
	// Flags to prevent recursive toggle updates from programmatic changes
	private var isStorageToggleProgrammatic = false
	private var isServiceToggleProgrammatic = false
	
	// QR code and camera support
	private lateinit var cameraExecutor: ExecutorService
	private var isCameraActive = false
	private var isJoinMeshMode = false     // True when Join Mesh button clicked
	private var isMergeMeshMode = false    // True when Merge Mesh button clicked
	private var isFlashlightOn = false
	private var currentCamera: androidx.camera.core.Camera? = null
	private var currentBarcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner? = null
	private var lastScannedQRCode: String? = null
	private var scanCooldownEndTime: Long = 0
	
	companion object {
		private const val PREF_STORAGE_FOLDER_URI = "mesh_storage_folder_uri"
		private const val PREF_STORAGE_QUOTA_BYTES = "mesh_storage_quota_bytes"
		private const val DEFAULT_STORAGE_QUOTA = 100_000_000L // 100MB default
		private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
	}
	
	// Permission launcher for runtime location permission requests
	private val requestLocationPermissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions()
	) { permissions ->
		val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
		val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
		
		if (fineLocationGranted && coarseLocationGranted) {
			// Permissions granted, retry starting mesh
			android.util.Log.d("EnhancedMeshFragment", "Location permissions granted, retrying startMesh()")
			startMeshWithPermissionCheck()
		} else {
			// Permissions denied, show message
			android.util.Log.w("EnhancedMeshFragment", "Location permissions denied by user")
			view?.let { v ->
				Snackbar.make(v, "Location permissions are required to start mesh networking", Snackbar.LENGTH_LONG)
					.setAction("Settings") {
						// Optional: Open app settings
					}
					.show()
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] ===== onCreate() called =====")
		
		// Initialize camera executor for QR scanning
		cameraExecutor = Executors.newSingleThreadExecutor()
		
		// Initialize folder picker launcher
		folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
			uri?.let {
				// Persist permissions for the selected folder
				requireContext().contentResolver.takePersistableUriPermission(
					it,
					android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
					android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
				)
				
				selectedFolderUri = it
				
				// Save to preferences
				requireActivity().getPreferences(android.content.Context.MODE_PRIVATE).edit()
					.putString(PREF_STORAGE_FOLDER_URI, it.toString())
					.apply()
				
				android.util.Log.d("EnhancedMeshFragment", "Folder selected: $it")
				
				// Update storage allocation
				updateStorageAllocation(it)
				
				// Update UI to show selected folder
				updateUI()
			}
		}
	}
	
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		val view = inflater.inflate(R.layout.fragment_mesh_enhanced, container, false)
		// Only bind immediate views (cards 1-3), deferred views bound after ViewStub inflation
		MeshUIBindings.bindImmediateViews(view)
		return view
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] ===== onViewCreated() CALLED =====")
		// Get MeshrabiyaApi singleton (already initialized in OrbotApp.onCreate)
		meshrabiyaApi = MeshrabiyaApiImpl.getInstance()
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] MeshrabiyaApi obtained")
		
		// NOTE: initMesh() is called ONLY in OrbotApp.onCreate() at app startup.
		// Fragment just uses the already-initialized mesh infrastructure.
		
		// Setup all UI event listeners (button clicks, toggles, etc.)
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Setting up listeners...")
		setupListeners()
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Listeners setup complete")
		
		// Setup observer for mesh roles StateFlow - auto-updates UI when roles change
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Setting up role observer...")
		setupRoleObserver()
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Role observer setup complete")
		
		// Setup observer for network info StateFlow - auto-updates peer count and network stats
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Setting up network info observer...")
		setupNetworkInfoObserver()
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Network info observer setup complete")
		
		// Initial UI update to show current mesh state
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Calling updateUI()...")
		updateUI()
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] ===== onViewCreated() COMPLETE =====")
		
		// Defer inflation of cards 4-9 to prevent UI thread blocking (async after 300ms)
		android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Scheduling deferred card inflation...")
		viewLifecycleOwner.lifecycleScope.launch {
			delay(300) // Allow initial UI to render first
			try {
				val stub = view.findViewById<android.view.ViewStub>(R.id.deferredCardsStub)
				if (stub != null) {
					android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Inflating deferred cards...")
					stub.inflate()
					android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Deferred cards inflated successfully")
					// Bind newly inflated deferred views (cards 4-9)
					android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Binding deferred views...")
					MeshUIBindings.bindDeferredViews(view)
					// Mark deferred views as initialized
					deferredViewsInitialized = true
					android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Deferred views bound, flag set to true")
					
					// Update network info UI with cached value if available
					latestNetworkInfo?.let { networkInfo ->
						android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Updating network info UI with cached value: connectedPeers=${networkInfo.connectedPeers}")
						MeshUIBindings.nodeInfoText.text = "IP: ${networkInfo.ipAddress}"
						MeshUIBindings.networkStatsText.text = 
							"Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
					}
					
					// Setup listeners for deferred cards
					android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Setting up deferred card listeners...")
					setupDeferredCardListeners()
					// Update UI for deferred cards
					android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Updating deferred card UI...")
					updateDeferredCardUI()
					android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Deferred card initialization complete")
				} else {
					android.util.Log.w("EnhancedMeshFragment", "[LIFECYCLE] ViewStub not found!")
				}
			} catch (e: Exception) {
				android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Failed to inflate deferred cards", e)
			}
		}
	}

	override fun onResume() {
		super.onResume()
		// Refresh UI when fragment becomes visible (tab switches, screen rotation, etc.)
		updateUI()
		
		// Start periodic UI updates (every 2 seconds) to refresh peer count and network stats
		viewLifecycleOwner.lifecycleScope.launch {
			while (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
				kotlinx.coroutines.delay(2000) // Update every 2 seconds
				updateUI()
			}
		}
	}
	
	override fun onDestroyView() {
		super.onDestroyView()
		
		// Stop camera if active
		if (isCameraActive) {
			stopQRScanning()
		}
		
		// Close barcode scanner to prevent memory leaks
		currentBarcodeScanner?.close()
		currentBarcodeScanner = null
		
		// Shutdown camera executor
		cameraExecutor.shutdown()
	}
	
	/**
	 * Setup observer for mesh roles StateFlow to automatically update UI when roles change
	 */
	private fun setupRoleObserver() {
		android.util.Log.d("EnhancedMeshFragment", "[ROLE_OBSERVER] Setting up role observer")
		viewLifecycleOwner.lifecycleScope.launch {
			(meshrabiyaApi as? MeshrabiyaApiImpl)?.currentMeshRolesFlow?.collect { roles ->
				android.util.Log.e("EnhancedMeshFragment", "[ROLE_OBSERVER] Roles changed: $roles")
				activity?.runOnUiThread {
					// Update roles text - show "Roles: --" when mesh not started or no roles determined yet
					val meshState = meshrabiyaApi.getMeshStatus()
					val meshStarted = meshState == MeshStateDto.CONNECTED || meshState == MeshStateDto.CONNECTING
					
					android.util.Log.d("EnhancedMeshFragment", "[ROLE_OBSERVER] Updating meshRolesText")
					MeshUIBindings.meshRolesText.text = if (!meshStarted) {
						"Roles: --" // Show label with placeholder when mesh not started
					} else if (roles.isNotEmpty()) {
						"Roles: ${roles.joinToString(", ")}"
					} else {
						"Roles: --" // Show label with placeholder when no roles determined yet
					}
					
					// Only update deferred views if they're initialized (after ViewStub inflation)
					if (deferredViewsInitialized) {
						android.util.Log.d("EnhancedMeshFragment", "[ROLE_OBSERVER] Updating deferred gateway status texts")
						val torGatewayEnabled = meshrabiyaApi.getTorGatewayStatus()
						MeshUIBindings.torGatewayStatus.text = if (torGatewayEnabled) "Enabled" else "Disabled"
						
						val internetGatewayEnabled = meshrabiyaApi.getInternetGatewayStatus()
						MeshUIBindings.internetGatewayStatus.text = if (internetGatewayEnabled) "Enabled" else "Disabled"
						
						android.util.Log.d("EnhancedMeshFragment", "[ROLE_OBSERVER] Deferred UI updated - Tor: $torGatewayEnabled, Internet: $internetGatewayEnabled")
					} else {
						android.util.Log.d("EnhancedMeshFragment", "[ROLE_OBSERVER] Skipping deferred view updates - not yet initialized")
					}
					
					android.util.Log.e("EnhancedMeshFragment", "[ROLE_OBSERVER] UI updated - meshStarted: $meshStarted, roles: ${roles.joinToString(", ")}")
				}
			}
		}
	}

	/**
	 * Setup observer for network info StateFlow to automatically update UI when network state changes
	 */
	private var latestNetworkInfo: NetworkInfoDto? = null
	
	private fun setupNetworkInfoObserver() {
		android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] Setting up network info observer")
		viewLifecycleOwner.lifecycleScope.launch {
			(meshrabiyaApi as? MeshrabiyaApiImpl)?.networkInfoFlow?.collect { networkInfo ->
				android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] Network info changed: connectedPeers=${networkInfo?.connectedPeers}, deferredViewsInitialized=$deferredViewsInitialized")
				
				// Store latest value for when deferred views are initialized
				latestNetworkInfo = networkInfo
				
				// Update UI if deferred views are ready
				if (deferredViewsInitialized && networkInfo != null) {
					activity?.runOnUiThread {
						android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] Updating UI with connectedPeers=${networkInfo.connectedPeers}")
						MeshUIBindings.nodeInfoText.text = "IP: ${networkInfo.ipAddress}"
						MeshUIBindings.networkStatsText.text = 
							"Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
					}
				}
			}
		}
	}

	private fun setupListeners() {
		// Mesh toggle button with debouncing and permission checks
		var meshOperationInProgress = false
		
		// Add touch listener to detect ALL touch events
		MeshUIBindings.meshToggleButton.setOnTouchListener { v, event ->
			android.util.Log.d("EnhancedMeshFragment", "[TOUCH] meshToggleButton touched: action=${event.action}, enabled=${v.isEnabled}, clickable=${v.isClickable}")
			false // Return false to allow click listener to also fire
		}
		
		MeshUIBindings.meshToggleButton.setOnClickListener {
			android.util.Log.d("EnhancedMeshFragment", "[CLICK] Mesh toggle button clicked")
			android.util.Log.d("EnhancedMeshFragment", "[CLICK] Button state: enabled=${MeshUIBindings.meshToggleButton.isEnabled}, clickable=${MeshUIBindings.meshToggleButton.isClickable}, meshOpInProgress=$meshOperationInProgress")
			
			// Prevent multiple concurrent operations
			if (meshOperationInProgress) {
				android.util.Log.w("EnhancedMeshFragment", "Mesh operation already in progress, ignoring click")
				return@setOnClickListener
			}
			
			val currentStatus = meshrabiyaApi.getMeshStatus()
			val meshActive = currentStatus == MeshStateDto.CONNECTED || currentStatus == MeshStateDto.CONNECTING
			android.util.Log.d("EnhancedMeshFragment", "Current mesh status: $currentStatus, meshActive=$meshActive")
			
			meshOperationInProgress = true
			MeshUIBindings.meshToggleButton.isEnabled = false
			android.util.Log.d("EnhancedMeshFragment", "Button disabled, operation marked in progress")
			
			if (meshActive) {
				// Stopping mesh doesn't require permissions
				android.util.Log.d("EnhancedMeshFragment", "Calling stopMesh()")
				meshrabiyaApi.stopMesh { result ->
					// Callback runs on background thread - must switch to main thread for UI updates
					activity?.runOnUiThread {
						android.util.Log.d("EnhancedMeshFragment", "stopMesh callback: success=${result.isSuccess}, error=${result.exceptionOrNull()}")
						meshOperationInProgress = false
						MeshUIBindings.meshToggleButton.isEnabled = true
						android.util.Log.d("EnhancedMeshFragment", "Button re-enabled, updating UI")
						updateUI()
					}
				}
			} else {
				// Starting mesh requires location permissions - check first
				android.util.Log.e("EnhancedMeshFragment", "========== START MESH BUTTON CLICKED ==========")
				android.util.Log.e("EnhancedMeshFragment", "This log MUST appear when Start Mesh is pressed")
				if (checkLocationPermissions()) {
					android.util.Log.e("EnhancedMeshFragment", "Permissions granted, calling meshrabiyaApi.startMesh()")
					meshrabiyaApi.startMesh { result ->
						// Callback runs on background thread - must switch to main thread for UI updates
						activity?.runOnUiThread {
							android.util.Log.d("EnhancedMeshFragment", "startMesh callback: success=${result.isSuccess}, error=${result.exceptionOrNull()}")
							if (result.isFailure) {
								android.util.Log.e("EnhancedMeshFragment", "startMesh failed", result.exceptionOrNull())
								view?.let { v ->
									val errorMessage = result.exceptionOrNull()?.message ?: "Unknown error"
									
									// Show AlertDialog for WiFi-related errors (more prominent)
									if (errorMessage.contains("WiFi") || errorMessage.contains("wifi")) {
										androidx.appcompat.app.AlertDialog.Builder(requireContext())
											.setTitle("⚠️ WiFi Must Be Disabled")
											.setMessage(errorMessage)
											.setPositiveButton("Open WiFi Settings") { _, _ ->
												try {
													startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
												} catch (e: Exception) {
													Snackbar.make(v, "Could not open WiFi settings", Snackbar.LENGTH_SHORT).show()
												}
											}
											.setNegativeButton("Cancel", null)
											.show()
									} else {
										// Show Snackbar for other errors
										Snackbar.make(v, "Failed to start mesh: $errorMessage", Snackbar.LENGTH_LONG).show()
									}
								}
							}
							meshOperationInProgress = false
							MeshUIBindings.meshToggleButton.isEnabled = true
							android.util.Log.d("EnhancedMeshFragment", "Button re-enabled, updating UI")
							updateUI()
						}
					}
				} else {
					// Permissions not granted, request them
					android.util.Log.d("EnhancedMeshFragment", "Permissions not granted, requesting now")
					meshOperationInProgress = false
					MeshUIBindings.meshToggleButton.isEnabled = true
					requestLocationPermissions()
				}
			}
		}

		// Refresh button
		MeshUIBindings.refreshButton.setOnClickListener {
			updateUI()
		}
		
		// ========================================
		// JOIN MESH BUTTON HANDLER
		// ========================================
		MeshUIBindings.joinMeshButton.setOnClickListener {
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
		MeshUIBindings.mergeMeshButton.setOnClickListener {
			android.util.Log.d("EnhancedMeshFragment", "Merge Mesh button clicked")
			
			val meshStatus = meshrabiyaApi.getMeshStatus()
			
			// Safety check: Should only be enabled when CONNECTED, but verify
			if (meshStatus != MeshStateDto.CONNECTED) {
				android.util.Log.w("EnhancedMeshFragment", "Merge Mesh clicked but not CONNECTED (status=$meshStatus)")
				view?.let { v ->
					Snackbar.make(v, "Cannot merge - not connected to a mesh", Snackbar.LENGTH_SHORT).show()
				}
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
		MeshUIBindings.cancelScanButton.setOnClickListener {
			android.util.Log.d("EnhancedMeshFragment", "Cancel scan button clicked")
			collapsePane()
		}
		
		// ========================================
		// TOGGLE FLASHLIGHT BUTTON HANDLER
		// ========================================
		MeshUIBindings.toggleFlashlightButton.setOnClickListener {
			toggleFlashlight()
		}
		
		// ========================================
		// COPY NETWORK INFO BUTTON HANDLER
		// ========================================
		MeshUIBindings.copyNetworkInfoButton.setOnClickListener {
			copyNetworkInfoToClipboard()
		}
		
		// ========================================
		// HEADER CLICK TO TOGGLE EXPANSION
		// ========================================
		MeshUIBindings.meshControlHeader.setOnClickListener {
			// Only allow expansion if mesh is CONNECTED or CONNECTING
			val meshStatus = meshrabiyaApi.getMeshStatus()
			if (meshStatus == MeshStateDto.CONNECTED || meshStatus == MeshStateDto.CONNECTING) {
				if (MeshUIBindings.meshExpandableContent.visibility == View.VISIBLE) {
					collapsePane()
				} else {
					// Show QR code (not camera)
					expandPane(showCamera = false)
					showCurrentNetworkQR()
				}
			}
		}
	}
	
	/**
	 * Check if location permissions are granted.
	 * Required for WiFi hotspot on Android 12+ (API 31+)
	 */
	private fun checkLocationPermissions(): Boolean {
		val fineLocation = ContextCompat.checkSelfPermission(
			requireContext(),
			Manifest.permission.ACCESS_FINE_LOCATION
		) == PackageManager.PERMISSION_GRANTED
		
		val coarseLocation = ContextCompat.checkSelfPermission(
			requireContext(),
			Manifest.permission.ACCESS_COARSE_LOCATION
		) == PackageManager.PERMISSION_GRANTED
		
		android.util.Log.d("EnhancedMeshFragment", "Permission check: FINE=$fineLocation, COARSE=$coarseLocation")
		return fineLocation && coarseLocation
	}
	
	/**
	 * Request location permissions from the user.
	 * Launches system permission dialog.
	 */
	private fun requestLocationPermissions() {
		android.util.Log.d("EnhancedMeshFragment", "Requesting location permissions")
		requestLocationPermissionLauncher.launch(
			arrayOf(
				Manifest.permission.ACCESS_FINE_LOCATION,
				Manifest.permission.ACCESS_COARSE_LOCATION
			)
		)
	}
	
	/**
	 * Start mesh with permission check already completed.
	 * Called after permissions are granted via the launcher callback.
	 */
	private fun startMeshWithPermissionCheck() {
		if (!checkLocationPermissions()) {
			android.util.Log.e("EnhancedMeshFragment", "startMeshWithPermissionCheck called but permissions still not granted!")
			return
		}
		
		android.util.Log.d("EnhancedMeshFragment", "Calling startMesh() after permission grant")
		var meshOperationInProgress = true
		MeshUIBindings.meshToggleButton.isEnabled = false
		
		meshrabiyaApi.startMesh { result ->
			// Callback runs on background thread - must switch to main thread for UI updates
			activity?.runOnUiThread {
				android.util.Log.d("EnhancedMeshFragment", "startMesh callback: success=${result.isSuccess}, error=${result.exceptionOrNull()}")
				if (result.isFailure) {
					android.util.Log.e("EnhancedMeshFragment", "startMesh failed", result.exceptionOrNull())
					view?.let { v ->
						val errorMessage = result.exceptionOrNull()?.message ?: "Unknown error"
						
						// Show AlertDialog for WiFi-related errors (more prominent)
						if (errorMessage.contains("WiFi") || errorMessage.contains("wifi")) {
							androidx.appcompat.app.AlertDialog.Builder(requireContext())
								.setTitle("⚠️ WiFi Must Be Disabled")
								.setMessage(errorMessage)
								.setPositiveButton("Open WiFi Settings") { _, _ ->
									try {
										startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
									} catch (e: Exception) {
										Snackbar.make(v, "Could not open WiFi settings", Snackbar.LENGTH_SHORT).show()
									}
								}
								.setNegativeButton("Cancel", null)
								.show()
						} else {
							// Show Snackbar for other errors
							Snackbar.make(v, "Failed to start mesh: $errorMessage", Snackbar.LENGTH_LONG).show()
						}
					}
				}
				meshOperationInProgress = false
				MeshUIBindings.meshToggleButton.isEnabled = true
				android.util.Log.d("EnhancedMeshFragment", "Button re-enabled, updating UI")
				updateUI()
			}
		}
	}

	private fun updateUI() {
		android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] Called (deferredViewsInitialized=$deferredViewsInitialized)")
		
		// Ensure all UI updates happen on the main thread
		activity?.runOnUiThread {
			// Mesh status
			val meshState = meshrabiyaApi.getMeshStatus()
			android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] Current mesh state: $meshState")
			MeshUIBindings.meshStatusText.text = meshState.toString()
			
			// Update button states based on mesh status
			updateButtonStates(meshState)
			
			// Update button text based on current mesh state
			// Show "Stop Mesh" when mesh is active (CONNECTING or CONNECTED), "Start Mesh" when DISCONNECTED
			val meshActive = meshState == MeshStateDto.CONNECTED || meshState == MeshStateDto.CONNECTING
			MeshUIBindings.meshToggleButton.text = if (meshActive) "Stop Mesh" else "Start Mesh"
			android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] Button text updated to: ${MeshUIBindings.meshToggleButton.text}")

			// Mesh Roles - show current node roles with label (immediate view)
			val roles = meshrabiyaApi.getNodeRoleNames()
			MeshUIBindings.meshRolesText.text = if (!meshActive) {
				"Roles: --" // Show label with placeholder when mesh not started
			} else if (roles.isNotEmpty()) {
				"Roles: ${roles.joinToString(", ")}"
			} else {
				"Roles: --" // Show label with placeholder when no roles determined yet
			}

			// Last update
			val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
			MeshUIBindings.lastUpdateText.text = "Last Updated: ${dateFormat.format(Date())}"

			// Only update deferred views if they're initialized
			if (deferredViewsInitialized) {
				android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] Updating deferred views...")
				
				// Network Status - show local node IP address (deferred view)
				val networkInfo = meshrabiyaApi.getNetworkInfo()
				MeshUIBindings.nodeInfoText.text = if (networkInfo != null) {
					"IP: ${networkInfo.ipAddress}"
				} else {
					"Mesh not initialized"
				}

				// Network Information - show detailed network stats (deferred view)
				MeshUIBindings.networkStatsText.text = if (networkInfo != null) {
					"Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
				} else {
					"No network data"
				}

				// Gateway status text (read from dataStore, not active roles)
				val torGatewayEnabled = meshrabiyaApi.getTorGatewayStatus()
				// Only update toggle if it doesn't match (avoid triggering listener)
				if (MeshUIBindings.gatewayToggle.isChecked != torGatewayEnabled) {
					MeshUIBindings.gatewayToggle.isChecked = torGatewayEnabled
				}
				MeshUIBindings.torGatewayStatus.text = if (torGatewayEnabled) "Enabled" else "Disabled"
				
				val internetGatewayEnabled = meshrabiyaApi.getInternetGatewayStatus()
				if (MeshUIBindings.internetGatewayToggle.isChecked != internetGatewayEnabled) {
					MeshUIBindings.internetGatewayToggle.isChecked = internetGatewayEnabled
				}
				MeshUIBindings.internetGatewayStatus.text = if (internetGatewayEnabled) "Enabled" else "Disabled"

				// Storage participation toggle (read from persisted dataStore)
				val storageEnabled = meshrabiyaApi.getStorageParticipationStatus()
				if (MeshUIBindings.storageParticipationToggle.isChecked != storageEnabled) {
					isStorageToggleProgrammatic = true
					MeshUIBindings.storageParticipationToggle.isChecked = storageEnabled
					isStorageToggleProgrammatic = false
					android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] Storage toggle loaded from dataStore: $storageEnabled")
				}
				MeshUIBindings.storageStatusText.text = if (storageEnabled) "Storage participation is enabled" else "Storage participation is disabled"
				
				// Service participation toggle (read from persisted dataStore)
				val serviceEnabled = meshrabiyaApi.getServiceParticipationStatus("compute_node")
				if (MeshUIBindings.serviceLayerParticipationSwitch.isChecked != serviceEnabled) {
					isServiceToggleProgrammatic = true
					MeshUIBindings.serviceLayerParticipationSwitch.isChecked = serviceEnabled
					isServiceToggleProgrammatic = false
					android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] Service toggle loaded from dataStore: $serviceEnabled")
				}
				MeshUIBindings.serviceLayerStatusText.text = if (serviceEnabled) "Service Layer active..." else "Service Layer inactive..."
				
				// Storage allocation slider and folder path (load from persisted values)
			val prefs = requireActivity().getPreferences(android.content.Context.MODE_PRIVATE)
			val quotaBytes = prefs.getLong(PREF_STORAGE_QUOTA_BYTES, DEFAULT_STORAGE_QUOTA)
			val quotaGB = quotaBytes / (1024.0 * 1024.0 * 1024.0)
			// Clamp to slider's valid range (1-50 GB)
			val clampedQuotaGB = quotaGB.toFloat().coerceIn(1.0f, 50.0f)
			MeshUIBindings.storageAllocationSlider.value = clampedQuotaGB
			MeshUIBindings.storageAllocationText.text = "${clampedQuotaGB.toInt()} GB"
			android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] Loaded storage quota from preferences: ${quotaGB}GB (clamped to ${clampedQuotaGB}GB)")

			// Drop folder
			val dropFolder = meshrabiyaApi.getDropFolder()
			MeshUIBindings.selectedFolderText.text = dropFolder?.absolutePath ?: "No folder selected"

			// Mesh files
			val meshFiles = meshrabiyaApi.getAllMeshFiles()
			// TODO: Update folderContentsAdapter with meshFiles
			
			// Update folder path display
			val savedUri = prefs.getString(PREF_STORAGE_FOLDER_URI, null)
			if (savedUri != null) {
				val uri = Uri.parse(savedUri)
				val docFile = DocumentFile.fromTreeUri(requireContext(), uri)
				MeshUIBindings.selectedFolderText.text = docFile?.name ?: savedUri
			} else {
				MeshUIBindings.selectedFolderText.text = "No folder selected"
			}
			
			android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] Deferred views updated successfully")
		} else {
			android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] Skipping deferred view updates - not yet initialized")
		}
		} // End runOnUiThread
	} // End updateUI
	
	/**
	 * Update storage allocation for the selected folder URI
	 */
	private fun updateStorageAllocation(folderUri: Uri) {
		try {
			val docFile = DocumentFile.fromTreeUri(requireContext(), folderUri)
			if (docFile != null && docFile.isDirectory) {
				// Calculate available space (simplified - use default quota)
				val quotaBytes = DEFAULT_STORAGE_QUOTA
				
				// Save quota to preferences
				requireActivity().getPreferences(android.content.Context.MODE_PRIVATE).edit()
					.putLong(PREF_STORAGE_QUOTA_BYTES, quotaBytes)
					.apply()
				
				android.util.Log.i("EnhancedMeshFragment", "Storage allocation updated: ${quotaBytes / (1024 * 1024)}MB")
				
				// If storage participation is enabled, update the configuration
				if (meshrabiyaApi.getStorageParticipationStatus()) {
					meshrabiyaApi.setStorageParticipationEnabled(true) { _ ->
						android.util.Log.i("EnhancedMeshFragment", "Storage participation refreshed with new allocation")
					}
				}
			}
		} catch (e: Exception) {
			android.util.Log.e("EnhancedMeshFragment", "Error updating storage allocation", e)
		}
	}
	/**
	 * Create a new storage folder in app-specific storage
	 */
	private fun createStorageFolder(folderName: String) {
		try {
			val appDir = requireContext().getExternalFilesDir(null)
			if (appDir != null) {
				val newFolder = java.io.File(appDir, folderName)
				if (!newFolder.exists()) {
					if (newFolder.mkdirs()) {
						android.util.Log.i("EnhancedMeshFragment", "Created folder: ${newFolder.absolutePath}")
						
						// Convert to Uri and update storage
						val folderUri = Uri.fromFile(newFolder)
						selectedFolderUri = folderUri
						
						// Save to preferences
						requireActivity().getPreferences(android.content.Context.MODE_PRIVATE).edit()
							.putString(PREF_STORAGE_FOLDER_URI, folderUri.toString())
							.apply()
						
						updateStorageAllocation(folderUri)
						updateUI()
						
						Snackbar.make(requireView(), "Folder created: $folderName", Snackbar.LENGTH_SHORT).show()
					} else {
						Snackbar.make(requireView(), "Failed to create folder", Snackbar.LENGTH_SHORT).show()
					}
				} else {
					Snackbar.make(requireView(), "Folder already exists", Snackbar.LENGTH_SHORT).show()
				}
			}
		} catch (e: Exception) {
			android.util.Log.e("EnhancedMeshFragment", "Error creating folder", e)
			Snackbar.make(requireView(), "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
		}
	}
	
	// ========================================
	// PANE CONTROL METHODS
	// ========================================
	
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
		MeshUIBindings.meshExpandableContent.visibility = View.GONE
		MeshUIBindings.expandCollapseIndicator.rotation = 0f  // Point down
		
		// Reset mode flags
		isJoinMeshMode = false
		isMergeMeshMode = false
	}
	
	// ========================================
	// BUTTON STATE MANAGEMENT
	// ========================================
	
	/**
	 * Update button states based on mesh status
	 * Only displays Join or Merge button based on state (not both)
	 */
	private fun updateButtonStates(meshStatus: MeshStateDto) {
		android.util.Log.d("EnhancedMeshFragment", "[BUTTON_STATE] updateButtonStates called with status: $meshStatus")
		when (meshStatus) {
			MeshStateDto.DISCONNECTED -> {
				MeshUIBindings.meshToggleButton.text = "Start Mesh"
				MeshUIBindings.meshToggleButton.isEnabled = true
				android.util.Log.d("EnhancedMeshFragment", "[BUTTON_STATE] DISCONNECTED - button enabled, text='Start Mesh'")
				// Show only Join button when disconnected
				MeshUIBindings.joinMeshButton.visibility = View.VISIBLE
				MeshUIBindings.joinMeshButton.isEnabled = true
				MeshUIBindings.mergeMeshButton.visibility = View.GONE
				// Hide expand indicator when disconnected
				MeshUIBindings.expandCollapseIndicator.visibility = View.GONE
			}
			MeshStateDto.CONNECTING -> {
				MeshUIBindings.meshToggleButton.text = "Stop Mesh"
				MeshUIBindings.meshToggleButton.isEnabled = true
				android.util.Log.d("EnhancedMeshFragment", "[BUTTON_STATE] CONNECTING - button enabled, text='Stop Mesh', clickable=${MeshUIBindings.meshToggleButton.isClickable}")
				// Hide both buttons while connecting
				MeshUIBindings.joinMeshButton.visibility = View.GONE
				MeshUIBindings.mergeMeshButton.visibility = View.GONE
				// Show expand indicator for QR code when connecting/connected
				MeshUIBindings.expandCollapseIndicator.visibility = View.VISIBLE
			}
			MeshStateDto.CONNECTED -> {
				MeshUIBindings.meshToggleButton.text = "Stop Mesh"
				MeshUIBindings.meshToggleButton.isEnabled = true
				android.util.Log.d("EnhancedMeshFragment", "[BUTTON_STATE] CONNECTED - button enabled, text='Stop Mesh'")
				// Show only Merge button when connected
				MeshUIBindings.joinMeshButton.visibility = View.GONE
				MeshUIBindings.mergeMeshButton.visibility = View.VISIBLE
				MeshUIBindings.mergeMeshButton.isEnabled = true
				// Show expand indicator for QR code when connected
				MeshUIBindings.expandCollapseIndicator.visibility = View.VISIBLE
			}
			MeshStateDto.INITIALIZING,
			MeshStateDto.ERROR,
			MeshStateDto.UNKNOWN -> {
				MeshUIBindings.meshToggleButton.isEnabled = false
				android.util.Log.d("EnhancedMeshFragment", "[BUTTON_STATE] ${meshStatus} - button DISABLED")
				// Hide both buttons in error/unknown states
				MeshUIBindings.joinMeshButton.visibility = View.GONE
				MeshUIBindings.mergeMeshButton.visibility = View.GONE
				// Hide expand indicator in error states
				MeshUIBindings.expandCollapseIndicator.visibility = View.GONE
			}
		}
	}
	
	// ========================================
	// QR CODE GENERATION METHODS
	// ========================================
	
	/**
	 * Show current network QR code
	 */
	private fun showCurrentNetworkQR() {
		android.util.Log.d("EnhancedMeshFragment", "showCurrentNetworkQR() called")
		
		val hotspotInfo = meshrabiyaApi.getHotspotInfo()
		if (hotspotInfo != null) {
			generateAndDisplayQRCode(hotspotInfo.ssid, hotspotInfo.password, hotspotInfo.port)
			// Update network info text
			MeshUIBindings.qrCodeNetworkInfo.text = "Network: ${hotspotInfo.ssid}"
		} else {
			android.util.Log.w("EnhancedMeshFragment", "No hotspot info available")
			Snackbar.make(
				requireView(),
				"No active mesh network to display",
				Snackbar.LENGTH_SHORT
			).show()
			collapsePane()
		}
	}
	
	/**
	 * Generate and display QR code
	 */
	private fun generateAndDisplayQRCode(ssid: String, password: String, port: Int) {
		android.util.Log.d("EnhancedMeshFragment", "generateAndDisplayQRCode(ssid=$ssid, port=$port)")
		
		viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
			try {
				// Create JSON format for mesh join
				val qrJson = JSONObject().apply {
					put("type", "mesh_join")
					put("password", password)
					put("ssidPattern", "meshr-*")
					put("bootstrapSSID", ssid)
					put("port", port)
				}
				
				// Generate QR code using ZXing library
				val writer = QRCodeWriter()
				val bitMatrix = writer.encode(qrJson.toString(), BarcodeFormat.QR_CODE, 512, 512)
				val width = bitMatrix.width
				val height = bitMatrix.height
				val qrBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
				for (x in 0 until width) {
					for (y in 0 until height) {
						qrBitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
					}
				}
				
				// Display on UI thread
				withContext(Dispatchers.Main) {
					MeshUIBindings.qrCodeImageView.setImageBitmap(qrBitmap)
					MeshUIBindings.qrCodeNetworkInfo.text = "Network: $ssid"
					android.util.Log.d("EnhancedMeshFragment", "QR code displayed successfully")
				}
			} catch (e: Exception) {
				android.util.Log.e("EnhancedMeshFragment", "Failed to generate QR code", e)
				withContext(Dispatchers.Main) {
					Snackbar.make(
						requireView(),
						"Failed to generate QR code: ${e.message}",
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
		android.util.Log.d("EnhancedMeshFragment", "copyNetworkInfoToClipboard() called")
		
		val hotspotInfo = meshrabiyaApi.getHotspotInfo()
		if (hotspotInfo != null) {
			val networkInfo = "Mesh Network\nSSID: ${hotspotInfo.ssid}\nPassword: ${hotspotInfo.password}"
			
			val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
			val clip = ClipData.newPlainText("Mesh Network Info", networkInfo)
			clipboard.setPrimaryClip(clip)
			
			android.util.Log.d("EnhancedMeshFragment", "Network info copied to clipboard")
			Snackbar.make(
				requireView(),
				"Network info copied to clipboard",
				Snackbar.LENGTH_SHORT
			).show()
		} else {
			android.util.Log.w("EnhancedMeshFragment", "No hotspot info available to copy")
			Snackbar.make(
				requireView(),
				"No active mesh network",
				Snackbar.LENGTH_SHORT
			).show()
		}
	}
	
	// ========================================
	// CAMERA SCANNING METHODS
	// ========================================
	
	/**
	 * Start QR code scanning with camera
	 */
	private fun startQRScanning() {
		android.util.Log.d("EnhancedMeshFragment", "startQRScanning() called")
		
		// Check camera permission
		if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) 
			!= PackageManager.PERMISSION_GRANTED) {
			android.util.Log.w("EnhancedMeshFragment", "Camera permission not granted")
			requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
			return
		}
		
		// Setup CameraX with ML Kit barcode scanning (ASYNC to avoid blocking UI thread)
		val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
		cameraProviderFuture.addListener({
			try {
				// Use async listener result instead of blocking .get()
				val cameraProvider = cameraProviderFuture.get()
				
				// Preview use case
				val preview = Preview.Builder().build().also {
					it.setSurfaceProvider(MeshUIBindings.cameraPreviewView.surfaceProvider)
				}
				
				// Image analysis use case for barcode scanning
				val imageAnalysis = ImageAnalysis.Builder()
					.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
					.build()
				
				// ML Kit barcode scanner - Create NEW instance to prevent cached results
				// Close any existing scanner first
				currentBarcodeScanner?.close()
				currentBarcodeScanner = BarcodeScanning.getClient()
				val barcodeScanner = currentBarcodeScanner!!
				
				android.util.Log.d("EnhancedMeshFragment", "Created fresh BarcodeScanner instance")
				
				imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
					val mediaImage = imageProxy.image
					if (mediaImage != null) {
						val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
						
						barcodeScanner.process(inputImage)
							.addOnSuccessListener { barcodes ->
								for (barcode in barcodes) {
									barcode.rawValue?.let { qrData ->
										android.util.Log.d("EnhancedMeshFragment", "QR code scanned: $qrData")
										viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
											processQRCode(qrData)
										}
									}
								}
							}
							.addOnFailureListener { e ->
								android.util.Log.e("EnhancedMeshFragment", "Barcode scanning failed", e)
							}
							.addOnCompleteListener {
								imageProxy.close()
							}
					}
				}
				
				// Camera selector (back camera)
				val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
				
				// Unbind all use cases before rebinding
				cameraProvider.unbindAll()
				
				// Bind use cases to camera
				currentCamera = cameraProvider.bindToLifecycle(
					viewLifecycleOwner,
					cameraSelector,
					preview,
					imageAnalysis
				)
				
				isCameraActive = true
				MeshUIBindings.scanningStatusText.text = "Scanning for QR codes..."
				android.util.Log.d("EnhancedMeshFragment", "Camera started successfully")
				
			} catch (e: Exception) {
				android.util.Log.e("EnhancedMeshFragment", "Failed to start camera", e)
				Snackbar.make(
					requireView(),
					"Failed to start camera: ${e.message}",
					Snackbar.LENGTH_SHORT
				).show()
			}
		}, ContextCompat.getMainExecutor(requireContext()))
	}
	
	/**
	 * Stop QR code scanning (async to avoid blocking UI thread)
	 */
	private fun stopQRScanning() {
		android.util.Log.d("EnhancedMeshFragment", "stopQRScanning() called")
		
		// Turn off flashlight if active
		if (isFlashlightOn) {
			currentCamera?.cameraControl?.enableTorch(false)
			isFlashlightOn = false
		}
		
		// Close barcode scanner to clear any cached results
		currentBarcodeScanner?.close()
		currentBarcodeScanner = null
		android.util.Log.d("EnhancedMeshFragment", "Closed BarcodeScanner instance")
		
		// Unbind camera asynchronously
		viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
			try {
				val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
				// Use addListener for async unbinding instead of blocking .get()
				cameraProviderFuture.addListener({
					try {
						val cameraProvider = cameraProviderFuture.get()
						cameraProvider.unbindAll()
						android.util.Log.d("EnhancedMeshFragment", "Camera stopped successfully")
					} catch (e: Exception) {
						android.util.Log.e("EnhancedMeshFragment", "Failed to unbind camera", e)
					}
				}, ContextCompat.getMainExecutor(requireContext()))
			} catch (e: Exception) {
				android.util.Log.e("EnhancedMeshFragment", "Failed to stop camera", e)
			}
		}
		
		isCameraActive = false
		currentCamera = null
		MeshUIBindings.scanningStatusText.text = ""
	}
	
	/**
	 * Toggle flashlight on/off
	 */
	private fun toggleFlashlight() {
		android.util.Log.d("EnhancedMeshFragment", "toggleFlashlight() called")
		
		if (currentCamera != null && isCameraActive) {
			isFlashlightOn = !isFlashlightOn
			currentCamera?.cameraControl?.enableTorch(isFlashlightOn)
			android.util.Log.d("EnhancedMeshFragment", "Flashlight ${if (isFlashlightOn) "ON" else "OFF"}")
			
			// Update button icon/text if needed
			MeshUIBindings.toggleFlashlightButton.text = if (isFlashlightOn) "Flash: ON" else "Flash: OFF"
		} else {
			android.util.Log.w("EnhancedMeshFragment", "Cannot toggle flashlight - camera not active")
			Snackbar.make(
				requireView(),
				"Camera must be active to use flashlight",
				Snackbar.LENGTH_SHORT
			).show()
		}
	}
	
	/**
	 * Process scanned QR code data
	 */
	private fun processQRCode(qrData: String) {
		android.util.Log.d("EnhancedMeshFragment", "processQRCode() - data=$qrData")
		
		// Cooldown check (prevent duplicate scans)
		val currentTime = System.currentTimeMillis()
		if (currentTime < scanCooldownEndTime && qrData == lastScannedQRCode) {
			android.util.Log.d("EnhancedMeshFragment", "QR code in cooldown period, ignoring")
			return
		}
		
		// Update last scanned QR and set cooldown (2 seconds)
		lastScannedQRCode = qrData
		scanCooldownEndTime = currentTime + 2000
		
		// **CRITICAL**: Save mode flags BEFORE collapsePane resets them
		val wasJoinMode = isJoinMeshMode
		val wasMergeMode = isMergeMeshMode
		
		// Stop camera to prevent additional scans
		stopQRScanning()
		collapsePane()
		
		try {
			// Parse JSON QR code data
			val qrJson = JSONObject(qrData)
			val type = qrJson.optString("type", "")
			
			if (type != "mesh_join") {
				android.util.Log.w("EnhancedMeshFragment", "Invalid QR code type: $type")
				Snackbar.make(
					requireView(),
					"Invalid mesh QR code",
					Snackbar.LENGTH_SHORT
				).show()
				return
			}
			
			// Show progress
			MeshUIBindings.scanningStatusText.text = "Connecting to mesh..."
			
			// Determine which API to call based on current mesh state
			val meshState = meshrabiyaApi.getMeshStatus()
			
			if (wasJoinMode) {
				// Join Mesh mode - call joinMesh()
				android.util.Log.d("EnhancedMeshFragment", "Calling joinMesh() with QR data")
				meshrabiyaApi.joinMesh(qrData) { result ->
					viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
						if (result.isSuccess) {
							android.util.Log.d("EnhancedMeshFragment", "joinMesh() succeeded")
							Snackbar.make(
								requireView(),
								"Successfully joined mesh network",
								Snackbar.LENGTH_LONG
							).show()
							isJoinMeshMode = false
						} else {
							android.util.Log.e("EnhancedMeshFragment", "joinMesh() failed: ${result.exceptionOrNull()?.message}")
							Snackbar.make(
								requireView(),
								"Failed to join mesh: ${result.exceptionOrNull()?.message}",
								Snackbar.LENGTH_LONG
							).show()
						}
						MeshUIBindings.scanningStatusText.text = ""
					}
				}
			} else if (wasMergeMode) {
				// Merge Mesh mode - call mergeMesh()
				android.util.Log.d("EnhancedMeshFragment", "Calling mergeMesh() with QR data")
				meshrabiyaApi.mergeMesh(qrData) { result ->
					viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
						if (result.isSuccess) {
							android.util.Log.d("EnhancedMeshFragment", "mergeMesh() succeeded")
							Snackbar.make(
								requireView(),
								"Successfully merged with mesh network",
								Snackbar.LENGTH_LONG
							).show()
							isMergeMeshMode = false
						} else {
							android.util.Log.e("EnhancedMeshFragment", "mergeMesh() failed: ${result.exceptionOrNull()?.message}")
							Snackbar.make(
								requireView(),
								"Failed to merge mesh: ${result.exceptionOrNull()?.message}",
								Snackbar.LENGTH_LONG
							).show()
						}
						MeshUIBindings.scanningStatusText.text = ""
					}
				}
			}
			
		} catch (e: Exception) {
			android.util.Log.e("EnhancedMeshFragment", "Failed to process QR code", e)
			Snackbar.make(
				requireView(),
				"Failed to process QR code: ${e.message}",
				Snackbar.LENGTH_SHORT
			).show()
			MeshUIBindings.scanningStatusText.text = ""
		}
	}
	
	/**
	 * Handle camera permission result for QR scanning
	 */
	override fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<out String>,
		grantResults: IntArray
	) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		
		if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
			if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				android.util.Log.d("EnhancedMeshFragment", "Camera permission granted, starting QR scanning")
				startQRScanning()
			} else {
				android.util.Log.w("EnhancedMeshFragment", "Camera permission denied")
				Snackbar.make(
					requireView(),
					"Camera permission is required to scan QR codes",
					Snackbar.LENGTH_LONG
				).setAction("Settings") {
					// Could open app settings here
				}.show()
			}
		}
	}
	
	/**
	 * Setup listeners for deferred cards (4-9) after ViewStub inflation
	 */
	private fun setupDeferredCardListeners() {
		android.util.Log.d("EnhancedMeshFragment", "[DEFERRED] Setting up deferred card listeners...")
		
		try {
			// Service Layer Participation Toggle
			MeshUIBindings.serviceLayerParticipationSwitch.setOnCheckedChangeListener { _, isChecked ->
				android.util.Log.e("EnhancedMeshFragment", "[SERVICE_TOGGLE] Service Layer toggle changed to: $isChecked (programmatic: $isServiceToggleProgrammatic)")
				if (isServiceToggleProgrammatic) {
					android.util.Log.e("EnhancedMeshFragment", "[SERVICE_TOGGLE] Skipping programmatic toggle update")
					return@setOnCheckedChangeListener
				}
				meshrabiyaApi.setServiceParticipationEnabled("compute_node", isChecked) { result ->
					activity?.runOnUiThread {
						result.onSuccess {
							MeshUIBindings.serviceLayerStatusText.text = if (isChecked) "Service Layer active..." else "Service Layer inactive..."
							android.util.Log.e("EnhancedMeshFragment", "[SERVICE_TOGGLE] Service Participation successfully set to: $isChecked")
						}
						result.onFailure { error ->
							android.util.Log.e("EnhancedMeshFragment", "[SERVICE_TOGGLE] Error setting Service Participation: ${error.message}", error)
							isServiceToggleProgrammatic = true
							MeshUIBindings.serviceLayerParticipationSwitch.isChecked = !isChecked
							isServiceToggleProgrammatic = false
						}
					}
				}
			}

			// Gateway toggles
			MeshUIBindings.gatewayToggle.setOnCheckedChangeListener { _, isChecked ->
				android.util.Log.d("EnhancedMeshFragment", "Tor Gateway toggle changed to: $isChecked")
				meshrabiyaApi.setTorGatewayEnabled(isChecked) { result ->
					activity?.runOnUiThread {
						result.onSuccess {
							MeshUIBindings.torGatewayStatus.text = if (isChecked) "Enabled" else "Disabled"
							android.util.Log.d("EnhancedMeshFragment", "Tor Gateway status updated to: ${if (isChecked) "Enabled" else "Disabled"}")
						}
						result.onFailure { error ->
							android.util.Log.e("EnhancedMeshFragment", "Error setting Tor Gateway: ${error.message}", error)
						}
					}
				}
			}
			
			MeshUIBindings.internetGatewayToggle.setOnCheckedChangeListener { _, isChecked ->
				android.util.Log.d("EnhancedMeshFragment", "Internet Gateway toggle changed to: $isChecked")
				meshrabiyaApi.setInternetGatewayEnabled(isChecked) { result ->
					activity?.runOnUiThread {
						result.onSuccess {
							MeshUIBindings.internetGatewayStatus.text = if (isChecked) "Enabled" else "Disabled"
							android.util.Log.d("EnhancedMeshFragment", "Internet Gateway status updated to: ${if (isChecked) "Enabled" else "Disabled"}")
						}
						result.onFailure { error ->
							android.util.Log.e("EnhancedMeshFragment", "Error setting Internet Gateway: ${error.message}", error)
						}
					}
				}
			}

			// Storage participation toggle
			MeshUIBindings.storageParticipationToggle.setOnCheckedChangeListener { _, isChecked ->
				android.util.Log.e("EnhancedMeshFragment", "[STORAGE_TOGGLE] Storage Participation toggle changed to: $isChecked (programmatic: $isStorageToggleProgrammatic)")
				if (isStorageToggleProgrammatic) {
					android.util.Log.e("EnhancedMeshFragment", "[STORAGE_TOGGLE] Skipping programmatic toggle update")
					return@setOnCheckedChangeListener
				}
				meshrabiyaApi.setStorageParticipationEnabled(isChecked) { result ->
					activity?.runOnUiThread {
						result.onSuccess {
							MeshUIBindings.storageStatusText.text = if (isChecked) "Storage participation is enabled" else "Storage participation is disabled"
							android.util.Log.e("EnhancedMeshFragment", "[STORAGE_TOGGLE] Storage Participation successfully set to: $isChecked")
						}
						result.onFailure { error ->
							android.util.Log.e("EnhancedMeshFragment", "[STORAGE_TOGGLE] Error setting Storage Participation: ${error.message}", error)
							isStorageToggleProgrammatic = true
							MeshUIBindings.storageParticipationToggle.isChecked = !isChecked
							isStorageToggleProgrammatic = false
						}
					}
				}
			}

			// Storage allocation slider
			MeshUIBindings.storageAllocationSlider.addOnChangeListener { _, value, fromUser ->
				if (fromUser) {
					val quotaBytes = (value * 1024 * 1024 * 1024).toLong()
					requireActivity().getPreferences(android.content.Context.MODE_PRIVATE).edit()
						.putLong(PREF_STORAGE_QUOTA_BYTES, quotaBytes)
						.apply()
					android.util.Log.d("EnhancedMeshFragment", "Storage quota updated to: ${value}GB ($quotaBytes bytes)")
					MeshUIBindings.storageAllocationText.text = "${value.toInt()} GB"
					if (meshrabiyaApi.getStorageParticipationStatus()) {
						meshrabiyaApi.setStorageParticipationEnabled(true) { _ ->
							android.util.Log.i("EnhancedMeshFragment", "Storage participation refreshed with new quota: ${value}GB")
						}
					}
				}
			}
			
			// Drop folder buttons
			MeshUIBindings.selectFolderButton.setOnClickListener {
				android.util.Log.d("EnhancedMeshFragment", "Select folder button clicked")
				try {
					folderPickerLauncher.launch(null)
				} catch (e: Exception) {
					android.util.Log.e("EnhancedMeshFragment", "Error launching folder picker", e)
					Snackbar.make(requireView(), "Error opening folder picker: ${e.message}", Snackbar.LENGTH_LONG).show()
				}
			}
			
			MeshUIBindings.createFolderButton.setOnClickListener {
				android.util.Log.d("EnhancedMeshFragment", "Create folder button clicked")
				val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
				val input = android.widget.EditText(requireContext())
				input.hint = "Folder name"
				builder.setView(input)
				builder.setTitle("Create Storage Folder")
				builder.setPositiveButton("Create") { _, _ ->
					val folderName = input.text.toString()
					if (folderName.isNotBlank()) {
						createStorageFolder(folderName)
					}
				}
				builder.setNegativeButton("Cancel", null)
				builder.show()
			}
			
			android.util.Log.d("EnhancedMeshFragment", "[DEFERRED] Deferred card listeners setup complete")
		} catch (e: Exception) {
			android.util.Log.e("EnhancedMeshFragment", "[DEFERRED] Error setting up deferred card listeners", e)
		}
	}
	
	/**
	 * Update UI for deferred cards after ViewStub inflation
	 */
	private fun updateDeferredCardUI() {
		android.util.Log.d("EnhancedMeshFragment", "[DEFERRED] Updating deferred card UI...")
		
		try {
			// Update gateway toggles
			val torGatewayEnabled = meshrabiyaApi.getTorGatewayStatus()
			val internetGatewayEnabled = meshrabiyaApi.getInternetGatewayStatus()
			if (MeshUIBindings.gatewayToggle.isChecked != torGatewayEnabled) {
				MeshUIBindings.gatewayToggle.isChecked = torGatewayEnabled
			}
			if (MeshUIBindings.internetGatewayToggle.isChecked != internetGatewayEnabled) {
				MeshUIBindings.internetGatewayToggle.isChecked = internetGatewayEnabled
			}
			
			// Update storage toggle
			val storageEnabled = meshrabiyaApi.getStorageParticipationStatus()
			if (MeshUIBindings.storageParticipationToggle.isChecked != storageEnabled) {
				isStorageToggleProgrammatic = true
				MeshUIBindings.storageParticipationToggle.isChecked = storageEnabled
				isStorageToggleProgrammatic = false
			}
			
			// Update service layer toggle
			val serviceEnabled = meshrabiyaApi.getServiceParticipationStatus("compute_node")
			if (MeshUIBindings.serviceLayerParticipationSwitch.isChecked != serviceEnabled) {
				isServiceToggleProgrammatic = true
				MeshUIBindings.serviceLayerParticipationSwitch.isChecked = serviceEnabled
				isServiceToggleProgrammatic = false
			}
			
			// Load storage quota from preferences
			val prefs = requireActivity().getPreferences(android.content.Context.MODE_PRIVATE)
			val quotaBytes = prefs.getLong(PREF_STORAGE_QUOTA_BYTES, DEFAULT_STORAGE_QUOTA)
			val quotaGB = quotaBytes / (1024f * 1024f * 1024f)
			val clampedGB = quotaGB.coerceIn(1f, 50f)
			android.util.Log.d("EnhancedMeshFragment", "Loaded storage quota from preferences: ${quotaGB}GB (clamped to ${clampedGB}GB)")
			MeshUIBindings.storageAllocationSlider.value = clampedGB
			MeshUIBindings.storageAllocationText.text = "${clampedGB.toInt()} GB"
			
			android.util.Log.d("EnhancedMeshFragment", "[DEFERRED] Deferred card UI update complete")
		} catch (e: Exception) {
			android.util.Log.e("EnhancedMeshFragment", "[DEFERRED] Error updating deferred card UI", e)
		}
	}
	
	// 	onDropFolderUpdateHandler?.invoke(changes)
	// }
	private val onDropFolderUpdateHandler: (List<DropFolderItemDto>) -> Unit = { changes ->
		// Handle drop folder updates
		// For example, refresh the RecyclerView adapter with new data
		// val adapter = MeshUIBindings.folderContentsRecyclerView.adapter as DropFolderContentsAdapter
		// adapter.updateItems(changes)
	}
}
