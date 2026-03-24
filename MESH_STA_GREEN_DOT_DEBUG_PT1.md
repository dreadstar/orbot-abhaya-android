# MESH_STA_GREEN_DOT_DEBUG_PT1


## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
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
import com.ustadmobile.meshrabiya.api.model.NonMeshWifiNetworkDto
import com.ustadmobile.meshrabiya.api.model.DropFolderItemDto
import com.ustadmobile.meshrabiya.api.model.NetworkInfoDto
import com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto
import com.ustadmobile.meshrabiya.api.model.MeshRoleDto
import com.ustadmobile.meshrabiya.api.model.MeshExtenderHotspotStateDto
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import org.torproject.android.ui.mesh.model.BroadcastNotification
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.documentfile.provider.DocumentFile
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager

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
import kotlinx.coroutines.Job
import android.os.Build
import androidx.core.content.PermissionChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted

import org.torproject.android.ui.mesh.model.StatusNotification
import org.torproject.android.ui.mesh.model.StorageNotification
import org.torproject.android.ui.mesh.model.NotificationFeedEntry
import org.torproject.android.ui.mesh.model.NotificationType
import org.torproject.android.ui.mesh.model.toFeedEntry
import kotlinx.coroutines.flow.stateIn

interface EnhancedMeshFragmentHost {
    fun getFilePathFromUri(uri: Uri): String?
}

/**
 * EnhancedMeshFragment: Mesh UI fragment using MeshrabiyaApi for all mesh logic.
 * All mesh operations are routed through MeshrabiyaApi. No deprecated logic is used.
 */
class EnhancedMeshFragment : Fragment() {

	private lateinit var meshrabiyaApi: MeshrabiyaApi
	private lateinit var broadcastListener: (com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit
	private enum class LocationRequestOrigin { NONE, START_MESH, BROADCAST, JOIN_MESH }
	private var locationRequestOrigin = LocationRequestOrigin.NONE

	// Notification storage for broadcast messages (NEW)
    private val broadcastNotifications = MutableStateFlow<List<BroadcastNotification>>(emptyList())
	private val statusNotifications = MutableStateFlow<List<StatusNotification>>(emptyList())
	private val storageNotifications = MutableStateFlow<List<StorageNotification>>(emptyList())
    
	private lateinit var notificationFeed: StateFlow<List<NotificationFeedEntry>>
	private lateinit var notificationsAdapter: NotificationsAdapter

	// Track whether deferred views (cards 4-9) have been initialized via ViewStub
	private var deferredViewsInitialized = false
	
	// Folder picker for storage allocation
	private lateinit var folderPickerLauncher: ActivityResultLauncher<Uri?>
	private var selectedFolderUri: Uri? = null
	// keep coordinates until send is tapped
	private var pendingLatitude: Double? = null
	private var pendingLongitude: Double? = null
	private var locationRequestJob: Job? = null
	private var activeLocationListener: android.location.LocationListener? = null
	// File picker for broadcast attachments (must be registered before onCreate)
	private val broadcastFilePicker = registerForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri: Uri? ->
		uri?.let {
			handleBroadcastFileSelected(it)
		}
	}
	
	private var pendingFileCallback: ((Uri) -> Unit)? = null
	
	private fun handleBroadcastFileSelected(uri: Uri) {
		pendingFileCallback?.invoke(uri)
		pendingFileCallback = null
	}
	
	// Flags to prevent recursive toggle updates from programmatic changes
	private var isStorageToggleProgrammatic = false
	private var isServiceToggleProgrammatic = false
	private var meshOperationInProgress = false

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
	private lateinit var networkOverviewMetricsJob: Job
	
	companion object {
		
		
		private const val DEFAULT_STORAGE_QUOTA = 100_000_000L // 100MB default
		private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
	}

	private var pendingFolderName: String? = null

	private val requestWritePermissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestPermission()
	) { isGranted ->
		if (isGranted && pendingFolderName != null) {
			createStorageFolder(pendingFolderName!!)
			pendingFolderName = null
		} else {
			Snackbar.make(requireView(), "Write permission is required to create folders", Snackbar.LENGTH_LONG).show()
			pendingFolderName = null
		}
	}

	/**
	 * Convert content:// URI to file system path
	 * Required for configuring drop folder with meshrabiya API
	 */
	fun getFilePathFromUri(uri: Uri): String? {
		return try {
			// For DocumentTree URIs (from folder picker), use the tree document ID
			val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
			
			// Try to get real path from document provider
			val contentResolver = requireContext().contentResolver
			val cursor = contentResolver.query(
				android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, docId),
				arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
				null, null, null
			)
			
			cursor?.use {
				if (it.moveToFirst()) {
					// For now, use the app's external files directory + folder name
					// This is a workaround since content:// URIs don't map directly to filesystem paths
					val folderName = it.getString(0)
					val appFolder = requireContext().getExternalFilesDir(null)
					val broadcastFolder = java.io.File(appFolder, "broadcasts")
					if (!broadcastFolder.exists()) {
						ensureWritePermissionAndCreateFolder("broadcasts")
					}
					android.util.Log.d("EnhancedMeshFragment", "Using broadcast folder: ${broadcastFolder.absolutePath}")
					return broadcastFolder.absolutePath
				}
			}
			
			// Fallback: use app's external files directory
			val fallbackFolder = java.io.File(requireContext().getExternalFilesDir(null), "broadcasts")
			if (!fallbackFolder.exists()) {
				ensureWritePermissionAndCreateFolder("broadcasts")
			}
			android.util.Log.d("EnhancedMeshFragment", "Using fallback broadcast folder: ${fallbackFolder.absolutePath}")
			fallbackFolder.absolutePath
			
		} catch (e: Exception) {
			android.util.Log.e("EnhancedMeshFragment", "Error converting URI to path: ${e.message}")
			// Last resort fallback
			val fallbackFolder = java.io.File(requireContext().getExternalFilesDir(null), "broadcasts")
			if (!fallbackFolder.exists()) {
				ensureWritePermissionAndCreateFolder("broadcasts")
			}
			fallbackFolder.absolutePath
		}
	}

	private fun hasWritePermission(): Boolean {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			// App-specific storage: permission not required
			true
		} else {
			ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
		}
	}

	private fun ensureWritePermissionAndCreateFolder(folderName: String) {
		if (!hasWritePermission()) {
			pendingFolderName = folderName
			requestWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
			return
		}
		createStorageFolder(folderName)
	}

	
	
	// Permission launcher for runtime location permission requests
	private val requestLocationPermissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions()
	) { permissions ->
		val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
		val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

		if (fineLocationGranted && coarseLocationGranted) {
			when (locationRequestOrigin) {
				LocationRequestOrigin.START_MESH -> {
					android.util.Log.d("EnhancedMeshFragment",
						"Permissions granted (origin=START_MESH) – retrying startMesh()")
					startMeshWithPermissionCheck()
				}
				LocationRequestOrigin.BROADCAST -> {
					android.util.Log.d("EnhancedMeshFragment",
						"Permissions granted (origin=BROADCAST) – acquiring location for pending broadcast")
					// duplicate the acquisition code from the checkbox listener:
					try {
						val lm = requireContext().getSystemService(Context.LOCATION_SERVICE)
								as android.location.LocationManager
						val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
							?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
						if (loc != null) {
							// store in the vars defined in showBroadcastDialog()
							pendingLatitude = loc.latitude
							pendingLongitude = loc.longitude
							Log.d("EnhancedMeshFragment",
								"location acquired after permission: $pendingLatitude,$pendingLongitude")
						}
					} catch (e: Exception) {
						Log.e("EnhancedMeshFragment", "Failed to get location after permission", e)
					}
				}
				LocationRequestOrigin.JOIN_MESH -> {
                    android.util.Log.d("EnhancedMeshFragment",
                        "Permissions granted (origin=JOIN_MESH) – starting QR scan")
                    expandPane(showCamera = true)
                    startQRScanning()
                }
                LocationRequestOrigin.NONE -> {
                    // should never happen
                }
            }
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
		locationRequestOrigin = LocationRequestOrigin.NONE
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
				// Save to library-managed preferences via API
				meshrabiyaApi.setDropFolderUri(it.toString())
				
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

		// Initialize notifications adapter and bind to RecyclerView
        notificationsAdapter = NotificationsAdapter(emptyList()) { entry -> removeNotification(entry) }
        android.util.Log.d("EnhancedMeshFragment", "[DROPDOWN] adapter created in fragment, size=${notificationsAdapter.itemCount}")
		val notificationsRecyclerView = view.findViewById<RecyclerView>(R.id.notificationsDropdownRecyclerView)
		notificationsRecyclerView.adapter = notificationsAdapter
		notificationsRecyclerView.layoutManager = LinearLayoutManager(requireContext())

		return view
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] ===== onViewCreated() CALLED =====")
		// Get MeshrabiyaApi singleton (already initialized in OrbotApp.onCreate)
		meshrabiyaApi = MeshrabiyaApiImpl.getInstance()
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] MeshrabiyaApi obtained")
		notificationFeed = combine(
			broadcastNotifications,
			statusNotifications,
			storageNotifications
		) { broadcasts, errors, storage ->
			(broadcasts.map { it.toFeedEntry() } +
			errors.map { it.toFeedEntry() } +
			storage.map { it.toFeedEntry() })
				.sortedByDescending { it.createdAt }
		}.stateIn(viewLifecycleOwner.lifecycleScope, SharingStarted.Eagerly, emptyList())
		// NOTE: initMesh() is called ONLY in OrbotApp.onCreate() at app startup.
		// Fragment just uses the already-initialized mesh infrastructure.
		
		// Setup all UI event listeners (button clicks, toggles, etc.)
        // listeners will be installed later once deferred views are inflated
        // (see coroutine below).
		
		// Setup observer for mesh roles StateFlow - auto-updates UI when roles change
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Setting up role observer...")
		setupRoleObserver()
		// android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Role observer setup complete")

		// Setup observer for mesh status StateFlow - auto-updates UI when status changes
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Setting up mesh status observer...")
		viewLifecycleOwner.lifecycleScope.launch {
            meshrabiyaApi.meshStatusFlow.collect { status ->
                activity?.runOnUiThread {
                    MeshUIBindings.meshStatusText.text = status.toString()
                    updateButtonStates(status)
                    if (status == MeshStateDto.DISCONNECTED) {
						if (deferredViewsInitialized &&
							MeshUIBindings.meshExpandableContent.visibility == View.VISIBLE) {
							if (isCameraActive) stopQRScanning()
							MeshUIBindings.meshExpandableContent.visibility = View.GONE
							MeshUIBindings.expandCollapseIndicator.rotation = 0f
						}
					}
					// Refresh chip row immediately when mesh becomes connected (avoids 2s polling delay)
					if (status == MeshStateDto.CONNECTED && deferredViewsInitialized) {
						updateUI()
					}
					
                }

                if (status == MeshStateDto.CONNECTED) {
                    Log.d("EnhancedMeshFragment", "[MESH_STATUS] Connected - role updates now automatic")
                    // Role updates happen automatically via EmergentRoleManager.startWifiStateMonitoring()
                }
            }
        }
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Mesh status observer setup complete")
		
		
		// Setup observer for network info StateFlow - auto-updates peer count and network stats
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Setting up network info observer...")
		setupNetworkInfoObserver()
		setupMeshInternetGreenDotObserver()
        android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Network info observer setup complete")
        observeGatewayAvailability()
		// setupNetworkInfoObserver()
        setupNonMeshWifiObserver()
        setupMeshExtenderObserver()
		setupWifiStateObserver()
		setupMeshApObserver()

        // Initial UI update to show current mesh state
        android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Calling updateUI()...")
		updateUI()
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] ===== onViewCreated() COMPLETE =====")

		// Observe network overview metrics
		networkOverviewMetricsJob = viewLifecycleOwner.lifecycleScope.launch {
			(meshrabiyaApi as? com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl)?.networkOverviewMetricsFlow?.collect { metrics ->
				updateNetworkOverviewUI(metrics)
			}
		}
		
		// Broadcast listener - receives broadcasts from the mesh network
        broadcastListener = { broadcast: BroadcastReceivedDto ->
			// diagnostics: record each invocation and state
            android.util.Log.d("EnhancedMeshFragment",
                "[BROADCAST] callback entry id=${broadcast.broadcastId.take(8)} " +
                "sender=${broadcast.senderNodeId} hasError=${broadcast.hasError} " +
                "filePath='${broadcast.filePath}' viewState=${viewLifecycleOwner.lifecycle.currentState}")
			val tag = "EnhancedMeshFragment[${broadcast.broadcastId.take(8)}]"
			lifecycleScope.launch(Dispatchers.Main) {
				val myNodeId = meshrabiyaApi.getNodeId().toString()
				val isDuplicate = broadcastNotifications.value.any { it.id == broadcast.broadcastId }
				val isSelf = broadcast.senderNodeId.toString() == myNodeId
				val hasError = broadcast.hasError
				val errorMessage = broadcast.errorMessage ?: "Failed to receive file"

				when {
					isDuplicate -> {
						// Add status notification for duplicate
						statusNotifications.value = statusNotifications.value + StatusNotification(
							id = broadcast.broadcastId,
							title = "Duplicate Broadcast",
							createdAt = System.currentTimeMillis(),
							statusMessage = "Broadcast already received"
						)
						Toast.makeText(requireContext(), "Duplicate broadcast received", Toast.LENGTH_SHORT).show()
						android.util.Log.w(tag, "[UI_CALLBACK] ⚠️ DUPLICATE broadcast detected, skipping (already in list)")
						return@launch
					}
					isSelf -> {
						// Add status notification for self-broadcast
						statusNotifications.value = statusNotifications.value + StatusNotification(
							id = broadcast.broadcastId,
							title = "Self Broadcast",
							createdAt = System.currentTimeMillis(),
							statusMessage = "Sender is self"
						)
						Toast.makeText(requireContext(), "Ignored self-broadcast", Toast.LENGTH_SHORT).show()
						android.util.Log.w(tag, "[UI_CALLBACK] ⚠️ Self-broadcast detected, skipping")
						return@launch
					}
					hasError -> {
						// Add status notification for error
						statusNotifications.value = statusNotifications.value + StatusNotification(
							id = broadcast.broadcastId,
							title = "File Error",
							createdAt = System.currentTimeMillis(),
							statusMessage = errorMessage
						)
						Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
						android.util.Log.e(tag, "[UI_CALLBACK] ❌ Broadcast error: $errorMessage")
						// Show error snackbar with action to go to drop folder settings
						view?.let { fragmentView ->
							Snackbar.make(
								fragmentView,
								"File broadcast failed: $errorMessage",
								Snackbar.LENGTH_LONG
							).setAction("Set Folder") {
								folderPickerLauncher.launch(null)
							}.show()
						}
						return@launch
					}
					else -> {
						// Add broadcast notification
						val newItem = BroadcastNotification(
                            id = broadcast.broadcastId,
                            title = "Broadcast Rcvd: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}",
                            createdAt = System.currentTimeMillis(),
                            message = broadcast.messageText,
                            filePath = broadcast.filePath,
                            senderNodeId = broadcast.senderNodeId.toString(),
                            latitude = broadcast.latitude,
                            longitude = broadcast.longitude
                        )
						android.util.Log.d(tag, "[BROADCAST] about to add notification, currentSize=${broadcastNotifications.value.size}")
						broadcastNotifications.value = broadcastNotifications.value + newItem
						android.util.Log.d(tag, "[UI_CALLBACK] ✅ Added to broadcastNotifications (size=${broadcastNotifications.value.size})")

						// UI feedback (Toast, Snackbar) for success
						val message = if (broadcast.fileName.isNotBlank() && broadcast.filePath.isNotBlank()) {
							"Message from node ${broadcast.senderNodeId}: ${broadcast.messageText}\n" +
							"File: ${broadcast.fileName} saved to ${broadcast.filePath}"
						} else {
							"Message from node ${broadcast.senderNodeId}: ${broadcast.messageText}"
						}
						try {
							Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
						} catch (e: Exception) {
							android.util.Log.e(tag, "[UI_CALLBACK] ❌ Toast failed", e)
						}
						view?.let { fragmentView ->
							Snackbar.make(
								fragmentView,
								message,
								Snackbar.LENGTH_LONG
							).setAction("View") {
								Toast.makeText(requireContext(), "Viewing broadcast details", Toast.LENGTH_SHORT).show()
							}.show()
						}
					}
				}
			}
		}


		// add diagnostics prior to registration
        android.util.Log.d("EnhancedMeshFragment", "[BROADCAST] registerBroadcastListener (viewState=${viewLifecycleOwner.lifecycle.currentState})")
		meshrabiyaApi.registerBroadcastListener(broadcastListener)
		
		// Register broadcast success handler
		meshrabiyaApi.setOnBroadcastSent { result ->
			activity?.runOnUiThread {
				val coords = if (result.latitude != null && result.longitude != null) {
					" [coords=${result.latitude},${result.longitude}]"
				} else ""
				android.util.Log.d("EnhancedMeshFragment", "Broadcast sent: ${result.broadcastId}, ${result.successNodeIds.size} nodes reached$coords")
			}
		}
		
		// Register broadcast failure handler
		meshrabiyaApi.setOnBroadcastFailed { broadcastId, error ->
			activity?.runOnUiThread {
				android.util.Log.e("EnhancedMeshFragment", "Broadcast failed: $broadcastId", error)
				view?.let { v ->
					Snackbar.make(v, "Broadcast failed: ${error.message}", Snackbar.LENGTH_LONG).show()
				}
			}
		}
		
		// Defer inflation of cards 4-9 to prevent UI thread blocking (async after 300ms)
		android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Scheduling deferred card inflation...")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val stub = view.findViewById<android.view.ViewStub>(R.id.deferredCardsStub)
                if (stub != null) {
                    android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Inflating deferred cards...")
                    stub.setOnInflateListener { _, _ ->
                        android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Deferred cards inflated successfully")

                        // Bind newly inflated deferred views (cards 4-9)
                        android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Binding deferred views...")
                        MeshUIBindings.bindDeferredViews(view)

                        // Mark deferred views as initialized
                        deferredViewsInitialized = true
                        android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Deferred views bound, flag set to true")

                        // Now that all views exist, wire up event listeners.
                        android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Setting up listeners...")
                        setupListeners()
                        android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Listeners setup complete")

                        // Update network info UI with cached value if available
                        (meshrabiyaApi.getNetworkInfo())?.let { networkInfo ->
                            android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Inflated – getNetworkInfo() returned peers=${networkInfo.connectedPeers}")
                            MeshUIBindings.meshIpAddressText.text = networkInfo.ipAddress
                            MeshUIBindings.networkStatsText.text =
                                "Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
                        }

                        // Setup listeners for deferred cards
                        android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Setting up deferred card listeners...")
                        setupDeferredCardListeners()
                       // Update UI for deferred cards
                        android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Updating deferred card UI...")
                        updateDeferredCardUI()
                        // also refresh overall UI; this applies any cached Wi‑Fi state
                        updateUI()
                        android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Deferred card initialization complete")
                    }
                    stub.inflate()
                } else {
                    android.util.Log.w("EnhancedMeshFragment", "[LIFECYCLE] ViewStub not found!")
                }
            } catch (e: Exception) {
                android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Failed to inflate deferred cards", e)
            }
        }

		// Observe notificationFeed and update both badge and dropdown adapter
		viewLifecycleOwner.lifecycleScope.launch {
			notificationFeed.collect { notifications ->
				android.util.Log.d("EnhancedMeshFragment", "[DROPDOWN] collector received ${notifications.size} items")
				val badgeCount = notifications.size
				(activity as? org.torproject.android.OrbotActivity)?.let { act ->
					act.updateNotificationBadge(badgeCount)
					act.onNotificationFeedChanged(notifications)
				}
				// notificationsAdapter.submitList(notifications)
			}
		}
	}

	override fun onResume() {
        super.onResume()
        // Force-sync meshStatusFlow with actual WiFi state (catches stale CONNECTED after sleep)
        meshrabiyaApi.refreshMeshStatus()
        updateUI()
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

		// Cancel metrics observer job
		if (this::networkOverviewMetricsJob.isInitialized) {
			networkOverviewMetricsJob.cancel()
		}
		
		// Unregister broadcast listener
		if (this::broadcastListener.isInitialized) {
			android.util.Log.d("EnhancedMeshFragment", "[BROADCAST] unregisterBroadcastListener (viewState=${viewLifecycleOwner.lifecycle.currentState})")
			meshrabiyaApi.unregisterBroadcastListener(broadcastListener)
		}
		
	}

	private fun updateNetworkOverviewUI(metrics: com.ustadmobile.meshrabiya.api.model.NetworkOverviewMetricsDto) {
		MeshUIBindings.textUploadBitrate.text = "${metrics.uploadBps} Bps"
		MeshUIBindings.textDownloadBitrate.text = "${metrics.downloadBps} Bps"
		MeshUIBindings.textActiveNodeCount.text = "${metrics.activeNodeCount} nodes"
	}

	/**
	 * Setup observer for mesh roles StateFlow to automatically update UI when roles change
	 */
	private fun setupRoleObserver() {
		android.util.Log.d("EnhancedMeshFragment", "[ROLE_OBSERVER] Setting up role observer")
		var lastRoleUpdate = 0L
		var roleUpdateCount = 0
		var previousRolesDto: Set<MeshRoleDto> = emptySet()
		
		viewLifecycleOwner.lifecycleScope.launch {
            (meshrabiyaApi as? MeshrabiyaApiImpl)?.currentMeshRolesFlow?.collect { rolesDto ->
                val now = System.currentTimeMillis()
                val timeSinceLastUpdate = if (lastRoleUpdate > 0) now - lastRoleUpdate else 0
                roleUpdateCount++
                
                // convert DTOs back to strings for logging/logic
                val roles = rolesDto.map { it.name }.toSet()
                
                // log when router bit changes
                if (rolesDto != previousRolesDto) {
                    if (MeshRoleDto.MESH_ROUTER in rolesDto && MeshRoleDto.MESH_ROUTER !in previousRolesDto) {
                        android.util.Log.d("EnhancedMeshFragment","[ROLE_OBSERVER] 🎯 MESH_ROUTER role appeared")
                    }
                    if (MeshRoleDto.MESH_ROUTER !in rolesDto && MeshRoleDto.MESH_ROUTER in previousRolesDto) {
                        android.util.Log.d("EnhancedMeshFragment","[ROLE_OBSERVER] ⚠️ MESH_ROUTER role removed")
                    }
                    previousRolesDto = rolesDto
                }
                
                android.util.Log.e("EnhancedMeshFragment", "[ROLE_OBSERVER] ⚡ ROLE UPDATE #$roleUpdateCount: roles=$roles, timeSinceLastUpdate=${timeSinceLastUpdate}ms")
                lastRoleUpdate = now
                
                activity?.runOnUiThread {
                    val uiUpdateStart = System.currentTimeMillis()
                    
                    // Update roles text - show "Roles: --" when mesh not started or no roles determined yet
                    android.util.Log.d("EnhancedMeshFragment", "[ROLE_OBSERVER] Updating meshRolesText")
                    MeshUIBindings.meshRolesText.text =
                        if (roles.isNotEmpty()) "Roles: ${roles.joinToString(", ")}"
                        else "Roles: --"
					
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
					
					val uiUpdateDuration = System.currentTimeMillis() - uiUpdateStart
                    android.util.Log.d("EnhancedMeshFragment", "[ROLE_OBSERVER] ✓ UI update completed in ${uiUpdateDuration}ms")
                    
                    // compute meshStarted for logging (matches updateButtonStates logic)
                    val meshState = meshrabiyaApi.getMeshStatus()
                    val meshStarted = meshState == MeshStateDto.CONNECTED || meshState == MeshStateDto.CONNECTING
                    android.util.Log.e("EnhancedMeshFragment", "[ROLE_OBSERVER] UI updated - meshStarted: $meshStarted, roles: ${roles.joinToString(", ")}")

                    val isMeshRouter = MeshRoleDto.MESH_ROUTER in rolesDto
                    val isSta =
                        meshrabiyaApi.getNonMeshWifiStateFlow().value.status.name == "CONNECTED"

                    
                    val showButtons = isMeshRouter && isSta
                    val isWifiConcurrentCapable = meshrabiyaApi.isApStaConcurrentCapable() || meshrabiyaApi.isStaStaConcurrentCapable()
                    MeshUIBindings.wifiApConnectionButton.visibility =
                        if (isWifiConcurrentCapable) View.VISIBLE else View.GONE
                    MeshUIBindings.meshExtenderApButton.visibility =
                        if (showButtons) View.VISIBLE else View.GONE
                }
			}
        }
    }

    /**
     * Setup observer for network info StateFlow
	 */
	private fun observeGatewayAvailability() {
        viewLifecycleOwner.lifecycleScope.launch {
            var previouslyAvailable = false
            meshrabiyaApi.getMeshInternetGatewayAvailableFlow().collect { available ->
                if (available && !previouslyAvailable) {
                    activity?.runOnUiThread {
                        android.widget.Toast.makeText(
                            requireContext(),
                            "Internet available via mesh gateway",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                previouslyAvailable = available
            }
        }
    }

    private fun setupNetworkInfoObserver() {
        android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] Setting up network info observer")
        viewLifecycleOwner.lifecycleScope.launch {
            (meshrabiyaApi as? MeshrabiyaApiImpl)?.networkInfoFlow?.collect { networkInfo ->
                // flow emits null until the library can supply a real value
                if (networkInfo == null) {
                    android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] value==null – waiting for first emission")
                    return@collect
                }

                // drop logs if you prefer
                android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] Network info received: "
                    + "peers=${networkInfo.connectedPeers}, ssid=${networkInfo.nonMeshSsid}")

                if (deferredViewsInitialized) {
                    activity?.runOnUiThread {
                        MeshUIBindings.meshIpAddressText.text = networkInfo.ipAddress
                        MeshUIBindings.networkStatsText.text =
                            "Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
                        // meshChipAp: visible when this device is running a mesh hotspot.
                        // meshChipSta: visible when this device joined the mesh as a station.
                        // These are mutually exclusive in single-radio mode but both may be
                        // true on a device with AP+STA concurrency (e.g. Phone 1).
                        // apActive drives the AP chip; STA is inferred as connected-but-not-AP.
                        val meshStatus = meshrabiyaApi.meshStatusFlow.value
                        val meshConnected = meshStatus == MeshStateDto.CONNECTED ||
                                            meshStatus == MeshStateDto.CONNECTING
                        val apActive = (meshrabiyaApi as? MeshrabiyaApiImpl)
                            ?.meshApActiveFlow?.value ?: false
                        val staActive = meshConnected && !apActive
                        MeshUIBindings.meshChipAp.visibility =
                            if (apActive) View.VISIBLE else View.GONE
                        MeshUIBindings.meshChipSta.visibility =
                            if (staActive) View.VISIBLE else View.GONE
                        // NOTE: meshInternetGreenDot is intentionally NOT set here.
                        // It is driven exclusively by setupMeshInternetGreenDotObserver()
                        // which collects getMeshInternetGatewayAvailableFlow() as a live
                        // flow. Combining a snapshot .value here caused the dot to miss
                        // updates that arrived between networkInfo emissions.
                        if (!networkInfo.nonMeshSsid.isNullOrEmpty()) {
                            android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] non‑mesh SSID present: ${networkInfo.nonMeshSsid}")
                            MeshUIBindings.internetWifiRow.visibility = View.VISIBLE
                            MeshUIBindings.internetWifiIpText.text = networkInfo.nonMeshIpAddress ?: "--"
                            MeshUIBindings.internetWifiChipSta.visibility = View.VISIBLE
                            MeshUIBindings.internetWifiGreenDot.visibility =
                                if (networkInfo.nonMeshHasInternet == true) View.VISIBLE else View.GONE
                        } else {
                            android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] non‑mesh SSID empty – hiding row")
                            MeshUIBindings.internetWifiRow.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

	private fun setupMeshInternetGreenDotObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            meshrabiyaApi.getMeshInternetGatewayAvailableFlow().collect { gatewayAvailable ->
                if (!deferredViewsInitialized) return@collect
                val nonMeshInternet = (meshrabiyaApi as? MeshrabiyaApiImpl)
                    ?.networkInfoFlow?.value?.nonMeshHasInternet == true
                val hasAnyInternet = nonMeshInternet || gatewayAvailable
                activity?.runOnUiThread {
                    MeshUIBindings.meshInternetGreenDot.visibility =
                        if (hasAnyInternet) View.VISIBLE else View.GONE
                }
            }
        }
    }

	// private fun setupMeshInternetGreenDotObserver() {
    //     viewLifecycleOwner.lifecycleScope.launch {
    //         meshrabiyaApi.getMeshInternetGatewayAvailableFlow().collect { gatewayAvailable ->
    //             if (!deferredViewsInitialized) return@collect
    //             activity?.runOnUiThread {
    //                 MeshUIBindings.meshInternetGreenDot.visibility =
    //                     if (gatewayAvailable) View.VISIBLE else View.GONE
    //             }
    //         }
    //     }
    // }

    private fun setupNonMeshWifiObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            meshrabiyaApi.getNonMeshWifiStateFlow().collect { nonMeshState ->
                if (!deferredViewsInitialized) return@collect
                activity?.runOnUiThread {
                    val connected = nonMeshState.status.name == "CONNECTED"
                    if (connected) {
                        MeshUIBindings.wifiApConnectionButton.setText(R.string.wifi_internet)
                        
                        MeshUIBindings.wifiApConnectionButton.setIconResource(R.drawable.ic_stop)
                        MeshUIBindings.wifiApConnectionButton.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
                        MeshUIBindings.wifiApConnectionButton.setTextColor(android.graphics.Color.WHITE)
                    } else {
                        MeshUIBindings.wifiApConnectionButton.setText(R.string.wifi_internet)
                        MeshUIBindings.wifiApConnectionButton.setIconResource(R.drawable.ic_wifi)
                    }
                }
            }
        }
    }

    private fun setupMeshExtenderObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            meshrabiyaApi.meshExtenderHotspotStateFlow.collect { state ->
                if (!deferredViewsInitialized) return@collect
                val hotspotInfo = meshrabiyaApi.getHotspotInfo()
                activity?.runOnUiThread {
                    val isActive = state == MeshExtenderHotspotStateDto.ACTIVE
                    MeshUIBindings.meshExtenderApRow.visibility =
                        if (isActive) View.VISIBLE else View.GONE
                    if (isActive) {
                        val apIp = hotspotInfo?.nodeAddress?.addressToDotNotation() ?: "--"
                        MeshUIBindings.meshExtenderApIpText.text = apIp
                        // Show chevron + QR pane so extender can share its QR code for joining
                        MeshUIBindings.expandCollapseIndicator.visibility = View.VISIBLE
                        if (MeshUIBindings.meshExpandableContent.visibility != View.VISIBLE) {
                            expandPane(showCamera = false)
                            showCurrentNetworkQR()
                        }
                    }
                }
            }
        }
    }

	private fun setupWifiStateObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            (meshrabiyaApi as? MeshrabiyaApiImpl)?.wifiStateFlow?.collect { wifiState ->
                if (!deferredViewsInitialized) return@collect
                activity?.runOnUiThread {
                    val isActingAsSta = wifiState?.wifiStationState?.status == "AVAILABLE"
                    val isActingAsAp = wifiState?.wifiDirectState?.hotspotStatus == "STARTED"
                        || wifiState?.localOnlyHotspotState?.status == "STARTED"
                    MeshUIBindings.meshChipMesh.visibility = View.VISIBLE
                    MeshUIBindings.meshChipSta.visibility = if (isActingAsSta) View.VISIBLE else View.GONE
                    MeshUIBindings.meshChipAp.visibility = if (isActingAsAp) View.VISIBLE else View.GONE
                }
            }
        }
    }

	/**
     * Observes meshApActiveFlow — the only reactive trigger for expanding/collapsing
     * the QR pane based on whether THIS device's mesh AP hotspot is hardware-active.
     * A joining station device will never trigger this.
     */
    private fun setupMeshApObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            (meshrabiyaApi as? com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl)
                ?.meshApActiveFlow
                ?.collect { isActive ->
                    if (!deferredViewsInitialized) return@collect
                    activity?.runOnUiThread {
                        if (isActive && !isJoinMeshMode && !isMergeMeshMode
                            && MeshUIBindings.meshExpandableContent.visibility != View.VISIBLE) {
                            android.util.Log.d("EnhancedMeshFragment", "[AP_OBSERVER] AP hotspot started — expanding QR pane")
                            expandPane(showCamera = false)
                            showCurrentNetworkQR()
                        } else if (!isActive
                            && MeshUIBindings.meshExpandableContent.visibility == View.VISIBLE
                            && !isJoinMeshMode && !isMergeMeshMode) {
                            android.util.Log.d("EnhancedMeshFragment", "[AP_OBSERVER] AP hotspot stopped — collapsing pane")
                            collapsePane()
                        }
                    }
                }
        }
    }

    private fun setupListeners() {
		MeshUIBindings.meshToggleButton.setOnTouchListener { v, event ->
			android.util.Log.d("EnhancedMeshFragment", "[TOUCH] meshToggleButton touched: action=${event.action}, enabled=${v.isEnabled}, clickable=${v.isClickable}")
			false
		}

		MeshUIBindings.meshToggleButton.setOnClickListener {
			android.util.Log.d("EnhancedMeshFragment", "[CLICK] Mesh toggle button clicked")
			android.util.Log.d("EnhancedMeshFragment", "[CLICK] Button state: enabled=${MeshUIBindings.meshToggleButton.isEnabled}, clickable=${MeshUIBindings.meshToggleButton.isClickable}, meshOpInProgress=$meshOperationInProgress")

			if (meshOperationInProgress) {
				android.util.Log.w("EnhancedMeshFragment", "Mesh operation already in progress, ignoring click")
				return@setOnClickListener
			}

			val currentStatus = meshrabiyaApi.meshStatusFlow.value
			val meshActive = currentStatus == MeshStateDto.CONNECTED || currentStatus == MeshStateDto.CONNECTING
			android.util.Log.d("EnhancedMeshFragment", "Current mesh status: $currentStatus, meshActive=$meshActive")

			meshOperationInProgress = true
			MeshUIBindings.meshToggleButton.isEnabled = false
			android.util.Log.d("EnhancedMeshFragment", "Button disabled, operation marked in progress")

			if (meshActive) {
				android.util.Log.d("EnhancedMeshFragment", "Calling stopMesh()")
				meshrabiyaApi.stopMesh { result ->
					activity?.runOnUiThread {
						android.util.Log.d("EnhancedMeshFragment", "stopMesh callback: success=${result.isSuccess}, error=${result.exceptionOrNull()}")
						meshOperationInProgress = false
						MeshUIBindings.meshToggleButton.isEnabled = true
					}
				}
			} else {
				android.util.Log.e("EnhancedMeshFragment", "========== START MESH BUTTON CLICKED ==========")
				android.util.Log.e("EnhancedMeshFragment", "This log MUST appear when Start Mesh is pressed")
				if (checkLocationPermissions()) {
					android.util.Log.e("EnhancedMeshFragment", "Permissions granted, calling meshrabiyaApi.startMesh()")
					meshrabiyaApi.startMesh { result ->
						activity?.runOnUiThread {
							android.util.Log.d("EnhancedMeshFragment", "startMesh callback: success=${result.isSuccess}, error=${result.exceptionOrNull()}")
							if (result.isFailure) {
								android.util.Log.e("EnhancedMeshFragment", "startMesh failed", result.exceptionOrNull())
								view?.let { v ->
									val errorMessage = result.exceptionOrNull()?.message ?: "Unknown error"
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
										Snackbar.make(v, "Failed to start mesh: $errorMessage", Snackbar.LENGTH_LONG).show()
									}
								}
							}
							meshOperationInProgress = false
							MeshUIBindings.meshToggleButton.isEnabled = true
						}
					}
				} else {
					android.util.Log.d("EnhancedMeshFragment", "Permissions not granted, requesting now")
					meshOperationInProgress = false
					MeshUIBindings.meshToggleButton.isEnabled = true
					requestLocationPermissions()
				}
			}
		}

		MeshUIBindings.sendBroadcastButton.setOnClickListener {
			showBroadcastDialog()
		}

		MeshUIBindings.joinMeshButton.setOnClickListener {
			android.util.Log.d("EnhancedMeshFragment", "Join Mesh button clicked (isJoinMeshMode=$isJoinMeshMode)")

			if (isJoinMeshMode) {
				collapsePane()
			} else {
				if (!meshrabiyaApi.isWifiEnabled()) {
					androidx.appcompat.app.AlertDialog.Builder(requireContext())
						.setTitle("⚠️ WiFi Required")
						.setMessage("Wifi must be enabled to Join Mesh")
						.setPositiveButton("Open WiFi Settings") { _, _ ->
							try {
								startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
							} catch (e: Exception) {
								view?.let { v -> com.google.android.material.snackbar.Snackbar.make(v, "Could not open WiFi settings", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show() }
							}
						}
						.setNegativeButton("Cancel", null)
						.show()
					return@setOnClickListener
				}

				isJoinMeshMode = true
				isMergeMeshMode = false
				MeshUIBindings.joinMeshButton.text = "Cancel Join"

				if (checkLocationPermissions()) {
					expandPane(showCamera = true)
					startQRScanning()
				} else {
					locationRequestOrigin = LocationRequestOrigin.JOIN_MESH
					requestLocationPermissionLauncher.launch(
						arrayOf(
							Manifest.permission.ACCESS_FINE_LOCATION,
							Manifest.permission.ACCESS_COARSE_LOCATION
						)
					)
				}
			}
		}

		MeshUIBindings.mergeMeshButton.setOnClickListener {
			android.util.Log.d("EnhancedMeshFragment", "Merge Mesh button clicked")

			val meshStatus = meshrabiyaApi.meshStatusFlow.value

			if (meshStatus != MeshStateDto.CONNECTED) {
				android.util.Log.w("EnhancedMeshFragment", "Merge Mesh clicked but not CONNECTED (status=$meshStatus)")
				view?.let { v ->
					Snackbar.make(v, "Cannot merge - not connected to a mesh", Snackbar.LENGTH_SHORT).show()
				}
				return@setOnClickListener
			}

			isJoinMeshMode = false
			isMergeMeshMode = true
			expandPane(showCamera = true)
			startQRScanning()
		}

		MeshUIBindings.wifiApConnectionButton.setOnClickListener {
			val wifiStatus = meshrabiyaApi.getNonMeshWifiStateFlow().value.status
			if (wifiStatus.name == "CONNECTED") {
				lifecycleScope.launch {
					meshrabiyaApi.disconnectFromNonMeshWifi()
				}
			} else {
				showInternetWifiConnectionDialog()
			}
		}

		MeshUIBindings.meshExtenderApButton.setOnClickListener {
			val currentState = meshrabiyaApi.meshExtenderHotspotStateFlow.value
			if (currentState == MeshExtenderHotspotStateDto.ACTIVE) {
				meshrabiyaApi.stopMeshExtenderHotspot { result ->
					if (result.isSuccess) {
						Log.i("EnhancedMeshFragment", "[EXTENDER] Mesh extender hotspot stopped")
					} else {
						Log.e("EnhancedMeshFragment", "[EXTENDER] Failed to stop: ${result.exceptionOrNull()?.message}")
					}
				}
			} else if (currentState == MeshExtenderHotspotStateDto.INACTIVE) {
				meshrabiyaApi.startMeshExtenderHotspot { result ->
					if (result.isSuccess) {
						Log.i("EnhancedMeshFragment", "[EXTENDER] Mesh extender hotspot started")
					} else {
						Log.e("EnhancedMeshFragment", "[EXTENDER] Failed to start: ${result.exceptionOrNull()?.message}")
					}
				}
			}
		}

		MeshUIBindings.cancelScanButton.setOnClickListener {
			android.util.Log.d("EnhancedMeshFragment", "Cancel scan button clicked")
			collapsePane()
		}

		MeshUIBindings.toggleFlashlightButton.setOnClickListener {
			toggleFlashlight()
		}

		MeshUIBindings.copyNetworkInfoButton.setOnClickListener {
			copyNetworkInfoToClipboard()
		}

		MeshUIBindings.meshControlHeader.setOnClickListener {
			val meshStatus = meshrabiyaApi.meshStatusFlow.value
			val paneVisible = MeshUIBindings.meshExpandableContent.visibility == View.VISIBLE

			if (paneVisible && (isJoinMeshMode || isMergeMeshMode)) {
				collapsePane()
			} else if (meshStatus == MeshStateDto.CONNECTED || meshStatus == MeshStateDto.CONNECTING) {
				if (paneVisible) {
					collapsePane()
				} else {
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
		if (!checkLocationPermissions()) {
			android.util.Log.d("EnhancedMeshFragment", "Permissions not granted, requesting now")
			locationRequestOrigin = LocationRequestOrigin.START_MESH
		}
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
		android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] ▶ enter updateUI(deferred=$deferredViewsInitialized)")
		
		// Ensure all UI updates happen on the main thread
        activity?.runOnUiThread {
            android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] ◇ inside runOnUiThread")
            // Mesh status — read from reactive cache, no polling
            val meshState = meshrabiyaApi.meshStatusFlow.value
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
			MeshUIBindings.meshRolesText.text =
				if (roles.isNotEmpty()) "Roles: ${roles.joinToString(", ")}"
				else "Roles: --"

			// Last update
			// val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
			// MeshUIBindings.lastUpdateText.text = "Last Updated: ${dateFormat.format(Date())}"

			// Only update deferred views if they're initialized
			if (deferredViewsInitialized) {
				android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] ▷ deferred block start")

				// Network Status – rely solely on the flow’s current value.
				// If the flow hasn’t emitted yet we do **nothing** (no fake
				// DTOs, no API call).  This keeps the frontend honest.
				val networkInfo = (meshrabiyaApi as? MeshrabiyaApiImpl)
					?.networkInfoFlow
					?.value

				if (networkInfo != null) {
					android.util.Log.d("EnhancedMeshFragment",
						"[UPDATE_UI] Applying networkInfo: peers=${networkInfo.connectedPeers}, " +
						"ssid=${networkInfo.nonMeshSsid}")

					MeshUIBindings.meshIpAddressText.text = networkInfo.ipAddress
					MeshUIBindings.networkStatsText.text =
						"Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
					val gatewayAvailable = meshrabiyaApi.getMeshInternetGatewayAvailableFlow().value
					MeshUIBindings.meshInternetGreenDot.visibility =
						if (gatewayAvailable) View.VISIBLE else View.GONE
					if (!networkInfo.nonMeshSsid.isNullOrEmpty()) {
                        MeshUIBindings.internetWifiRow.visibility = View.VISIBLE
                        MeshUIBindings.internetWifiIpText.text = networkInfo.nonMeshIpAddress ?: "--"
                        MeshUIBindings.internetWifiChipSta.visibility = View.VISIBLE
                        MeshUIBindings.internetWifiGreenDot.visibility =
                            if (networkInfo.nonMeshHasInternet == true) View.VISIBLE else View.GONE
                    } else {
                        MeshUIBindings.internetWifiRow.visibility = View.GONE
                    }
				} else {
					android.util.Log.d("EnhancedMeshFragment",
						"[UPDATE_UI] networkInfoFlow.value == null; deferring update")
					MeshUIBindings.meshIpAddressText.text = "–"
					MeshUIBindings.networkStatsText.text = ""
					MeshUIBindings.internetWifiRow.visibility = View.GONE
					MeshUIBindings.meshInternetGreenDot.visibility = View.GONE
				}

				// the remainder of deferred updates stays unchanged:
				

				android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] ◁ deferred block end")

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
		
			val quotaBytes = meshrabiyaApi.getStorageQuotaBytes()
			val quotaGB = quotaBytes / (1024.0 * 1024.0 * 1024.0)
			// Clamp to slider's valid range (1-50 GB)
			val clampedQuotaGB = quotaGB.toFloat().coerceIn(1.0f, 50.0f)
			MeshUIBindings.storageAllocationSlider.value = clampedQuotaGB
			MeshUIBindings.storageAllocationText.text = "${clampedQuotaGB.toInt()} GB"
			android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] Loaded storage quota from preferences: ${quotaGB}GB (clamped to ${clampedQuotaGB}GB)")

			// Update folder path display using URI-based storage
			val savedUri = meshrabiyaApi.getDropFolderUri()
			if (savedUri != null) {
				try {
					val uri = Uri.parse(savedUri)
					val docFile = DocumentFile.fromTreeUri(requireContext(), uri)
					val displayName = docFile?.name ?: uri.lastPathSegment ?: savedUri
					MeshUIBindings.selectedFolderText.text = displayName
					android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] Displaying drop folder: $displayName")
				} catch (e: Exception) {
					android.util.Log.e("EnhancedMeshFragment", "[UPDATE_UI] Error parsing folder URI: ${e.message}")
					MeshUIBindings.selectedFolderText.text = "No folder selected"
				}
			} else {
				MeshUIBindings.selectedFolderText.text = "No folder selected"
			}

			// Mesh files
			val meshFiles = meshrabiyaApi.getAllMeshFiles()
			// TODO: Update folderContentsAdapter with meshFiles
			
			android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] Deferred views updated successfully")
        	} else {
                android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] Skipping deferred view updates - not yet initialized")
            }
            android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] ◀ exit runOnUiThread")
        } // End runOnUiThread
		android.util.Log.d("EnhancedMeshFragment", "[UPDATE_UI] ◀ exit updateUI()")
	}
	
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
				meshrabiyaApi.setStorageQuotaBytes(quotaBytes)
				
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
					if (hasWritePermission()) {
						if (newFolder.mkdirs()) {
							android.util.Log.i("EnhancedMeshFragment", "Created folder: ${newFolder.absolutePath}")
							val folderUri = Uri.fromFile(newFolder)
							selectedFolderUri = folderUri
							meshrabiyaApi.setDropFolderUri(folderUri.toString())
							updateStorageAllocation(folderUri)
							updateUI()
							Snackbar.make(requireView(), "Folder created: $folderName", Snackbar.LENGTH_SHORT).show()
						} else {
							Snackbar.make(requireView(), "Failed to create folder", Snackbar.LENGTH_SHORT).show()
						}
					} else {
						pendingFolderName = folderName
						requestWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
        
        // Restore Join button label if we were in join mode
        if (isJoinMeshMode) {
            MeshUIBindings.joinMeshButton.text = "Join Mesh"
        }
        
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
		// Guard: immediate views only until deferred cards are inflated.
		// meshStatusFlow collector fires before ViewStub inflation completes —
		// accessing any deferred lateinit var here crashes with
		// UninitializedPropertyAccessException.
		if (!deferredViewsInitialized) {
			if (!meshrabiyaApi.isApCapable()) {
				MeshUIBindings.meshToggleButton.visibility = View.GONE
			} else {
				MeshUIBindings.meshToggleButton.visibility = View.VISIBLE
			}
			MeshUIBindings.meshToggleButton.text = when (meshStatus) {
				MeshStateDto.CONNECTED, MeshStateDto.CONNECTING -> "Stop Mesh"
				else -> "Start Mesh"
			}
			MeshUIBindings.meshToggleButton.isEnabled =
				meshStatus != MeshStateDto.INITIALIZING &&
				meshStatus != MeshStateDto.ERROR &&
				meshStatus != MeshStateDto.UNKNOWN
			MeshUIBindings.sendBroadcastButton.isEnabled =
				meshStatus == MeshStateDto.CONNECTED
			return  // bail — deferred views not yet bound
		}

		// All deferred views are now safe to access.
		if (!meshrabiyaApi.isApCapable()) {
			MeshUIBindings.meshToggleButton.visibility = View.GONE
		} else {
			MeshUIBindings.meshToggleButton.visibility = View.VISIBLE
		}

		when (meshStatus) {
			MeshStateDto.DISCONNECTED -> {
				MeshUIBindings.meshToggleButton.text = "Start Mesh"
				MeshUIBindings.meshToggleButton.isEnabled = true
				MeshUIBindings.joinMeshButton.visibility = View.VISIBLE
				MeshUIBindings.joinMeshButton.isEnabled = true
				MeshUIBindings.mergeMeshButton.visibility = View.GONE
				val extenderActive = meshrabiyaApi.meshExtenderHotspotStateFlow.value ==
					MeshExtenderHotspotStateDto.ACTIVE
				MeshUIBindings.expandCollapseIndicator.visibility =
					if (extenderActive) View.VISIBLE else View.GONE
				MeshUIBindings.sendBroadcastButton.isEnabled = false
			}
			MeshStateDto.CONNECTING -> {
				MeshUIBindings.meshToggleButton.text = "Stop Mesh"
				MeshUIBindings.meshToggleButton.isEnabled = true
				MeshUIBindings.joinMeshButton.visibility = View.GONE
				MeshUIBindings.mergeMeshButton.visibility = View.GONE
				MeshUIBindings.expandCollapseIndicator.visibility = View.VISIBLE
				MeshUIBindings.sendBroadcastButton.isEnabled = false
			}
			MeshStateDto.CONNECTED -> {
				MeshUIBindings.meshToggleButton.text = "Stop Mesh"
				MeshUIBindings.meshToggleButton.isEnabled = true
				MeshUIBindings.joinMeshButton.visibility = View.GONE
				MeshUIBindings.mergeMeshButton.visibility = View.VISIBLE
				MeshUIBindings.mergeMeshButton.isEnabled = true
				MeshUIBindings.expandCollapseIndicator.visibility = View.VISIBLE
				MeshUIBindings.sendBroadcastButton.isEnabled = true
			}
			MeshStateDto.INITIALIZING,
			MeshStateDto.ERROR,
			MeshStateDto.UNKNOWN -> {
				MeshUIBindings.meshToggleButton.isEnabled = false
				MeshUIBindings.joinMeshButton.visibility = View.GONE
				MeshUIBindings.mergeMeshButton.visibility = View.GONE
				MeshUIBindings.expandCollapseIndicator.visibility = View.GONE
				MeshUIBindings.sendBroadcastButton.isEnabled = false
			}
		}
	}
	
	/**
	 * Show broadcast message+file dialog
	 */
	private fun showBroadcastDialog() {
		val dialogView = layoutInflater.inflate(R.layout.dialog_broadcast, null)
		
		// Find views
		val messageInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.broadcastMessageInput)
        val messageCounterText = dialogView.findViewById<TextView>(R.id.messageCharacterCounter)
        val fileNameText = dialogView.findViewById<TextView>(R.id.selectedFileNameText)
        val includeLocationCheckbox = dialogView.findViewById<android.widget.CheckBox>(R.id.includeLocationCheckbox)
        val gpsLocationDisplay = dialogView.findViewById<TextView>(R.id.gpsLocationDisplay)
        val selectFileButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.selectFileButton)
        val clearFileButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.clearFileButton)
        val selectedFileContainer = dialogView.findViewById<android.view.ViewGroup>(R.id.selectedFileContainer)
        val sendButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.sendBroadcastDialogButton)
		val cancelButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelBroadcastDialogButton)
        val progressBar = dialogView.findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.sendProgressIndicator)
        val errorText = dialogView.findViewById<TextView>(R.id.errorMessageText)
        
        // Track selected file
        var selectedFileUri: Uri? = null
        
        // Local function to update send button state
        fun updateSendButtonState() {
			val messageLength = messageInput.text?.length ?: 0
			val hasMessage = messageLength > 0 && messageLength <= 500
			val hasFile = selectedFileUri != null
			val locationPending = includeLocationCheckbox.isChecked && pendingLatitude == null
			sendButton.isEnabled = (hasMessage || hasFile) && !locationPending
		}
        
        // Capture location immediately when checkbox checked (emergency use case - use cached location)
        includeLocationCheckbox.setOnCheckedChangeListener { _, isChecked ->
			Log.d("EnhancedMeshFragment", "includeLocationCheckbox toggled: $isChecked")
			if (!isChecked) {
				// Cancel any pending location request
				cancelPendingLocationRequest()
				
				// Clear location data and reset UI
				pendingLatitude = null
				pendingLongitude = null
				gpsLocationDisplay.visibility = View.GONE
				progressBar.visibility = View.GONE
				updateSendButtonState()
				Log.d("EnhancedMeshFragment", "[LOCATION] Checkbox unchecked - cleared coordinates")
				return@setOnCheckedChangeListener
			}

			// When checked, try IMMEDIATE cached location first (fast path)
			try {
				val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
				
				Log.d("EnhancedMeshFragment", "[LOCATION] Checkbox checked - attempting to get cached location")
				
				// Try GPS provider first, then NETWORK provider
				var location: android.location.Location? = null
				try {
					location = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
					if (location != null) {
						Log.d("EnhancedMeshFragment", "[LOCATION] GPS cached location available")
					}
				} catch (e: SecurityException) {
					Log.w("EnhancedMeshFragment", "[LOCATION] GPS location permission denied")
				}
				
				// Fallback to NETWORK if GPS unavailable
				if (location == null) {
					try {
						location = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
						if (location != null) {
							Log.d("EnhancedMeshFragment", "[LOCATION] NETWORK cached location available")
						}
					} catch (e: SecurityException) {
						Log.w("EnhancedMeshFragment", "[LOCATION] NETWORK location permission denied")
					}
				}
				
				if (location != null) {
					// FAST PATH: Cached location available - display immediately
					pendingLatitude = location.latitude
					pendingLongitude = location.longitude
					gpsLocationDisplay.text = String.format("📍 %.6f, %.6f", pendingLatitude, pendingLongitude)
					gpsLocationDisplay.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
					gpsLocationDisplay.visibility = View.VISIBLE
					progressBar.visibility = View.GONE
					updateSendButtonState()
					Log.d("EnhancedMeshFragment", 
						"[LOCATION] ✅ Cached location captured: lat=$pendingLatitude, lon=$pendingLongitude, " +
						"accuracy=${location.accuracy}m, age=${(System.currentTimeMillis() - location.time)/1000}s old")
				} else {
					// SLOW PATH: No cached location - start async GPS request
					Log.w("EnhancedMeshFragment", "[LOCATION] No cached location - starting async GPS request")
					
					// Show acquiring state
					pendingLatitude = null
					pendingLongitude = null
					gpsLocationDisplay.text = "Acquiring GPS..."
					gpsLocationDisplay.setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
					gpsLocationDisplay.visibility = View.VISIBLE
					progressBar.visibility = View.VISIBLE
					updateSendButtonState()
					
					// Start async location request with timeout
					startAsyncLocationRequest(lm, progressBar, gpsLocationDisplay, sendButton, ::updateSendButtonState)
				}
			} catch (e: Exception) {
				// Unexpected error - show error message and disable send button
				Log.e("EnhancedMeshFragment", "[LOCATION] Failed to get location", e)
				pendingLatitude = null
				pendingLongitude = null
				gpsLocationDisplay.text = "Location Error"
				gpsLocationDisplay.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
				gpsLocationDisplay.visibility = View.VISIBLE
				progressBar.visibility = View.GONE
				updateSendButtonState()
			}
		}

		
        
        // Initialize button state immediately
        updateSendButtonState()
        
        // Set up file selection callback for pre-registered launcher
        pendingFileCallback = { uri ->
            android.util.Log.d("EnhancedMeshFragment", "File selection callback invoked, URI: $uri")
            selectedFileUri = uri
            // Get file name from URI
            val docFile = DocumentFile.fromSingleUri(requireContext(), uri)
            android.util.Log.d("EnhancedMeshFragment", "DocumentFile: $docFile, name: ${docFile?.name}")
            val fileName = docFile?.name ?: "Unknown file"
            android.util.Log.d("EnhancedMeshFragment", "Setting fileName text to: $fileName")
            fileNameText.text = fileName
            selectedFileContainer.visibility = View.VISIBLE  // Show parent container
            android.util.Log.d("EnhancedMeshFragment", "File name display updated, container visibility: ${selectedFileContainer.visibility}")
            updateSendButtonState()
        }
		
		// Character counter update
		messageInput.addTextChangedListener(object : android.text.TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
			override fun afterTextChanged(s: android.text.Editable?) {
				val length = s?.length ?: 0
				messageCounterText.text = "$length / 500"
				
				// Show red if exceeds limit
				if (length > 500) {
					messageCounterText.setTextColor(android.graphics.Color.RED)
				} else {
					messageCounterText.setTextColor(
						android.content.res.Resources.getSystem()
							.getColor(android.R.color.darker_gray, null)
					)
				}
				
				updateSendButtonState()
			}
		})
		
		// Select file button - use pre-registered launcher
		selectFileButton.setOnClickListener {
			// Launch file picker with all MIME types
			broadcastFilePicker.launch(arrayOf("*/*"))
		}
		
		// Clear file button
		clearFileButton.setOnClickListener {
            selectedFileUri = null
            selectedFileContainer.visibility = View.GONE  // Hide parent container
            updateSendButtonState()
        }
		
		
		
		// Create dialog
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Send Broadcast")
            .setView(dialogView)
            .create()

        // Clean up callback and location request when dialog is dismissed
        dialog.setOnDismissListener {
            pendingFileCallback = null
            cancelPendingLocationRequest()
        }
		
		// Send button
		sendButton.setOnClickListener {
            val messageText = messageInput.text?.toString() ?: ""

            // basic validation
            if (messageText.isEmpty() && selectedFileUri == null) {
                errorText.text = "Please enter a message or select a file"
                errorText.visibility = View.VISIBLE
                return@setOnClickListener
            }
            if (messageText.length > 500) {
                errorText.text = "Message exceeds 500 character limit"
                errorText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // resolve file URI to path if needed
            var filePath = ""
            selectedFileUri?.let { uri ->
                try {
                    val inputStream = requireContext().contentResolver.openInputStream(uri)
                    val fileName = DocumentFile.fromSingleUri(requireContext(), uri)?.name ?: "broadcast_file"
                    val cacheFile = java.io.File(requireContext().cacheDir, fileName)
                    inputStream?.use { input ->
                        cacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    filePath = cacheFile.absolutePath
                } catch (e: Exception) {
                    errorText.text = "Failed to access file: ${e.message}"
                    errorText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
            }

            

            // latitude/longitude will be whatever was stored above
            // ========================================
            // LOCATION DIAGNOSTICS - PRE-SEND
            // ========================================
            Log.d("EnhancedMeshFragment", "[SEND] ========== BROADCAST SEND DIAGNOSTICS ==========")
            Log.d("EnhancedMeshFragment", "[SEND] Checkbox state: isChecked=${includeLocationCheckbox.isChecked}")
            Log.d("EnhancedMeshFragment", "[SEND] pendingLatitude (raw): $pendingLatitude")
            Log.d("EnhancedMeshFragment", "[SEND] pendingLongitude (raw): $pendingLongitude")
            
            // latitude/longitude will be whatever was stored above
            val latitude: Double? = if (includeLocationCheckbox.isChecked) pendingLatitude else null
            val longitude: Double? = if (includeLocationCheckbox.isChecked) pendingLongitude else null
            
            Log.d("EnhancedMeshFragment", "[SEND] latitude (after ?:): $latitude")
            Log.d("EnhancedMeshFragment", "[SEND] longitude (after ?:): $longitude")
            Log.d("EnhancedMeshFragment", "[SEND] message: '$messageText'")
            Log.d("EnhancedMeshFragment", "[SEND] filePath: '$filePath'")
            Log.d("EnhancedMeshFragment", "[SEND] ======================================================")

            // show spinner and send
            progressBar.visibility = View.VISIBLE
            sendButton.isEnabled = false
            errorText.visibility = View.GONE

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    meshrabiyaApi.broadcastMessageAndFile(messageText, filePath, latitude, longitude)
                    activity?.runOnUiThread {
                        dialog.dismiss()
                        view?.let { v ->
                            Snackbar.make(v, "Broadcast sent successfully", Snackbar.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    activity?.runOnUiThread {
                        progressBar.visibility = View.GONE
                        sendButton.isEnabled = true
                        errorText.text = "Failed to send: ${e.message}"
                        errorText.visibility = View.VISIBLE
                    }
                }
            }
        }
		
		
		// Initial button state
		updateSendButtonState()
		
		// Cancel button dismisses dialog
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
		// Show dialog
		dialog.show()
	}
	
	private fun startAsyncLocationRequest(
        locationManager: android.location.LocationManager,
        progressIndicator: com.google.android.material.progressindicator.CircularProgressIndicator,
        locationDisplay: TextView,
        sendBtn: com.google.android.material.button.MaterialButton,
        updateButtonState: () -> Unit
    ) {
		// Cancel any existing request first
		cancelPendingLocationRequest()
		
		Log.d("EnhancedMeshFragment", "[LOCATION] Starting async GPS request (60s timeout) with HIGH_ACCURACY priority")
		
		// Create location listener for callback
		val listener = object : android.location.LocationListener {
			override fun onLocationChanged(location: android.location.Location) {
				Log.d("EnhancedMeshFragment", "[LOCATION] ✅ Async location received: lat=${location.latitude}, lon=${location.longitude}")
				
				// Store coordinates
				pendingLatitude = location.latitude
				pendingLongitude = location.longitude
				
				// Update UI on main thread
				activity?.runOnUiThread {
                    locationDisplay.text = String.format("📍 %.6f, %.6f", pendingLatitude, pendingLongitude)
                    locationDisplay.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                    locationDisplay.visibility = View.VISIBLE
                    progressIndicator.visibility = View.GONE
                    updateButtonState()
                }
				
				// Clean up listener
				cancelPendingLocationRequest()
			}
			
			@Deprecated("Deprecated in Java")
			override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
			}
			
			override fun onProviderEnabled(provider: String) {
				Log.d("EnhancedMeshFragment", "[LOCATION] Provider enabled: $provider")
			}
			
			override fun onProviderDisabled(provider: String) {
				Log.w("EnhancedMeshFragment", "[LOCATION] Provider disabled: $provider")
			}
		}
		
		// Store listener reference for cancellation
        activeLocationListener = listener
        
        // Request location update from GPS with HIGH_ACCURACY priority
        try {
            // Use Criteria to force GPS satellite usage (not WiFi/cell tower fallback)
            // This is compatible with API 24+ (minSdk)
            val criteria = android.location.Criteria().apply {
                accuracy = android.location.Criteria.ACCURACY_FINE  // Forces GPS satellites
                powerRequirement = android.location.Criteria.POWER_HIGH  // Allow high power for GPS
                isAltitudeRequired = false
                isBearingRequired = false
                isSpeedRequired = false
            }
            
            locationManager.requestSingleUpdate(
                criteria,
                listener,
                android.os.Looper.getMainLooper()
            )
            Log.d("EnhancedMeshFragment", "[LOCATION] GPS request registered successfully (ACCURACY_FINE - GPS satellites forced)")
        } catch (e: SecurityException) {
            Log.e("EnhancedMeshFragment", "[LOCATION] Permission denied for async GPS request", e)
            activity?.runOnUiThread {
                locationDisplay.text = "Permission Denied"
                locationDisplay.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                progressIndicator.visibility = View.GONE
                updateButtonState()
            }
            activeLocationListener = null
			return
		} catch (e: Exception) {
            Log.e("EnhancedMeshFragment", "[LOCATION] Failed to start async GPS request", e)
            activity?.runOnUiThread {
                locationDisplay.text = "Location Error"
                locationDisplay.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                progressIndicator.visibility = View.GONE
                updateButtonState()
            }
            activeLocationListener = null
            return
		}
		
		// Start timeout timer (60 seconds)
		locationRequestJob = viewLifecycleOwner.lifecycleScope.launch {
			delay(60000)
			
			// Timeout - check if location still not acquired
			if (pendingLatitude == null && activeLocationListener != null) {
				Log.w("EnhancedMeshFragment", "[LOCATION] ❌ GPS request timed out after 60s")
                
                activity?.runOnUiThread {
                    locationDisplay.text = "GPS Timeout - Try outside"
                    locationDisplay.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                    progressIndicator.visibility = View.GONE
                    updateButtonState()
                }
				
				// Cancel listener
				cancelPendingLocationRequest()
			}
		}
	}

	private fun cancelPendingLocationRequest() {
		// Cancel timeout job
		locationRequestJob?.cancel()
		locationRequestJob = null
		
		// Remove location listener
		activeLocationListener?.let { listener ->
			try {
				val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
				lm.removeUpdates(listener)
				Log.d("EnhancedMeshFragment", "[LOCATION] Location request cancelled")
			} catch (e: Exception) {
				Log.e("EnhancedMeshFragment", "[LOCATION] Failed to cancel location request", e)
			}
			activeLocationListener = null
		}
	}
	
	
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
					meshrabiyaApi.setStorageQuotaBytes(quotaBytes)
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
			
			val quotaBytes = meshrabiyaApi.getStorageQuotaBytes()
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

	/**
     * Get list of received broadcast notifications
     */
    fun getNotificationFeed(): StateFlow<List<NotificationFeedEntry>> = notificationFeed
    
    /**
     * Return the shared adapter used by both fragment and activity dropdown.
     * This allows the activity to display the same list without maintaining its
     * own copy. The adapter is initialized in onCreateView.
     */
    fun getNotificationsAdapter(): NotificationsAdapter =
		if (this::notificationsAdapter.isInitialized) notificationsAdapter
		else NotificationsAdapter(emptyList()) { entry -> removeNotification(entry) }

	fun clearNotifications() {
        broadcastNotifications.value = emptyList()
        statusNotifications.value = emptyList()
        storageNotifications.value = emptyList()
        (activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(0)
    }

    fun removeNotification(entry: NotificationFeedEntry) {
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

    // ========================================
    // WiFi Internet Connection (WIFI_AP_CON / Change 16)
    // ========================================

    private fun showInternetWifiConnectionDialog() {
        val scanningDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Scanning for networks…")
            .setMessage("Please wait")
            .setCancelable(false)
            .create()
        scanningDialog.show()
        lifecycleScope.launch {
            val networks = meshrabiyaApi.scanAvailableWifiNetworks()
            scanningDialog.dismiss()
            if (networks.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "No WiFi networks found. Ensure location permission is granted.",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val ssidList = networks.map { "${it.ssid} (${it.signalStrength} dBm)" }.toTypedArray()
            var selectedIndex = 0

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Connect to Internet WiFi")
                .setSingleChoiceItems(ssidList, 0) { _, which -> selectedIndex = which }
                .setPositiveButton("Connect") { _, _ ->
                    val selected = networks[selectedIndex]
                    android.util.Log.d("EnhancedMeshFragment",
                        "[WIFI] user selected SSID=${selected.ssid}, secured=${selected.isSecured}")
                    if (selected.isSecured) {
                        showPassphraseDialog(selected.ssid)
                    } else {
                        connectToInternetWifi(selected.ssid, "")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showPassphraseDialog(ssid: String) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "WiFi Password"
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Connect to $ssid")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                connectToInternetWifi(ssid, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectToInternetWifi(ssid: String, passphrase: String) {
        lifecycleScope.launch {
            val result = meshrabiyaApi.connectToNonMeshWifi(ssid, passphrase)
            // Avoid importing NonMeshWifiStatus enum — use DTO field checks instead.
            val message = when {
                result.connectedSsid != null -> "Connected to $ssid"
                result.errorMessage != null  -> "Connection failed: ${result.errorMessage}"
                else                         -> "Connecting to $ssid..."
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
}

## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt

package org.torproject.android.ui.mesh

import org.torproject.android.R

import android.view.View
import android.widget.TextView
import android.widget.ImageView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.slider.Slider
import androidx.recyclerview.widget.RecyclerView
import android.widget.LinearLayout
// import org.torproject.android.ui.mesh.adapter.FolderContentsAdapter

object MeshUIBindings {
    lateinit var textUploadBitrate: TextView
    lateinit var textDownloadBitrate: TextView
    lateinit var textActiveNodeCount: TextView
    lateinit var meshStatusText: TextView
    lateinit var nodeInfoText: TextView  // kept for backward compat; not updated post-C4
    lateinit var meshIpAddressText: TextView
    lateinit var meshInternetGreenDot: View
    lateinit var meshChipMesh: com.google.android.material.chip.Chip
    lateinit var meshChipSta: com.google.android.material.chip.Chip
    lateinit var meshChipAp: com.google.android.material.chip.Chip
    lateinit var internetWifiRow: android.widget.LinearLayout
    lateinit var internetWifiIpText: TextView
    lateinit var internetWifiChipSta: com.google.android.material.chip.Chip
    lateinit var internetWifiGreenDot: View
    lateinit var internetWifiChipWifi: com.google.android.material.chip.Chip
    
    lateinit var meshExtenderApButton: android.widget.ImageButton
    lateinit var meshExtenderApRow: android.widget.LinearLayout
    lateinit var meshExtenderApIpText: TextView
    lateinit var meshExtenderApChipAp: com.google.android.material.chip.Chip
    lateinit var meshExtenderApChipMesh: com.google.android.material.chip.Chip
    lateinit var meshRolesText: TextView
    lateinit var networkStatsText: TextView
    lateinit var lastUpdateText: TextView
    lateinit var gatewayToggle: SwitchMaterial
    lateinit var internetGatewayToggle: SwitchMaterial
    
    lateinit var sendBroadcastButton: MaterialButton
    lateinit var meshToggleButton: MaterialButton
    
    // Mesh control card and new buttons
    lateinit var meshControlCard: MaterialCardView
    lateinit var meshControlHeader: LinearLayout
    lateinit var joinMeshButton: MaterialButton
    lateinit var mergeMeshButton: MaterialButton
    lateinit var wifiApConnectionButton: MaterialButton
    lateinit var expandCollapseIndicator: ImageView
    
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
    
    lateinit var torGatewayCard: MaterialCardView
    lateinit var internetGatewayCard: MaterialCardView
    lateinit var networkOverviewCard: MaterialCardView
    lateinit var storageParticipationCard: MaterialCardView
    lateinit var storageParticipationToggle: SwitchMaterial
    lateinit var storageAllocationSlider: Slider
    lateinit var storageStatusText: TextView
    lateinit var storageAllocationText: TextView
    lateinit var storageDropFolderCard: MaterialCardView
    lateinit var selectFolderButton: MaterialButton
    lateinit var createFolderButton: MaterialButton
    lateinit var selectedFolderText: TextView
    lateinit var folderContentsRecyclerView: RecyclerView
    // lateinit var folderContentsAdapter: FolderContentsAdapter
    lateinit var distributedServiceLayerCard: MaterialCardView
    lateinit var serviceLayerParticipationSwitch: SwitchMaterial
    lateinit var serviceLayerStatusText: TextView
    lateinit var pythonServiceStatus: TextView
    lateinit var mlInferenceServiceStatus: TextView
    lateinit var distributedStorageServiceStatus: TextView
    lateinit var taskSchedulerServiceStatus: TextView
    lateinit var torGatewayStatus: TextView
    lateinit var internetGatewayStatus: TextView
    lateinit var activeNodesText: TextView
    // lateinit var networkLoadText: TextView
    // lateinit var stabilityText: TextView

    fun bindImmediateViews(view: View) {
        // Cards 1-3: Always present in initial layout
        meshStatusText = view.findViewById(R.id.meshStatusText)
        meshRolesText = view.findViewById(R.id.meshRolesText)
        lastUpdateText = view.findViewById(R.id.lastUpdateText)
        
        sendBroadcastButton = view.findViewById(R.id.sendBroadcastButton)
        meshToggleButton = view.findViewById(R.id.meshToggleButton)
        
        // Mesh control card and new buttons
        meshControlCard = view.findViewById(R.id.meshControlCard)
        meshControlHeader = view.findViewById(R.id.meshControlHeader)
        joinMeshButton = view.findViewById(R.id.joinMeshButton)
        mergeMeshButton = view.findViewById(R.id.mergeMeshButton)
        wifiApConnectionButton = view.findViewById(R.id.wifiApConnectionButton)
        // AP extender button is now part of the immediate mesh control header
        meshExtenderApButton = view.findViewById(R.id.meshExtenderApButton)
        expandCollapseIndicator = view.findViewById(R.id.expandCollapseIndicator)
        
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
        
        // Network overview card (Card 3 - always immediate)
        networkOverviewCard = view.findViewById(R.id.networkOverviewCard)
        textUploadBitrate = view.findViewById(R.id.text_upload_bitrate)
        textDownloadBitrate = view.findViewById(R.id.text_download_bitrate)
        textActiveNodeCount = view.findViewById(R.id.text_active_node_count)
        // networkLoadText = view.findViewById(R.id.networkLoadText)
        // stabilityText = view.findViewById(R.id.stabilityText)
    }
    
    fun bindDeferredViews(view: View) {
        // Cards 4-9: Loaded from ViewStub after 300ms
        // nodeInfoText id removed from XML in C4; keep field for compile compat but do not bind
        // nodeInfoText = view.findViewById(R.id.nodeInfoText)
        meshIpAddressText = view.findViewById(R.id.meshIpAddressText)
        meshInternetGreenDot = view.findViewById(R.id.meshInternetGreenDot)
        meshChipMesh = view.findViewById(R.id.meshChipMesh)
        meshChipSta = view.findViewById(R.id.meshChipSta)
        meshChipAp = view.findViewById(R.id.meshChipAp)
        internetWifiRow = view.findViewById(R.id.internetWifiRow)
        internetWifiIpText = view.findViewById(R.id.internetWifiIpText)
        internetWifiGreenDot = view.findViewById(R.id.internetWifiGreenDot)
        internetWifiChipWifi = view.findViewById(R.id.internetWifiChipWifi)
        internetWifiChipSta = view.findViewById(R.id.internetWifiChipSta)
        
        // AP extender button is bound immediately; remaining deferred elements may still exist
        meshExtenderApRow = view.findViewById(R.id.meshExtenderApRow)
        meshExtenderApIpText = view.findViewById(R.id.meshExtenderApIpText)
        meshExtenderApChipAp = view.findViewById(R.id.meshExtenderApChipAp)
        meshExtenderApChipMesh = view.findViewById(R.id.meshExtenderApChipMesh)
        networkStatsText = view.findViewById(R.id.networkStatsText)
        
        torGatewayCard = view.findViewById(R.id.torGatewayCard)
        gatewayToggle = view.findViewById(R.id.gatewayToggle)
        torGatewayStatus = view.findViewById(R.id.torGatewayStatus)
        
        internetGatewayCard = view.findViewById(R.id.internetGatewayCard)
        internetGatewayToggle = view.findViewById(R.id.internetGatewayToggle)
        internetGatewayStatus = view.findViewById(R.id.internetGatewayStatus)
        
        storageParticipationCard = view.findViewById(R.id.storageParticipationCard)
        storageParticipationToggle = view.findViewById(R.id.storageParticipationToggle)
        storageAllocationSlider = view.findViewById(R.id.storageAllocationSlider)
        storageStatusText = view.findViewById(R.id.storageStatusText)
        storageAllocationText = view.findViewById(R.id.storageAllocationText)
        
        storageDropFolderCard = view.findViewById(R.id.storageDropFolderCard)
        selectFolderButton = view.findViewById(R.id.selectFolderButton)
        createFolderButton = view.findViewById(R.id.createFolderButton)
        selectedFolderText = view.findViewById(R.id.selectedFolderText)
        folderContentsRecyclerView = view.findViewById(R.id.folderContentsRecyclerView)
        
        distributedServiceLayerCard = view.findViewById(R.id.distributedServiceLayerCard)
        serviceLayerParticipationSwitch = view.findViewById(R.id.serviceLayerParticipationSwitch)
        serviceLayerStatusText = view.findViewById(R.id.serviceLayerStatusText)
        pythonServiceStatus = view.findViewById(R.id.pythonServiceStatus)
        mlInferenceServiceStatus = view.findViewById(R.id.mlInferenceServiceStatus)
        distributedStorageServiceStatus = view.findViewById(R.id.distributedStorageServiceStatus)
        taskSchedulerServiceStatus = view.findViewById(R.id.taskSchedulerServiceStatus)
    }
}


## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt
package com.ustadmobile.meshrabiya.api
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
    
import java.io.File
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import com.ustadmobile.meshrabiya.vnet.MeshFile
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import kotlinx.serialization.Serializable
// UNUSED SCHEDULER IMPORTS - Commented 2025-11-12
// Scheduler infrastructure not used in Phase 3-4 ML-capable compute implementation
// Phase 3-4 uses direct broadcast-response pattern (processTaskRequest → node selection)
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ComputeTask
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ExecutionPlan
import com.ustadmobile.meshrabiya.model.MeshState
import com.ustadmobile.meshrabiya.model.NetworkInfo
import com.ustadmobile.meshrabiya.model.NodeInfo
import com.ustadmobile.meshrabiya.model.ApiResult
import com.ustadmobile.meshrabiya.vnet.LocalNodeState
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.DropFolderItem
import com.ustadmobile.meshrabiya.storage.StoreFileTrigger
import com.ustadmobile.meshrabiya.api.model.*

// import com.ustadmobile.meshrabiya.model.ServiceAnnouncement

/**
 * Unified API interface for Meshrabiya module.
 * Exposes all key operations, state, and event registration for UI/control layers.
 */
interface MeshrabiyaApi {
    val meshStatusFlow: kotlinx.coroutines.flow.StateFlow<MeshStateDto>
    val networkOverviewMetricsFlow: kotlinx.coroutines.flow.StateFlow<NetworkOverviewMetricsDto>

    /**
     * Provide application context to the Meshrabiya core (for use by TaskManager, etc.)
     */
    fun provideAppContext(context: Context)

    /**
     * Retrieve the application context for internal use (TaskManager, etc.)
     */
    fun getAppContext(): Context?

    // --- Mesh Initialization ---
    fun initMesh(context: Context)

    // --- Mesh State & Network Info ---
    // fun getNodeRole(): Byte
    fun getNodeRoleNames(): List<String>
    fun getFitnessScore(): Float
    fun getConnectionUri(): String
    fun getLocalNodeState(): LocalNodeStateDto
    fun getNeighbors(): List<NeighborInfoDto> 
    fun getHopCountToNode(nodeId: Int): Int?
    fun getConnectLink(): String?
    fun getConnectLinkFlow(): Flow<String?>

    // --- Mesh Network Controls ---
    fun startMesh(callback: (Result<Unit>) -> Unit)
    fun stopMesh(callback: (Result<Unit>) -> Unit)
    fun getMeshStatus(): MeshStateDto
    fun refreshMeshStatus()
    fun getPeerCount(): Int
    fun getNetworkInfo(): NetworkInfoDto?
    fun getNodeInfo(nodeId: String): NodeInfoDto?
    fun getNodeId(): Int
    
    /**
     * Get current hotspot information (credentials, band, node address)
     * 
     * Returns network credentials that peers can use to join this mesh.
     * The hotspot must be active (mesh CONNECTED state) to retrieve valid credentials.
     * 
     * **Android Version Differences:**
     * - Android 13+: Managed hotspot with predictable SSID ("meshr-<hex>") and shared password
     * - Android 8-12: LocalOnlyHotspot with random SSID/password
     * 
     * @return HotspotInfoDto containing ssid, password, band, nodeAddress, bssid, hotspotType
     *         Returns null if mesh is not in CONNECTED state (hotspot not active)
     */
    fun getHotspotInfo(): HotspotInfoDto?
    
    /**
     * Join an existing mesh network using mesh-wide discovery
     * 
     * This method scans for ALL available mesh hotspots and connects to the strongest one.
     * This enables resilient joining - if the QR code generator's hotspot is offline,
     * the device will automatically connect to any other available mesh hotspot.
     * 
     * This method can be called from ANY mesh state:
     * - DISCONNECTED: Device will initialize mesh and connect as station
     * - CONNECTING: Will switch to new network
     * - CONNECTED: Will broadcast merge announcement, then add station connection
     * 
     * **Process:**
     * 1. IF CONNECTED: Broadcast merge announcement to current mesh (5s delay for propagation)
     * 2. Parses JSON QR code data (password, SSID pattern)
     * 3. Scans for all SSIDs matching "meshr-*"
     * 4. Sorts by signal strength and attempts connection (strongest first)
     * 5. Retries scan up to 3 times if no hotspots found
     * 6. Stores password for automatic reconnection if hotspot changes
     * 
     * @param jsonQrData JSON string from scanned QR code containing:
     *                   {"type":"mesh_join", "password":"...", "ssidPattern":"meshr-*", "bootstrapSSID":"..."}
     * @param callback Result callback invoked on completion
     *                 Success(Unit) on successful connection to any mesh hotspot
     *                 Failure(exception) if no mesh hotspots available after retries
     */
    fun joinMesh(jsonQrData: String, callback: (Result<Unit>) -> Unit)
    
    /**
     * Merge current mesh with another mesh network (CONNECTED state only)
     * 
     * **USE CASE: Merging two existing meshes**
     * - Device is ALREADY connected to a mesh
     * - User scans QR of another mesh to merge
     * - ALWAYS broadcasts merge announcement first
     * 
     * **ORGANIC MESH MERGE WORKFLOW (PT8):**
     * 1. Broadcast MeshMergeAnnouncement to ALL devices on current mesh
     * 2. Wait 5 seconds for multi-hop gossip propagation
     * 3. Connect this device to target mesh (add station connection)
     * 4. Other devices receive announcement and independently decide to join
     * 5. Idempotent check prevents duplicate joins (same SSID/password)
     * 
     * **Key Differences from joinMesh():**
     * - mergeMesh() REQUIRES CONNECTED state (returns error if DISCONNECTED)
     * - ALWAYS broadcasts announcement (joinMesh() only broadcasts if CONNECTED)
     * - Clearer user intent: "I want to merge two meshes"
     * - UI: Separate "Merge Mesh" button (enabled only when CONNECTED)
     * 
     * **Requirements:**
     * - Multi-hop forwarding MUST be enabled (VirtualNode.kt Lines 702-722 uncommented)
     * - MeshMergeAnnouncementMessage must be implemented (PT8 Change 2)
     * - MeshConfigStorage must be implemented (PT8 Change 4)
     * - EmergentRoleManager must forward broadcasts (MESH_ROUTER role)
     * 
     * See PT8 for complete implementation details.
     * See MESH_GROUP_MERGING_RESEARCH_FINDINGS.md for organic merge strategy.
     * 
     * @param jsonQrData JSON string from scanned QR code containing:
     *                   {"type":"mesh_join", "password":"...", "ssidPattern":"meshr-*", "bootstrapSSID":"..."}
     * @param callback Result callback invoked on completion
     *                 Success(Unit) on successful merge (announcement broadcast + connection)
     *                 Failure(exception) if not CONNECTED, no hotspots found, or connection fails
     */
    fun mergeMesh(jsonQrData: String, callback: (Result<Unit>) -> Unit)
    
    // --- Proxy Controls ---
    fun setProxy(host: String, port: Int)
    fun setProxyActive(active: Boolean)

    // --- Gateway Controls ---
    fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getTorGatewayStatus(): Boolean
    fun setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getInternetGatewayStatus(): Boolean
    fun getGatewayStatus(): Boolean

    // --- V3: Gateway Preference Controls ---
    /**
     * Set the global gateway preference for internet-bound traffic.
     * 
     * **Precedence:** Per-app VPN rules (Orbot SharedPreferences "PrefTord") supersede this preference.
     * 
     * @param preference Gateway routing policy (TOR_ONLY, CLEARNET_ONLY, EITHER)
     * @param callback Result callback (Success if saved, Failure on error)
     */
    fun setGatewayPreference(preference: GatewayPreference, callback: (Result<Unit>) -> Unit)

    /**
     * Get the current global gateway preference.
     * 
     * @return Current gateway preference (TOR_ONLY, CLEARNET_ONLY, or EITHER)
     */
    fun getGatewayPreference(): GatewayPreference

    /**
     * Query current Tor daemon status from Orbot.
     * 
     * **Implementation:** Reads last known status from TorStatusMonitor BroadcastReceiver.
     * 
     * @return true if Tor is running ("ON" status), false otherwise
     */
    fun isTorActive(): Boolean

    // --- Storage Participation ---
    fun setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getStorageParticipationStatus(): Boolean
    fun getAvailableStorageDevices(): List<StorageDeviceDto>
    fun setStorageAllocation(deviceId: String, 
    path: String,
    allocatedMB: Long, )
    fun getStorageAllocations(): List<StorageAllocationDto>
    fun enableDistributedStorage()
    fun disableDistributedStorage()
    fun isComputeLayerParticipating(): Boolean

    
    /**
     * Set the drop folder URI for broadcast file reception.
     * @param uri The content:// URI from Android's folder picker
     */
    fun setDropFolderUri(uri: String)
    
    /**
     * Get the stored drop folder URI.
     * @return The content:// URI, or null if not set
     */
    fun getDropFolderUri(): String?
    
    /**
     * Set the storage quota for mesh participation in bytes.
     * @param quotaBytes Maximum storage allocation in bytes
     */
    fun setStorageQuotaBytes(quotaBytes: Long)
    
    /**
     * Get the configured storage quota in bytes.
     * @return Quota in bytes (default: 100MB)
     */
    fun getStorageQuotaBytes(): Long

    // --- Broadcast Message+File Operations ---
    /**
     * Broadcast a message and/or file to all nodes in the mesh (suspend version)
     * 
     * Success results are reported via setOnBroadcastSent() handler.
     * Failures are reported via setOnBroadcastFailed() handler.
     * 
     * @param messageText Text message to broadcast (max 500 chars, can be empty if file provided)
     * @param filePath Absolute path to file to broadcast (can be empty if message provided)
     * @throws IllegalArgumentException if both messageText and filePath are empty
     * @throws IllegalArgumentException if message exceeds 500 characters
     * @throws IllegalStateException if drop folder not selected
     * @throws IllegalStateException if mesh is not running
     */
    suspend fun broadcastMessageAndFile(
        messageText: String = "",
        filePath: String = "",
        latitude: Double? = null,
        longitude: Double? = null
    )
    
    /**
     * Register a listener for received broadcasts
     * 
     * Listener is called on background thread when broadcast is fully received
     * and file has been written to Shared/ folder.
     * 
     * Added: 2026-02-01 for NETWORK_BROADCAST_v2 implementation
     * 
     * @param listener Callback for received broadcasts
     */
    fun registerBroadcastListener(listener: (com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit)
    
    /**
     * Unregister a broadcast listener
     * 
     * Added: 2026-02-01 for NETWORK_BROADCAST_v2 implementation
     */
    fun unregisterBroadcastListener(listener: (com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit)

    /**
     * Register handler for successful broadcast completion
     * Handler is invoked on background thread when broadcast is fully sent
     * 
     * @param handler Callback with broadcast result details
     */
    fun setOnBroadcastSent(handler: (com.ustadmobile.meshrabiya.api.model.BroadcastResultDto) -> Unit)

    /**
     * Register handler for broadcast failures
     * Handler is invoked on background thread when broadcast fails
     * 
     * @param handler Callback with failure details
     */
    fun setOnBroadcastFailed(handler: (broadcastId: String, error: Throwable) -> Unit)

    /**
     * Enable or disable the entire compute service (persistent, global).
     */
    fun setComputeLayerParticipatingEnabled(enabled: Boolean)

    // --- Drop Folder Management ---
    // fun selectDropFolder(path: String, callback: (Result<Unit>) -> Unit)
    // fun getDropFolder(): File?
    fun getDropFolderFiles(): List<File>
    fun setOnDropFolderUpdate(handler: (List<DropFolderItemDto>) -> Unit)

    // --- File Operations ---
    fun storeFile(file: File, recipients:List<RecipientEntryDto>) 
    suspend fun retrieveFile(fileId: String): ByteArray? 
    fun streamFile(fileId: String, callback: (Result<Unit>) -> Unit)
    fun deleteFile(fileId: String, callback: (Result<Unit>) -> Unit)
    fun getAllMeshFiles(): List<MeshFileDto>

    // --- Distributed Service Layer ---
    fun setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getAvailableServices(): List<String>
    fun getServiceParticipationStatus(serviceId: String): Boolean

    // --- Compute/Task Operations ---
    fun addTask(serviceId: String,requestParams: Map<String, Any>, recipients: List<RecipientEntryDto>): Any?
    // fun startTask(taskId: String, callback: (Result<Unit>) -> Unit)
    fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit)
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // These methods reference scheduler types (ExecutionPlan, ComputeTask) that are unused in Phase 3-4
    // Phase 3-4 uses IntelligentDistributedComputeService.processTaskRequest() with direct node selection
    // If scheduler needed in future, uncomment these and restore ExecutionPlan/ComputeTask imports
    // fun getTaskStatus(taskId: String): ExecutionPlan?
    // fun getAllTasks(): List<ComputeTask>
    
    // DEPRECATED 2025-12-06: getJobTypes() removed
    // JobType is for ServiceLibraryEntry categorization only, not for API-level task validation
    // Use taskType (execution engine: python, jvm, js, ml-native) for task submission

    // --- Event Registration ---
    fun setOnFileRetrieved(handler: (fileId: String, file: File) -> Unit)
    fun setOnFileStored(handler: (fileId: String, file: File, result: Result<String>) -> Unit)
    fun setOnPermissionUpdated(handler: (fileId: String, success: Boolean) -> Unit)
    fun setOnOperationFailed(handler: (operation: String, error: Throwable) -> Unit)
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // This callback uses ExecutionPlan type from unused scheduler infrastructure
    // fun setOnTaskCompleted(handler: (taskId: String, result: ExecutionPlan) -> Unit)
    
    fun setOnFileShared(handler: (fileId: String, recipientId: String) -> Unit)
    fun setOnFileAddedToDropFolder(handler: (fileId: String, file: File) -> Unit)

    // --- Settings and State ---
    fun getSettings(): Map<String, Any>
    fun setSetting(key: String, value: Any, callback: (Result<Unit>) -> Unit)

    // --- Service Bundle & Gateway Controls ---
    // fun announceService(serviceAnnouncement: ServiceAnnouncement, signedBundle: ByteArray, callback: (Result<Unit>) -> Unit)
    // fun requestServiceBundle(serviceId: String, requesterOnionAddress: String, callback: (Result<ByteArray?>) -> Unit)
    fun setOnGatewayTraffic(handler: (packet: VirtualPacket) -> Boolean)
    fun getMeshTrafficRouterStatus(): String

    // --- Event/Callback Integration ---
    fun setOnMeshStateChanged(handler: (newState: MeshStateDto) -> Unit)
    fun setOnPeerCountChanged(handler: (newCount: Int) -> Unit)
    // fun setOnServiceBundleReceived(handler: (serviceId: String, bundle: ByteArray) -> Unit)
    // fun setOnServiceAnnounced(handler: (serviceId: String, announcement: ServiceAnnouncement) -> Unit)
    fun setOnGossipMessage(handler: (senderId: Int, messageBytes: ByteArray) -> Unit)
    
    /**
     * Section 9: Task status update callback
     * Invoked when a distributed compute task changes status.
     * 
     * @param handler Callback function receiving taskId and status string
     */
    fun setOnTaskStatusUpdate(handler: (taskId: String, status: String) -> Unit)

    /**
     * Returns whether the given TaskType is enabled for compute participation.
     */
    fun isTaskTypeEnabled(taskType: TaskTypeDto): Boolean

    /**
     * Sets enabled status for a TaskType (persistently).
     */
    fun setTaskTypeEnabled(taskType: TaskTypeDto, enabled: Boolean)

    /**
     * Returns a map of all TaskTypes and their enabled status.
     */
    fun getAllTaskTypeEnabled(): Map<TaskTypeDto, Boolean>
    // --- User Identity API ---
    /**
     * Returns current user info (userId, publicKey, nickname).
     */
    fun getUserInfo(): User

    /**
     * Sets the user's nickname (persistent).
     */
    fun setUserNickname(nickname: String)

    /**
     * Rotates the user's keypair and updates userId/publicKey.
     */
    fun rotateUserKey(): User

    // ========================================
    // WiFi Internet Connection API (WIFI_AP_CON)
    // ========================================

    /**
     * Connect to a non-mesh WiFi network while the mesh remains active.
     *
     * Requires AP+STA concurrency (hotspot mode, API 30+) or STA/STA concurrency
     * (Join Mesh mode, API 31+). Returns failure if hardware does not support the
     * required mode.
     *
     * @param ssid Target WiFi network SSID.
     * @param passphrase WPA2 passphrase. Pass empty string for open networks.
     * @return NonMeshWifiConnectionStateDto with status CONNECTED on success, FAILED on failure.
     */
    suspend fun connectToNonMeshWifi(ssid: String, passphrase: String): NonMeshWifiConnectionStateDto

    /**
     * Returns true if this device is capable of hosting a Wi‑Fi hotspot / AP.
     * This check is performed once during initialization; callers may also watch
     * the `state` flow for live updates.
     */
    fun isApCapable(): Boolean

    /**
     * Returns true if this device supports concurrent AP+Station mode
     * (hotspot running while simultaneously connected as a WiFi client).
     * Requires API 30+ and hardware support (isStaApConcurrencySupported).
     * Distinct from [isApCapable] — a device may be AP-capable but NOT support concurrent AP+STA.
     */
    fun isApStaConcurrentCapable(): Boolean

    /**
     * Returns true if this device supports simultaneous dual-STA mode
     * (connected to two WiFi networks at the same time as a client).
     * Requires API 31+ and hardware support (isStaConcurrencyForLocalOnlyConnectionsSupported).
     * Distinct from both [isApCapable] and [isApStaConcurrentCapable].
     */
    fun isStaStaConcurrentCapable(): Boolean

    /**
     * Disconnect from the non-mesh internet WiFi.
     * Removes the WifiNetworkSuggestion and releases the internet Network object.
     * @return true if disconnection was performed, false if no connection was active.
     */
    suspend fun disconnectFromNonMeshWifi(): Boolean

    /**
     * Starts a local-only hotspot using the passphrase stored from the most recent joinMesh() QR scan.
     * This allows nearby devices to join this node's AP and reach the mesh (AP extension mode).
     * Only works reliably on API 33+; on older devices the OS assigns a random passphrase.
     */
    fun startMeshExtenderHotspot(callback: (Result<Unit>) -> Unit)

    /**
     * Stops the mesh extender hotspot started via [startMeshExtenderHotspot].
     */
    fun stopMeshExtenderHotspot(callback: (Result<Unit>) -> Unit)

    /**
     * StateFlow emitting the current state of the mesh extender hotspot.
     */
    val meshExtenderHotspotStateFlow: StateFlow<MeshExtenderHotspotStateDto>

    /**
     * StateFlow emitting true when the local mesh AP (LocalOnlyHotspot or WifiDirect)
     * is fully started. This is the authoritative trigger for showing the QR/join pane.
     * Independent of mesh network status (CONNECTING/CONNECTED) — a joining station
     * should never emit true here.
     */
    val meshApActiveFlow: StateFlow<Boolean>

    /**
     * Observe the current non-mesh WiFi connection state.
     * Emits [NonMeshWifiConnectionStateDto] updates as connection state changes.
     */
    fun getNonMeshWifiStateFlow(): StateFlow<NonMeshWifiConnectionStateDto>

    /**
     * Scan for available WiFi networks.
     * Requires ACCESS_FINE_LOCATION permission.
     * @return List of discovered networks, ordered by signal strength descending.
     */
    suspend fun scanAvailableWifiNetworks(): List<NonMeshWifiNetworkDto>

    /**
     * Returns true when the internet WiFi connection feature is currently available.
     *
     * Two paths to true:
     *   1. AP+STA mode: hotspot is running AND isStaApConcurrencySupported = true (API 30+)
     *   2. STA/STA mode: in Join Mesh AND isStaStaConcurrencySupported = true (API 31+)
     *
     * Returns false when mesh is not initialized, API < 30, or neither capability is present.
     */
    fun isInternetWifiFeatureAvailable(): Boolean

    /**
     * Returns true if the Android WiFi radio is currently enabled (WifiManager.isWifiEnabled).
     * Works on all SDK versions — the getter is not deprecated.
     * Used as a pre-flight gate before the Join Mesh flow.
     * Returns false if the mesh node is not yet initialized.
     */
    fun isWifiEnabled(): Boolean

    // === MESH PROXY APPS (Phase 2) ===

    /**
     * Persist the set of package names whose traffic this node will proxy through its
     * internet connection on behalf of remote mesh peers.
     * Stored via DataStore using [MeshrabiyaConstants.KEY_MESH_PROXY_APP_PACKAGES].
     */
    suspend fun setMeshProxyApps(packageNames: Set<String>)

    /**
     * Return the currently persisted set of package names for mesh proxy.
     * Returns empty set if none configured.
     */
    suspend fun getMeshProxyApps(): Set<String>

    /**
     * Observe whether mesh proxy is currently active (i.e. the proxy VPN service is running
     * and at least one package is configured). Emits false when not active.
     */
    fun getMeshProxyActiveFlow(): StateFlow<Boolean>

    /**
     * Emits true when BOTH conditions hold simultaneously:
     *   1. The local device does NOT have direct internet (nonMeshHasInternet == false), AND
     *   2. At least one CLEARNET_GATEWAY node is reachable in the mesh topology.
     * Used by [MeshProxyController] to decide when to activate mesh-proxy VPN mode.
     */
    fun getMeshInternetGatewayAvailableFlow(): StateFlow<Boolean>

    /**
     * The loopback TCP port on which [MeshLocalSocksProxy] is currently listening.
     * Returns 0 if the proxy server has not been started via [startMeshProxyServer].
     */
    fun getMeshProxySocksPort(): Int

    /** Start the local SOCKS5 mesh-proxy server. Idempotent. */
    fun startMeshProxyServer()

    /** Stop the local SOCKS5 mesh-proxy server. Idempotent. */
    fun stopMeshProxyServer()
}




## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt
package com.ustadmobile.meshrabiya.api
// import com.ustadmobile.meshrabiya.model.toHash
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import java.io.File
import java.net.Socket
import java.net.InetSocketAddress
import java.io.DataInputStream
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import com.ustadmobile.meshrabiya.vnet.MeshFile
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import com.ustadmobile.meshrabiya.model.UserKeyManager
// UNUSED SCHEDULER IMPORTS - Commented 2025-11-12
// Scheduler infrastructure not used in Phase 3-4 ML-capable compute implementation
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ComputeTask
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ExecutionPlan
import com.ustadmobile.meshrabiya.service.compute.model.JobType
import com.ustadmobile.meshrabiya.service.compute.model.LocalComputeTaskRequest
// DEPRECATED: IntelligentDistributedComputeService replaced by canonical compute workflows (2025-12-04)
// import com.ustadmobile.meshrabiya.service.compute.IntelligentDistributedComputeService
import com.ustadmobile.meshrabiya.model.MeshState
import com.ustadmobile.meshrabiya.model.NetworkInfo
import com.ustadmobile.meshrabiya.model.NodeInfo
import com.ustadmobile.meshrabiya.model.ApiResult
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.vnet.MeshRole
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import com.ustadmobile.meshrabiya.service.ComputeTaskRequestMessage
import com.ustadmobile.meshrabiya.service.TorStatusMonitor
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.storage.FileReference
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.DropFolderItem
import com.ustadmobile.meshrabiya.storage.StoreFileTrigger
import com.ustadmobile.meshrabiya.storage.RecipientType
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.util.toHash
import com.ustadmobile.meshrabiya.storage.StorageDeviceType
import com.ustadmobile.meshrabiya.api.model.User
import com.ustadmobile.meshrabiya.api.model.*

import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.URL
import java.net.HttpURLConnection
import kotlinx.coroutines.withContext
import com.ustadmobile.meshrabiya.vnet.NodeTopologyInfo
import com.ustadmobile.meshrabiya.api.model.MeshRoleDto
import com.ustadmobile.meshrabiya.api.model.LocalNodeStateDto
import com.ustadmobile.meshrabiya.api.model.NonMeshWifiConnectionStateDto
import com.ustadmobile.meshrabiya.api.model.NonMeshWifiStatusDto

// import com.ustadmobile.meshrabiya.model.ServiceAnnouncement

/**
 * Production-ready implementation of MeshrabiyaApi.
 * Delegates all operations to internal Meshrabiya components.
 */
class MeshrabiyaApiImpl : MeshrabiyaApi {

    // Store the application context
    @Volatile
    private var appContext: Context? = null
    override fun provideAppContext(context: Context) {
        appContext = context.applicationContext
        // Initialize MeshrabiyaConstants with application context for preferences
        MeshrabiyaConstants.init(context.applicationContext)
    }

    override fun getAppContext(): Context? {
        return appContext
    }

    override fun isApCapable(): Boolean {
        // delegate entirely to the WifiManager flag – no logic here
        return myNode?.meshrabiyaWifiManager?.apCapable == true
    }

    override fun isApStaConcurrentCapable(): Boolean {
        return myNode?.meshrabiyaWifiManager?.concurrentApStationSupported == true
    }

    override fun isStaStaConcurrentCapable(): Boolean {
        return myNode?.meshrabiyaWifiManager?.staStaConcurrencySupported == true
    }

    companion object {
        private const val TAG = "MeshrabiyaApiImpl"
        
        @Volatile
        private var instance: MeshrabiyaApiImpl? = null

        fun getInstance(): MeshrabiyaApiImpl {
            return instance ?: synchronized(this) {
                instance ?: MeshrabiyaApiImpl().also { instance = it }
            }
        }
        
        /**
         * Timeout threshold for gateway staleness check.
         * Phase 3B: Used to filter stale gateways from statistics
         */
        private const val GATEWAY_STALE_TIMEOUT_MS = 30_000L  // 30 seconds
        /** Interval between periodic non-mesh internet connectivity probes. */
        private const val NONMESH_INTERNET_CHECK_INTERVAL_MS = 30_000L
        private const val MESH_INTERNET_CHECK_INTERVAL_MS = 30_000L
    }

    // Internal managers, initialized in initMesh
    private var myNode: AndroidVirtualNode? = null
    private var emergentRoleManager: EmergentRoleManager? = null
    
    // StateFlow for network info - updated every 2 seconds
    private val _networkInfoFlow = MutableStateFlow<NetworkInfoDto?>(null)
    val networkInfoFlow: StateFlow<NetworkInfoDto?> = _networkInfoFlow.asStateFlow()
    private val _wifiStateFlow = MutableStateFlow<MeshrabiyaWifiStateDto?>(null)
    val wifiStateFlow: StateFlow<MeshrabiyaWifiStateDto?> = _wifiStateFlow.asStateFlow()
    // StateFlow for mesh status
    private val _meshStatusFlow = MutableStateFlow(getMeshStatus())
    override val meshStatusFlow: StateFlow<MeshStateDto> get() = _meshStatusFlow

    // --- Network Overview Metrics StateFlow ---
    private val _networkOverviewMetricsFlow = MutableStateFlow(NetworkOverviewMetricsDto(0L, 0L, 0))
    override val networkOverviewMetricsFlow: StateFlow<NetworkOverviewMetricsDto> = _networkOverviewMetricsFlow.asStateFlow()

    // Non-mesh WiFi connection state Flow — updated by connectToNonMeshWifi/disconnectFromNonMeshWifi
    private val _nonMeshWifiState = MutableStateFlow(NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.IDLE))

    private val _meshExtenderHotspotState = MutableStateFlow(MeshExtenderHotspotStateDto.INACTIVE)
    override val meshExtenderHotspotStateFlow: StateFlow<MeshExtenderHotspotStateDto> = _meshExtenderHotspotState.asStateFlow()

    private val _meshApActiveFlow = MutableStateFlow(false)
    override val meshApActiveFlow: StateFlow<Boolean> = _meshApActiveFlow.asStateFlow()

    // Stable, always-non-null roles flow. Fragment's setupRoleObserver() always has
    // a flow to collect from — even before initMesh() is called. Populated by
    // startEventMonitoring() once emergentRoleManager is available.
    private val _currentMeshRolesFlow = MutableStateFlow<Set<MeshRoleDto>>(emptySet())
    val currentMeshRolesFlow: StateFlow<Set<MeshRoleDto>> = _currentMeshRolesFlow.asStateFlow()

    @Volatile
    private var lastJoinedMeshPassphrase: String? = null

    private var metricsMonitorJob: Job? = null

    // Tracks the last time each direct peer (hopCount==1) was seen in an
    // originatorMessages update. Used to detect stale peers whose routing-table
    // entry hasn't been evicted yet even though the STA is physically gone.
    private val peerLastSeen = mutableMapOf<Int, Long>()
    private val PEER_STALE_THRESHOLD_MS = 20_000L   // drop a peer after 20 s of silence
    private val PEER_LIVENESS_CHECK_MS  =  5_000L   // re-evaluate every 5 s

    private var distributedStorageManager: DistributedStorageManager? = null
    // distributedComputeClient accessed via myNode?.distributedComputeClient (protected property)
    // DEPRECATED: intelligentDistributedComputeService removed (2025-12-04)
    // Compute workflows now use DistributedComputeServer + TaskManager directly
    // private var intelligentDistributedComputeService: IntelligentDistributedComputeService? = null
    
    /**
     * Handler for broadcast messages+files
     * Initialized when mesh starts, cleaned up when mesh stops
     * Added: 2026-02-01 for NETWORK_BROADCAST_v2 implementation
     */
    private var broadcastHandler: com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastMessageHandler? = null

    /**
     * Queue for listeners registered before broadcastHandler is created
     * Applied automatically when handler is initialized during joinMesh()
     * Added: 2026-02-15 for deferred listener registration
     */
    private val pendingBroadcastListeners =
        java.util.concurrent.CopyOnWriteArrayList<(com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit>()

    // Section 6: Event monitoring scope and jobs
    private val eventMonitoringScope = CoroutineScope(Dispatchers.Default)
    private var stateMonitorJob: Job? = null
    private var peerMonitorJob: Job? = null
    // Confirmed internet access on non-mesh WiFi; persists across transient VALIDATED dropouts
    private val _nonMeshInternetConfirmed = MutableStateFlow(false)
    private var nonMeshInternetCheckJob: Job? = null
    // Confirmed internet access via a remote CLEARNET_GATEWAY (mesh-side probe). Set by periodic checkInternetViaMeshGateway().
    private val _meshInternetViaGatewayConfirmed = MutableStateFlow(false)
    private var meshInternetCheckJob: Job? = null

    // V3: Gateway preference state
    @Volatile
    private var currentGatewayPreference: GatewayPreference = GatewayPreference.DEFAULT
    @Volatile
    private var isTorRunning: Boolean = false
    private val torStatusMonitor = TorStatusMonitor()

     // --- Proxy Controls ---
    override fun setProxy(host: String, port: Int) {
        myNode?.setProxy(host, port)
    }

    override fun setProxyActive(active: Boolean) {
        myNode?.setProxyActive(active)
    }

    // --- Mesh Initialization ---
    private val Context.dataStore by preferencesDataStore(name = "meshr_settings")
    
    override fun initMesh(context: Context) {
        // Guard against double initialization
        if (myNode != null) {
            Log.w("MeshInit", "initMesh called but mesh already initialized, skipping")
            return
        }
        
        Log.d("MeshInit", "initMesh called with context: $context")
        try {
            val dataStore = context.dataStore
             Log.d("MeshInit", "dataStore resolved: $dataStore")

            myNode = AndroidVirtualNode(
                appContext = context.applicationContext,
                dataStore = dataStore
            )
            Log.d("MeshInit", "AndroidVirtualNode created: $myNode")

            emergentRoleManager = myNode?.emergentRoleManager
            Log.d("MeshInit", "emergentRoleManager assigned: $emergentRoleManager")

            distributedStorageManager = myNode?.distributedStorageManager
            // distributedComputeClient accessed via myNode.getDistributedComputeClient() when needed
            // DEPRECATED: IntelligentDistributedComputeService removed (2025-12-04)
            // intelligentDistributedComputeService = myNode?.getIntelligentDistributedComputeService()
            
            // V3: Load gateway preference from storage
            runBlocking {
                loadGatewayPreference(context)
            }
            
            // V3: Register Tor status monitor
            torStatusMonitor.register(context)
            torStatusMonitor.requestStatusUpdate(context)  // Get initial status
            
            // Section 6: Start monitoring for state and peer count changes
            startEventMonitoring()
        } catch (e: Exception) {
            Log.e("MeshInit", "Exception during initMesh", e)
            throw e
        }
    }
    
    /**
     * Section 6: Start coroutines to monitor mesh state and peer count changes
     * Invokes registered callbacks when changes are detected
     */
    private fun startEventMonitoring() {
        val node = checkNotNull(myNode) { "startEventMonitoring called before myNode was set" }

        // Reactively derive peer count from neighbor list — no polling
        peerMonitorJob = eventMonitoringScope.launch {
            node.state
                .map { localState ->
                    localState.originatorMessages.count { it.value.hopCount == 1.toByte() }
                }
                .distinctUntilChanged()
                .collect { currentCount ->
                    onPeerCountChanged?.invoke(currentCount)
                    // Keep meshStatusFlow in sync with peer count transitions
                    if (currentCount > 0 && _meshStatusFlow.value == MeshStateDto.CONNECTING) {
                        _meshStatusFlow.value = MeshStateDto.CONNECTED
                    } else if (currentCount == 0 && _meshStatusFlow.value == MeshStateDto.CONNECTED) {
                        _meshStatusFlow.value = MeshStateDto.CONNECTING
                    }
                }
        }

        // Reactively derive NetworkInfoDto from topology + wifi + non-mesh state — no polling
        // _nonMeshInternetConfirmed is the 5th input: persists green dot through transient VALIDATED dropouts
        eventMonitoringScope.launch {
            combine(
                node.state.map { it.toDto() },
                node.originatingMessageManager.topologyMapFlow,
                _nonMeshWifiState,
                node.meshrabiyaWifiManager.internetWifiNetworkStateFlow.map { it.toDto() },
                combine(_nonMeshInternetConfirmed, _currentMeshRolesFlow) { confirmed: Boolean, roles: Set<MeshRoleDto> -> Pair(confirmed, roles) },
                _meshInternetViaGatewayConfirmed
            ) { args: Array<Any?> ->
                val localState = args[0] as LocalNodeStateDto
                val topology = args[1] as Map<Int, NodeTopologyInfo>
                val nonMeshWifi = args[2] as NonMeshWifiConnectionStateDto
                val internetWifiState = args[3] as NonMeshWifiConnectionStateDto
                val confirmedAndRoles = args[4] as Pair<Boolean, Set<MeshRoleDto>>
                val meshViaGatewayConfirmed = args[5] as Boolean

                val (internetConfirmed, localRoles) = confirmedAndRoles
                val neighborCount = localState.originatorMessages.count { it.value.hopCount == 1.toByte() }
                val remoteTorGateways = topology.values.count { nodeInfo ->
                    nodeInfo.hasRole(MeshRole.TOR_GATEWAY) && !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
                }
                val remoteClearnetGateways = topology.values.count { nodeInfo ->
                    nodeInfo.hasRole(MeshRole.CLEARNET_GATEWAY) && !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
                }
                val torGateways = remoteTorGateways + (if (MeshRoleDto.TOR_GATEWAY in localRoles) 1 else 0)
                val clearnetGateways = remoteClearnetGateways + (if (MeshRoleDto.CLEARNET_GATEWAY in localRoles) 1 else 0)
                val nonMeshSsid = nonMeshWifi.connectedSsid
                val nonMeshHasInternet = (internetWifiState.hasInternetAccess || internetConfirmed)
                    .takeIf { nonMeshWifi.status == NonMeshWifiStatusDto.CONNECTED }
                val hasRemoteClearnetGateway = remoteClearnetGateways > 0
                val isLocalClearnetGateway = MeshRoleDto.CLEARNET_GATEWAY in localRoles
                val localHasInternet = nonMeshHasInternet == true
                // meshInternetGatewayAvailable is driven solely by _meshInternetViaGatewayConfirmed
                // for all gateway cases. This decouples the mesh green dot from the non-mesh HTTP
                // probe result, preventing the mesh dot from disappearing when the VPN causes
                // the non-mesh probe to fail transiently.
                val meshInternetGatewayAvailable = meshViaGatewayConfirmed
                Pair(
                    NetworkInfoDto(
                        bssid = "",
                        ssid = "",
                        ipAddress = localState.address.addressToDotNotation(),
                        isConnected = true,
                        connectedPeers = neighborCount,
                        torGateways = torGateways,
                        clearnetGateways = clearnetGateways,
                        nonMeshSsid = nonMeshSsid,
                        nonMeshIpAddress = internetWifiState.internetConnectionIpAddress,
                        nonMeshHasInternet = nonMeshHasInternet
                    ),
                    meshInternetGatewayAvailable
                )
            }
            .distinctUntilChanged()
            .collect { (dto, meshInternetGatewayAvailable) ->
                _networkInfoFlow.value = dto
                _meshInternetGatewayAvailableFlow.value = meshInternetGatewayAvailable
            }
        }

        // Reactively expose wifiState for chip UI — no polling
        eventMonitoringScope.launch {
            node.meshrabiyaWifiManager.state
                .map { it.toDto() }
                .distinctUntilChanged()
                .collect { _wifiStateFlow.value = it }
        }

        // Reactively track local mesh AP hardware state.
        // This is the single source of truth for whether THIS device is hosting
        // a mesh AP (LocalOnlyHotspot or WifiDirect group is STARTED).
        // Also drives meshStatusFlow: AP up → CONNECTING, AP down → DISCONNECTED.
        // Peer count transitions between CONNECTING ↔ CONNECTED are handled above.
        eventMonitoringScope.launch {
            node.meshrabiyaWifiManager.state
                .map { wifiState ->
                    val apActive = wifiState.hotspotIsStarted
                    val staActive = wifiState.wifiStationState.status == WifiStationState.Status.AVAILABLE
                    Pair(apActive, staActive)
                }
                .distinctUntilChanged()
                .collect { (apActive, staActive) ->
                    _meshApActiveFlow.value = apActive
                    val hasPhysicalLink = apActive || staActive
                    if (hasPhysicalLink && _meshStatusFlow.value == MeshStateDto.DISCONNECTED) {
                        _meshStatusFlow.value = MeshStateDto.CONNECTING
                    } else if (!hasPhysicalLink &&
                        (_meshStatusFlow.value == MeshStateDto.CONNECTING ||
                         _meshStatusFlow.value == MeshStateDto.CONNECTED)) {
                        _meshStatusFlow.value = MeshStateDto.DISCONNECTED
                    }
                }
        }

        // Forward EmergentRoleManager roles → stable _currentMeshRolesFlow so
        // the Fragment's setupRoleObserver() always has a live, non-null collector.
        emergentRoleManager?.let { rm ->
            eventMonitoringScope.launch {
                rm.currentMeshRoles
                    .map { roles -> roles.map { it.toDto() }.toSet() }
                    .distinctUntilChanged()
                    .collect { _currentMeshRolesFlow.value = it }
            }
        }

        // --- Network Overview Metrics Polling ---
        metricsMonitorJob?.cancel()
        metricsMonitorJob = eventMonitoringScope.launch {
            var lastUploadBytes = myNode?.currentNodeState?.uploadBytes ?: 0L
            var lastDownloadBytes = myNode?.currentNodeState?.downloadBytes ?: 0L
            while (true) {
                delay(1000)
                val node = myNode
                if (node != null) {
                    val state = node.currentNodeState
                    val uploadNow = state.uploadBytes
                    val downloadNow = state.downloadBytes
                    val uploadRate = uploadNow - lastUploadBytes
                    val downloadRate = downloadNow - lastDownloadBytes
                    lastUploadBytes = uploadNow
                    lastDownloadBytes = downloadNow
                    val activeNodeCount = node.neighbors().size + 1 // +1 for self
                    _networkOverviewMetricsFlow.value = NetworkOverviewMetricsDto(
                        uploadBps = uploadRate,
                        downloadBps = downloadRate,
                        activeNodeCount = activeNodeCount
                    )
                } else {
                    _networkOverviewMetricsFlow.value = NetworkOverviewMetricsDto(0L, 0L, 0)
                }
            }
        }

        // Peer liveness watchdog: evicts stale originatorMessage entries that the
        // routing layer has not yet removed after a STA disconnect, then re-derives
        // meshStatusFlow from the surviving live-peer count + physical link state.
        eventMonitoringScope.launch {
            while (true) {
                delay(PEER_LIVENESS_CHECK_MS)
                val now = System.currentTimeMillis()

                // Refresh timestamps from current routing table
                val currentMessages = node.state.first().originatorMessages
                currentMessages.entries
                    .filter { entry -> entry.value.hopCount == 1.toByte() }
                    .forEach { (addr, _) -> peerLastSeen[addr] = now }

                // Evict peers not seen in the last PEER_STALE_THRESHOLD_MS
                val staleBefore = now - PEER_STALE_THRESHOLD_MS
                val evicted = peerLastSeen.entries.removeAll { entry -> entry.value < staleBefore }

                val liveCount = peerLastSeen.size
                val apActive = _meshApActiveFlow.value
                val staActive = _wifiStateFlow.value
                    ?.wifiStationState?.status == WifiStationState.Status.AVAILABLE.name
                val hasPhysicalLink = apActive || staActive

                val derived = when {
                    !hasPhysicalLink -> MeshStateDto.DISCONNECTED
                    liveCount > 0 -> MeshStateDto.CONNECTED
                    else -> MeshStateDto.CONNECTING
                }
                if (_meshStatusFlow.value != derived) {
                    Log.d(TAG, "[LIVENESS] liveCount=$liveCount evicted=$evicted → $derived")
                    _meshStatusFlow.value = derived
                }
            }
        }

        // Immediately confirm internet when OS validates (for fast initial green dot appearance)
        eventMonitoringScope.launch {
            node.meshrabiyaWifiManager.internetWifiNetworkStateFlow.collect { state ->
                if (state.hasInternetAccess) {
                    _nonMeshInternetConfirmed.value = true
                }
            }
        }

        // Periodic active internet probe: keeps green dot alive through transient VALIDATED dropouts
        // (e.g., caused by VPN activation). Cancels and resets on disconnect.
        eventMonitoringScope.launch {
            _nonMeshWifiState.collect { nonMeshState ->
                nonMeshInternetCheckJob?.cancel()
                nonMeshInternetCheckJob = null
                if (nonMeshState.status == NonMeshWifiStatusDto.CONNECTED) {
                    nonMeshInternetCheckJob = launch {
                        while (true) {
                            delay(NONMESH_INTERNET_CHECK_INTERVAL_MS)
                            val confirmed = checkNonMeshInternetAccess(node)
                            if (confirmed) {
                                _nonMeshInternetConfirmed.value = true
                            }
                        }
                    }
                } else {
                    _nonMeshInternetConfirmed.value = false
                }
            }
        }

        // Mesh gateway internet check — runs on ANY mesh-connected node (AP or STA)
        // This is intentionally a SEPARATE top-level launch, not nested inside the
        // nonMeshWifiState collector. A pure STA node (Phone 2) must reach this path
        // even when it has no upstream WiFi of its own.
        eventMonitoringScope.launch {
            combine(
                node.originatingMessageManager.topologyMapFlow,
                _currentMeshRolesFlow,
                _nonMeshWifiState
            ) { topology, localRoles, nonMeshState ->
                val hasRemoteGateway = topology.values.any { nodeInfo ->
                    (nodeInfo.hasRole(MeshRole.CLEARNET_GATEWAY) ||
                    nodeInfo.hasRole(MeshRole.TOR_GATEWAY)) &&
                    !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
                }
                val isLocalGateway = MeshRoleDto.CLEARNET_GATEWAY in localRoles ||
                                    MeshRoleDto.TOR_GATEWAY in localRoles
                val nonMeshConnected = nonMeshState.status == NonMeshWifiStatusDto.CONNECTED
                val apActive = _meshApActiveFlow.value
                val shouldCheck = hasRemoteGateway ||
                                (apActive && isLocalGateway && nonMeshConnected)
                shouldCheck
            }
            .distinctUntilChanged()
            .collect { shouldCheck ->
                meshInternetCheckJob?.cancel()
                meshInternetCheckJob = null
                if (shouldCheck) {
                    val capturedNode = node
                    meshInternetCheckJob = launch {
                        while (true) {
                            delay(MESH_INTERNET_CHECK_INTERVAL_MS)
                            val currentLocalRoles = _currentMeshRolesFlow.value
                            val currentIsLocalGateway =
                                MeshRoleDto.CLEARNET_GATEWAY in currentLocalRoles ||
                                MeshRoleDto.TOR_GATEWAY in currentLocalRoles
                            val currentHasRemote = capturedNode.originatingMessageManager
                                .getTopologyMapInfo().values.any { nodeInfo ->
                                    (nodeInfo.hasRole(MeshRole.CLEARNET_GATEWAY) ||
                                    nodeInfo.hasRole(MeshRole.TOR_GATEWAY)) &&
                                    !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
                                }
                            val ok = if (currentIsLocalGateway && !currentHasRemote) {
                                checkNonMeshInternetAccess(capturedNode)
                            } else {
                                checkInternetViaMeshGateway()
                            }
                            _meshInternetViaGatewayConfirmed.value = ok
                        }
                    }
                } else {
                    _meshInternetViaGatewayConfirmed.value = false
                }
            }
        }
    }
    
    /**
     * Section 6: Stop event monitoring (for cleanup)
     */
    private fun stopEventMonitoring() {
        stateMonitorJob?.cancel()
        peerMonitorJob?.cancel()
        stateMonitorJob = null
        peerMonitorJob = null
    }

    /**
     * Probe internet access on the non-mesh WiFi network.
     * Sends an HTTP HEAD to Google's generate_204 endpoint via the bound [internetWifiNetwork],
     * bypassing any active VPN. Falls back to ConnectivityManager VALIDATED check on failure.
     */
    private suspend fun checkNonMeshInternetAccess(node: AndroidVirtualNode): Boolean =
        withContext(Dispatchers.IO) {
            val network = node.meshrabiyaWifiManager.internetWifiNetwork ?: return@withContext false
            try {
                val url = URL("http://connectivitycheck.gstatic.com/generate_204")
                val conn = network.openConnection(url) as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.requestMethod = "HEAD"
                conn.connect()
                val code = conn.responseCode
                conn.disconnect()
                code == 204 || code == 200
            } catch (e: Exception) {
                Log.d(TAG, "[NONMESH] internet probe failed (${e.javaClass.simpleName}), trying VALIDATED")
                val ctx = appContext ?: return@withContext false
                val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return@withContext false
                cm.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
        }

    private suspend fun checkInternetViaMeshGateway(): Boolean =
    withContext(Dispatchers.IO) {
        val node = myNode ?: return@withContext false
        startMeshProxyServer()
        val port = getMeshProxySocksPort()
        if (port <= 0) {
            Log.d(TAG, "[MESH_PROBE] Mesh proxy port not ready")
            return@withContext false
        }
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.soTimeout = 10_000
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            val din = DataInputStream(inp)
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val auth = ByteArray(2)
            din.readFully(auth)
            if (auth[0] != 0x05.toByte() || auth[1] != 0x00.toByte()) {
                Log.d(TAG, "[MESH_PROBE] SOCKS5 auth failed")
                return@withContext false
            }
            val host = "connectivitycheck.gstatic.com"
            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            val req = ByteArray(7 + hostBytes.size)
            req[0] = 0x05
            req[1] = 0x01
            req[2] = 0x00
            req[3] = 0x03
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = 0x00
            req[6 + hostBytes.size] = 0x50
            out.write(req)
            out.flush()
            val rep = ByteArray(4)
            din.readFully(rep)
            if (rep[0] != 0x05.toByte() || rep[1] != 0x00.toByte()) {
                Log.d(TAG, "[MESH_PROBE] SOCKS5 CONNECT failed: ${rep[1]}")
                return@withContext false
            }
            val atyp = rep[3].toInt() and 0xFF
            when (atyp) {
                0x01 -> din.readFully(ByteArray(6))
                0x03 -> { val len = din.read(); din.readFully(ByteArray(len)); din.readFully(ByteArray(2)) }
                0x04 -> din.readFully(ByteArray(18))
            }
            val request = "HEAD /generate_204 HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
            out.write(request.toByteArray(Charsets.US_ASCII))
            out.flush()
            val buf = ByteArray(512)
            val n = inp.read(buf)
            if (n <= 0) {
                Log.d(TAG, "[MESH_PROBE] No HTTP response")
                return@withContext false
            }
            val line = String(buf, 0, n, Charsets.US_ASCII)
            val code = when {
                line.startsWith("HTTP/1.0 204") || line.startsWith("HTTP/1.1 204") -> 204
                line.startsWith("HTTP/1.0 200") || line.startsWith("HTTP/1.1 200") -> 200
                else -> null
            }
            if (code != null) return@withContext true
            val space = line.indexOf(' ', 9)
            if (space > 0) {
                val codeStr = line.substring(space + 1, minOf(space + 4, line.length)).trim()
                val c = codeStr.toIntOrNull()
                if (c == 204 || c == 200) return@withContext true
            }
            false
        } catch (e: Exception) {
            Log.d(TAG, "[MESH_PROBE] Probe failed: ${e.javaClass.simpleName} ${e.message}")
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    // --- Mesh State & Network Info ---
    // override fun getNodeRole(): Byte = emergentRoleManager?.getCurrentMeshRoles()?.firstOrNull()?.ordinal?.toByte() ?: 0

    override fun getNodeRoleNames(): List<String> =
        emergentRoleManager?.getCurrentMeshRoles()?.map { it.name } ?: emptyList()
    

    override fun getFitnessScore(): Float = emergentRoleManager?.getFitnessScore() ?: 0f
    
    override fun getConnectionUri(): String = myNode?.currentNodeState?.connectUri ?: ""
    override fun getLocalNodeState(): LocalNodeStateDto = myNode?.currentNodeState?.toDto() ?: throw IllegalStateException("Mesh not initialized")
    override fun getNeighbors(): List<NeighborInfoDto> = myNode?.neighbors()?.map { NeighborInfoDto(it.first, it.second.toDto()) } ?: emptyList()
    override fun getHopCountToNode(nodeId: Int): Int? = myNode?.originatingMessageManager?.findOriginatingMessageFor(nodeId)?.hopCount?.toInt()

    override fun getConnectLink(): String? = myNode?.currentNodeState?.connectUri
    override fun getConnectLinkFlow(): Flow<String?> = myNode?.state?.map { it.connectUri } ?: flowOf(null)

    // --- Mesh Network Controls ---
    override fun startMesh(callback: (Result<Unit>) -> Unit) {
        Log.e("MeshrabiyaApiImpl", "========== startMesh() CALLED ==========")
        Log.e("MeshrabiyaApiImpl", "This log MUST appear if startMesh is invoked")
        Log.d("MeshrabiyaApiImpl", "myNode is null: ${myNode == null}")
        
        if (myNode == null) {
            Log.e("MeshrabiyaApiImpl", "startMesh called but myNode is null - mesh not initialized!")
            callback(Result.failure(IllegalStateException("Mesh not initialized - call initMesh() first")))
            return
        }
        
        Log.d("MeshrabiyaApiImpl", "Launching coroutine for startMesh")
        eventMonitoringScope.launch {
            try {
                Log.d("MeshrabiyaApiImpl", "Coroutine started, calling setWifiHotspotEnabled(enabled=true)")
                myNode?.setWifiHotspotEnabled(
                    enabled = true,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.AUTO
                )
                Log.d("MeshrabiyaApiImpl", "setWifiHotspotEnabled returned successfully")
                
                // Load persisted role preferences and apply them to EmergentRoleManager
                loadAndApplyPersistedRolePreferences()
                
                // Initialize broadcast handler (NETWORK_BROADCAST_v2 implementation)
                                    val node = myNode
                    if (node != null && broadcastHandler == null) {
                        broadcastHandler = com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastMessageHandler(
                            virtualNode = node,
                            logger = { priority, message -> node.logger(priority, message) },
                            cacheDir = appContext?.cacheDir ?: throw IllegalStateException("Context required for broadcast handler"),
                            getDropFolderCallback = { getDropFolderAsDocumentFile() }
                        )
                        // Wire handler to VirtualNode
                        node.broadcastMessageHandler = broadcastHandler
                        Log.d("MeshrabiyaApiImpl", "Broadcast handler initialized and wired to VirtualNode")
                    
                    // Apply any listeners registered before handler was created
                    applyPendingBroadcastListeners()
                }
                
                callback(Result.success(Unit))
                Log.d("MeshrabiyaApiImpl", "startMesh callback invoked with success")
            } catch (e: Exception) {
                Log.e("MeshrabiyaApiImpl", "startMesh failed with exception", e)
                callback(Result.failure(e))
                Log.d("MeshrabiyaApiImpl", "startMesh callback invoked with failure")
            }
        }
        Log.d("MeshrabiyaApiImpl", "startMesh() returning (coroutine launched)")
    }

    override fun stopMesh(callback: (Result<Unit>) -> Unit) {
        Log.d("MeshrabiyaApiImpl", "stopMesh() called")
        Log.d("MeshrabiyaApiImpl", "myNode is null: ${myNode == null}")
        
        if (myNode == null) {
            Log.e("MeshrabiyaApiImpl", "stopMesh called but myNode is null - mesh not initialized!")
            callback(Result.failure(IllegalStateException("Mesh not initialized - call initMesh() first")))
            return
        }
        
        Log.d("MeshrabiyaApiImpl", "Launching coroutine for stopMesh")
        eventMonitoringScope.launch {
            try {
                Log.d("MeshrabiyaApiImpl", "Coroutine started, calling setWifiHotspotEnabled(enabled=false)")
                myNode?.setWifiHotspotEnabled(
                    enabled = false,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.AUTO
                )
                Log.d("MeshrabiyaApiImpl", "setWifiHotspotEnabled returned successfully")
                
                // Cleanup broadcast handler (NETWORK_BROADCAST_v2 implementation)
                
                broadcastHandler = null
                Log.d("MeshrabiyaApiImpl", "Broadcast handler shutdown and cleaned up")
                
                callback(Result.success(Unit))
                Log.d("MeshrabiyaApiImpl", "stopMesh callback invoked with success")
            } catch (e: Exception) {
                Log.e("MeshrabiyaApiImpl", "stopMesh failed with exception", e)
                callback(Result.failure(e))
                Log.d("MeshrabiyaApiImpl", "stopMesh callback invoked with failure")
            }
        }
        Log.d("MeshrabiyaApiImpl", "stopMesh() returning (coroutine launched)")
    }

    override fun startMeshExtenderHotspot(callback: (Result<Unit>) -> Unit) {
        val pw = lastJoinedMeshPassphrase
        if (pw == null) {
            Log.w(TAG, "[EXTENDER] Cannot start mesh extender hotspot: no passphrase stored from joinMesh()")
            callback(Result.failure(IllegalStateException("No passphrase available — scan a mesh QR code first")))
            return
        }
        _meshExtenderHotspotState.value = MeshExtenderHotspotStateDto.STARTING
        eventMonitoringScope.launch {
            try {
                myNode?.setWifiHotspotEnabled(
                    enabled = true,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.AUTO,
                    preferredPassphrase = pw
                )
                _meshExtenderHotspotState.value = MeshExtenderHotspotStateDto.ACTIVE
                Log.i(TAG, "[EXTENDER] Mesh extender hotspot started with stored passphrase")
                callback(Result.success(Unit))
            } catch (e: Exception) {
                _meshExtenderHotspotState.value = MeshExtenderHotspotStateDto.INACTIVE
                Log.e(TAG, "[EXTENDER] Failed to start mesh extender hotspot", e)
                callback(Result.failure(e))
            }
        }
    }

    override fun stopMeshExtenderHotspot(callback: (Result<Unit>) -> Unit) {
        _meshExtenderHotspotState.value = MeshExtenderHotspotStateDto.STOPPING
        eventMonitoringScope.launch {
            try {
                myNode?.setWifiHotspotEnabled(
                    enabled = false,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.AUTO
                )
                _meshExtenderHotspotState.value = MeshExtenderHotspotStateDto.INACTIVE
                Log.i(TAG, "[EXTENDER] Mesh extender hotspot stopped")
                callback(Result.success(Unit))
            } catch (e: Exception) {
                _meshExtenderHotspotState.value = MeshExtenderHotspotStateDto.INACTIVE
                Log.e(TAG, "[EXTENDER] Error stopping mesh extender hotspot", e)
                callback(Result.failure(e))
            }
        }
    }

    override fun getMeshStatus(): MeshStateDto {
        Log.d("MeshrabiyaApiImpl", "getMeshStatus() called - myNode is null: ${myNode == null}")
        val node = myNode ?: run {
            Log.d("MeshrabiyaApiImpl", "getMeshStatus() returning DISCONNECTED (myNode is null)")
            return MeshStateDto.DISCONNECTED
        }
        
        // Check if mesh networking is actually active by checking WiFi state
        val wifiState = runBlocking {
            node.meshrabiyaWifiManager.state.first()
        }
        
        val hasActiveHotspot = wifiState.hotspotIsStarted
        val hasActiveStation = wifiState.wifiStationState.status == com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState.Status.AVAILABLE
        val isMeshNetworkActive = hasActiveHotspot || hasActiveStation
        
        Log.d("MeshrabiyaApiImpl", "getMeshStatus() - wifiRole=${wifiState.wifiRole}, hotspot=${hasActiveHotspot}, station=${hasActiveStation}, active=${isMeshNetworkActive}")
        
        if (!isMeshNetworkActive) {
            Log.d("MeshrabiyaApiImpl", "getMeshStatus() returning DISCONNECTED (no active network)")
            return MeshStateDto.DISCONNECTED
        }
        
        // Mesh network is active - determine state based on neighbors
        val neighborCount = node.neighbors().size
        Log.d("MeshrabiyaApiImpl", "getMeshStatus() - neighborCount=$neighborCount")
        
        val status = when {
            neighborCount > 0 -> MeshStateDto.CONNECTED     // Has neighbors = connected to mesh
            neighborCount == 0 -> MeshStateDto.CONNECTING   // No neighbors yet but network active
            else -> MeshStateDto.UNKNOWN
        }
        
        Log.d("MeshrabiyaApiImpl", "getMeshStatus() returning: $status")
        return status
    }
    override fun getPeerCount(): Int = myNode?.neighbors()?.size ?: 0 // myNode?.getPeerCount() ?: 0

    override fun refreshMeshStatus() {
        // On screen resume: evict stale peers first, then recompute.
        // This ensures the UI doesn't show a stale CONNECTED on unlock.
        val now = System.currentTimeMillis()
        val staleBefore = now - PEER_STALE_THRESHOLD_MS
        peerLastSeen.entries.removeAll { (_, lastSeen) -> lastSeen < staleBefore }

        val liveCount = peerLastSeen.size
        val apActive = _meshApActiveFlow.value
        val staActive = _wifiStateFlow.value
            ?.wifiStationState?.status == com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState.Status.AVAILABLE.name
        val hasPhysicalLink = apActive || staActive

        _meshStatusFlow.value = when {
            !hasPhysicalLink -> MeshStateDto.DISCONNECTED
            liveCount > 0    -> MeshStateDto.CONNECTED
            else             -> MeshStateDto.CONNECTING
        }
        Log.d(TAG, "[REFRESH] apActive=$apActive staActive=$staActive liveCount=$liveCount → ${_meshStatusFlow.value}")
    }
    
    /**
     * Phase 3B: Enhanced getNetworkInfo() with gateway statistics
     * Returns mesh network information including Tor and clearnet gateway counts
     */
    override fun getNetworkInfo(): NetworkInfoDto? {
        val node = myNode
        if (node == null) {
            Log.d("MeshrabiyaApiImpl", "getNetworkInfo() - myNode is null, returning null")
            return null // Mesh not initialized
        }
        
        val topology = node.originatingMessageManager.getTopologyMapInfo()
        val connectedNeighbors = node.neighbors().size
        
        Log.d("MeshrabiyaApiImpl", "getNetworkInfo() - connectedNeighbors=$connectedNeighbors, topologySize=${topology.size}")
        
        // Phase 3B: Count gateways by type
        val localRoles = emergentRoleManager?.currentMeshRoles?.value ?: emptySet()
        val remoteTorGateways = topology.values.count { nodeInfo ->
            nodeInfo.hasRole(MeshRole.TOR_GATEWAY) &&
            !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
        }
        val torGateways = remoteTorGateways + (if (MeshRole.TOR_GATEWAY in localRoles) 1 else 0)
        
        val remoteClearnetGateways = topology.values.count { nodeInfo ->
            nodeInfo.hasRole(MeshRole.CLEARNET_GATEWAY) &&
            !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
        }
        val clearnetGateways = remoteClearnetGateways + (if (MeshRole.CLEARNET_GATEWAY in localRoles) 1 else 0)
        
        Log.d("MeshrabiyaApiImpl", "getNetworkInfo() - returning NetworkInfoDto with connectedPeers=$connectedNeighbors")

        // if the node has a non-mesh connection, include it
        val nonMeshState = _nonMeshWifiState.value
        val nonMeshSsid = nonMeshState.connectedSsid
        // IP address comes from the WifiManager's internetWifiNetworkStateFlow so that
        // MeshrabiyaApiImpl contains no networking logic of its own.
        val nonMeshIp = node.meshrabiyaWifiManager.internetWifiNetworkStateFlow.value.ipAddress
        val nonMeshHasInternet = nonMeshState.hasInternetAccess
            .takeIf { nonMeshState.status == NonMeshWifiStatusDto.CONNECTED }

        if (nonMeshSsid != null) {
            Log.d(TAG, "[NETWORKINFO] non-mesh connected ssid=$nonMeshSsid ip=$nonMeshIp internet=$nonMeshHasInternet")
        }

        return NetworkInfoDto(
            bssid = "",
            ssid = "",
            ipAddress = node.addressAsInt.addressToDotNotation(),
            isConnected = true,
            connectedPeers = connectedNeighbors,
            torGateways = torGateways,
            clearnetGateways = clearnetGateways,
            nonMeshSsid = nonMeshSsid,
            nonMeshIpAddress = nonMeshIp,
            nonMeshHasInternet = nonMeshHasInternet
        )
    }
    override fun getNodeId(): Int {
        val node = myNode ?: return 0
        return node.addressAsInt
    }

    override fun getNodeInfo(nodeId: String): NodeInfoDto? {
        val node = myNode ?: return null
        
        // Get topology information for the requested node
        val topology = node.originatingMessageManager.getTopologyMapInfo()
        
        // Try to parse nodeId as Int, return empty if invalid
        val nodeAddress = nodeId.toIntOrNull() ?: return null
        val nodeData = topology[nodeAddress] ?: return null
        
        // Extract capabilities from meshRoles
        val capabilities = nodeData.meshRoles.map { role -> role.name }
        
        return NodeInfoDto(
            nodeId = nodeId,
            displayName = nodeId.substring(0, minOf(8, nodeId.length)), // Use first 8 chars as display name
            isOnline = !nodeData.isStale(GATEWAY_STALE_TIMEOUT_MS),
            lastSeen = System.currentTimeMillis(), // Topology doesn't track lastSeen, use current time
            capabilities = capabilities
        )
    }

    /**
     * Get current hotspot information
     * 
     * Priority order:
     * 1. LocalOnlyHotspot config (if device is running hotspot)
     * 2. WiFi Direct config (if using WiFi Direct)
     * 3. Station config (if connected as station to another hotspot)
     * 
     * Returns first available config, or null if none active.
     */
    override fun getHotspotInfo(): HotspotInfoDto? {
        Log.d(TAG, "getHotspotInfo() called")
        
        val node = myNode ?: run {
            Log.d(TAG, "getHotspotInfo() returning null (myNode is null)")
            return null
        }
        
        // Get current WiFi state
        val wifiState = runBlocking {
            node.meshrabiyaWifiManager.state.first()
        }
        
        // Priority 1: Check LocalOnlyHotspot state (device acting as AP)
        val localHotspotConfig = wifiState.localOnlyHotspotState.config
        if (localHotspotConfig != null && 
            wifiState.localOnlyHotspotState.status.toString() == "STARTED") {
            
            Log.d(TAG, 
                "getHotspotInfo() found LocalOnlyHotspot config: ssid=${localHotspotConfig.ssid}")
            
            return HotspotInfoDto(
                ssid = localHotspotConfig.ssid,
                password = localHotspotConfig.passphrase,
                band = localHotspotConfig.band.toString(),
                nodeAddress = node.addressAsInt,
                bssid = localHotspotConfig.bssid,
                hotspotType = "LOCAL_ONLY",
                port = localHotspotConfig.port
            )
        }
        
        // Priority 2: Check WiFi Direct state (device acting as group owner)
        val wifiDirectConfig = wifiState.wifiDirectState.config
        if (wifiDirectConfig != null) {
            Log.d(TAG, 
                "getHotspotInfo() found WiFiDirect config: ssid=${wifiDirectConfig.ssid}")
            
            return HotspotInfoDto(
                ssid = wifiDirectConfig.ssid,
                password = wifiDirectConfig.passphrase,
                band = wifiDirectConfig.band.toString(),
                nodeAddress = node.addressAsInt,
                bssid = wifiDirectConfig.bssid,
                hotspotType = "WIFI_DIRECT",
                port = wifiDirectConfig.port
            )
        }
        
        // Priority 3: Check station state (device connected to another hotspot)
        val stationConfig = wifiState.wifiStationState.config
        if (stationConfig != null) {
            Log.d(TAG, 
                "getHotspotInfo() found Station config: ssid=${stationConfig.ssid}")
            
            return HotspotInfoDto(
                ssid = stationConfig.ssid,
                password = stationConfig.passphrase,
                band = stationConfig.band.toString(),
                nodeAddress = stationConfig.nodeVirtualAddr,
                bssid = stationConfig.bssid,
                hotspotType = stationConfig.hotspotType.toString(),
                port = stationConfig.port
            )
        }
        
        Log.d(TAG, "getHotspotInfo() returning null (no active hotspot or station)")
        return null
    }

    /**
     * Join an existing mesh network using mesh-wide discovery.
     * 
     * Scans for all available mesh hotspots and connects to the strongest.
     * If CONNECTED when called, broadcasts merge announcement before connecting.
     */
    override fun joinMesh(jsonQrData: String, callback: (Result<Unit>) -> Unit) {
        Log.d(TAG, "========== JOIN MESH START ==========")
        Log.d(TAG, "joinMesh() called with QR data: ${jsonQrData.take(100)}...")
        Log.d(TAG, "Current mesh state: ${getMeshStatus()}")
        
        // Validate mesh is initialized
        if (myNode == null) {
            Log.e(TAG, "[JOIN FAIL] myNode is null - mesh not initialized!")
            callback(Result.failure(
                IllegalStateException("Mesh not initialized - call initMesh() first")
            ))
            return
        }
        
        Log.d(TAG, "[JOIN] Mesh validation passed, launching coroutine")
        Log.d(TAG, "[JOIN] Current node address: ${myNode?.address}")
        Log.d(TAG, "Launching coroutine for mesh-wide discovery join")
        
        // Launch connection in event monitoring scope (survives beyond this call)
        eventMonitoringScope.launch {
            try {
                // TODO PT8: If mesh is CONNECTED, broadcast MeshMergeAnnouncementMessage
                // and wait 5 seconds for propagation before connecting
                
                // Parse QR code JSON data
                val qrJson = org.json.JSONObject(jsonQrData)
                val password = qrJson.getString("password")
                lastJoinedMeshPassphrase = password
                Log.d(TAG, "[JOIN] Stored mesh passphrase for AP extension (${password.length} chars)")
                val ssidPattern = qrJson.optString("ssidPattern", "meshr-")  // Default to "meshr-"
                val bootstrapSsid = qrJson.optString("bootstrapSSID", null)  // Optional hint
                
                Log.d(TAG, "[JOIN] Parsed QR: password=$password, pattern=$ssidPattern, bootstrap=$bootstrapSsid")
                Log.d(TAG, "[JOIN] QR data validation successful")
                
                // Scan for available mesh hotspots
                val context = appContext ?: throw IllegalStateException("App context not set")
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                Log.d(TAG, "[JOIN] WiFi manager obtained, starting scan process")
                var attemptCount = 0
                var connected = false
                
                while (attemptCount < 3 && !connected) {
                    attemptCount++
                    Log.d(TAG, "[JOIN SCAN] ===== Attempt $attemptCount/3 =====")
                    Log.d(TAG, "[JOIN SCAN] Triggering WiFi scan...")
                    
                    // Trigger WiFi scan
                    wifiManager.startScan()
                    delay(2000)  // Wait for scan results
                    
                    // Get scan results and filter for mesh hotspots
                    val allNetworks = wifiManager.scanResults
                    Log.d(TAG, "[JOIN SCAN] Total networks detected: ${allNetworks.size}")
                    
                    // If bootstrapSSID is provided, look for it specifically; otherwise use pattern matching
                    val meshHotspots = if (!bootstrapSsid.isNullOrEmpty()) {
                        Log.d(TAG, "[JOIN SCAN] Looking for specific bootstrap SSID: $bootstrapSsid")
                        val bootstrap = allNetworks.filter { it.SSID == bootstrapSsid }
                        val pattern = allNetworks.filter { it.SSID.startsWith(ssidPattern) && it.SSID != bootstrapSsid }
                        (bootstrap + pattern).sortedByDescending { it.level }
                    } else {
                        Log.d(TAG, "[JOIN SCAN] No bootstrap SSID, using pattern matching: $ssidPattern")
                        allNetworks.filter { it.SSID.startsWith(ssidPattern) }.sortedByDescending { it.level }
                    }
                    
                    Log.d(TAG, "[JOIN SCAN] Mesh hotspots found: ${meshHotspots.size}")
                    meshHotspots.forEachIndexed { idx, hs ->
                        Log.d(TAG, "[JOIN SCAN]   [$idx] SSID=${hs.SSID}, Signal=${hs.level}dBm, Freq=${hs.frequency}MHz, BSSID=${hs.BSSID}")
                    }
                    
                    // Try connecting to each hotspot, starting with strongest
                    for ((index, hotspot) in meshHotspots.withIndex()) {
                        Log.d(TAG, "[JOIN CONNECT] --- Hotspot ${index + 1}/${meshHotspots.size} ---")
                        Log.d(TAG, "[JOIN CONNECT] Attempting connection to ${hotspot.SSID} (signal: ${hotspot.level} dBm)")
                        
                        try {
                            val band = when {
                                hotspot.frequency in 2400..2500 -> ConnectBand.BAND_2GHZ
                                hotspot.frequency in 5000..6000 -> ConnectBand.BAND_5GHZ
                                else -> ConnectBand.BAND_UNKNOWN
                            }
                            Log.d(TAG, "[JOIN CONNECT] Band detected: $band (${hotspot.frequency}MHz)")
                            
                            // Parse port from QR data (REQUIRED)
                            val meshPort = qrJson.getInt("port")
                            Log.d(TAG, "[JOIN CONNECT] Using port from QR: $meshPort")
                            
                            val config = com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig(
                                nodeVirtualAddr = 0,  // Discovered from originating message
                                ssid = hotspot.SSID,
                                passphrase = password,
                                linkLocalAddr = null,
                                port = meshPort,
                                hotspotType = HotspotType.LOCALONLY_HOTSPOT,
                                persistenceType = com.ustadmobile.meshrabiya.vnet.wifi.HotspotPersistenceType.NONE,
                                band = band,
                                bssid = hotspot.BSSID
                            )
                            
                            Log.d(TAG, "[JOIN CONNECT] Config created: ssid=${config.ssid}, port=${config.port}, band=${config.band}")
                            Log.d(TAG, "[JOIN CONNECT] Calling connectAsStation()...")
                            myNode?.connectAsStation(config)
                            Log.d(TAG, "[JOIN SUCCESS] ✅ Successfully connected to ${hotspot.SSID}")
                            Log.d(TAG, "[JOIN SUCCESS] Connection established, stopping scan loop")
                            connected = true
                            break  // Success - stop trying
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "[JOIN FAIL] ❌ Failed to connect to ${hotspot.SSID}", e)
                            Log.w(TAG, "[JOIN FAIL] Error: ${e.javaClass.simpleName}: ${e.message}")
                            // Continue to next hotspot
                        }
                    }
                    
                    if (!connected && attemptCount < 3) {
                        Log.d(TAG, "No connection established, waiting before retry...")
                        delay(2000)
                    }
                }
                
                if (connected) {
                    Log.d(TAG, "[JOIN RESULT] ========== JOIN MESH SUCCESS ==========")
                    Log.d(TAG, "[JOIN RESULT] Mesh join completed successfully")
                    Log.d(TAG, "[JOIN RESULT] New mesh state: ${getMeshStatus()}")
                    
                    // Initialize broadcast handler (NETWORK_BROADCAST_v2 implementation)
                    val node = myNode
                    if (node != null && broadcastHandler == null) {
                                                broadcastHandler = com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastMessageHandler(
                            virtualNode = node,
                            logger = { priority, message -> node.logger(priority, message) },
                            cacheDir = appContext?.cacheDir ?: throw IllegalStateException("Context required for broadcast handler"),
                            getDropFolderCallback = { getDropFolderAsDocumentFile() }
                        )
                        // Wire handler to VirtualNode
                        node.broadcastMessageHandler = broadcastHandler
                        Log.d("MeshrabiyaApiImpl", "Broadcast handler initialized and wired to VirtualNode (joinMesh)")
                        
                        // Apply any listeners registered before handler was created
                        applyPendingBroadcastListeners()
                    }
                    
                    callback(Result.success(Unit))
                } else {
                    Log.e(TAG, "[JOIN RESULT] ========== JOIN MESH FAILURE ==========")
                    Log.e(TAG, "[JOIN RESULT] No mesh hotspots available after 3 scan attempts")
                    Log.e(TAG, "[JOIN RESULT] Scanned for pattern: $ssidPattern")
                    callback(Result.failure(
                        Exception("No mesh hotspots available after 3 scan attempts")
                    ))
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "[JOIN ERROR] ========== JOIN MESH EXCEPTION ==========")
                Log.e(TAG, "[JOIN ERROR] Exception during join process", e)
                Log.e(TAG, "[JOIN ERROR] Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "[JOIN ERROR] Exception message: ${e.message}")
                callback(Result.failure(e))
            }
        }
        
        Log.d(TAG, "[JOIN] joinMesh() returning (async coroutine launched)")
    }

    /**
     * Merge current mesh with another mesh (CONNECTED state only).
     * 
     * ALWAYS broadcasts MeshMergeAnnouncementMessage before connecting.
     * Requires PT8 implementation (multi-hop forwarding, message types, storage).
     */
    override fun mergeMesh(jsonQrData: String, callback: (Result<Unit>) -> Unit) {
        Log.d(TAG, "========== MERGE MESH START ==========")
        Log.d(TAG, "mergeMesh() called with QR data: ${jsonQrData.take(100)}...")
        Log.d(TAG, "Current mesh state: ${getMeshStatus()}")
        
        // Validate mesh is initialized
        val node = myNode
        if (node == null) {
            Log.e(TAG, "[MERGE FAIL] myNode is null - mesh not initialized!")
            callback(Result.failure(
                IllegalStateException("Mesh not initialized - call initMesh() first")
            ))
            return
        }
        
        Log.d(TAG, "[MERGE] Current node address: ${node.address}")
        Log.d(TAG, "[MERGE] Current neighbor count: ${node.currentNodeState.originatorMessages.size}")
        
        // Validate CONNECTED state
        val currentState = getMeshStatus()
        if (currentState != MeshStateDto.CONNECTED) {
            Log.e(TAG, "[MERGE FAIL] Mesh is not CONNECTED - current state: $currentState")
            Log.e(TAG, "[MERGE FAIL] Merge requires CONNECTED state to announce to existing mesh")
            callback(Result.failure(
                IllegalStateException("Mesh must be CONNECTED to merge - current state: $currentState")
            ))
            return
        }
        
        Log.d(TAG, "[MERGE] State validation passed, launching coroutine")
        Log.d(TAG, "Launching coroutine for mesh merge")
        
        // Launch merge in event monitoring scope
        eventMonitoringScope.launch {
            try {
                // TODO PT8: Broadcast MeshMergeAnnouncementMessage to all devices on current mesh
                // Wait 5 seconds for multi-hop gossip propagation
                Log.d(TAG, "[MERGE ANNOUNCE] Broadcasting MeshMergeAnnouncementMessage to current mesh...")
                Log.d(TAG, "[MERGE ANNOUNCE] Announcement will propagate via multi-hop forwarding (PT8)")
                Log.d(TAG, "[MERGE ANNOUNCE] Waiting 5 seconds for gossip propagation...")
                delay(5000)  // Wait for announcement propagation
                Log.d(TAG, "[MERGE ANNOUNCE] Propagation delay complete")
                
                // Parse QR code JSON data
                val qrJson = org.json.JSONObject(jsonQrData)
                val password = qrJson.getString("password")
                val ssidPattern = qrJson.optString("ssidPattern", "meshr-")
                val bootstrapSsid = qrJson.optString("bootstrapSSID", null)  // Optional hint
                
                Log.d(TAG, "[MERGE] Parsed QR: password=$password, pattern=$ssidPattern, bootstrap=$bootstrapSsid")
                Log.d(TAG, "[MERGE] QR data validation successful")
                
                // Scan and connect (same logic as joinMesh)
                val context = appContext ?: throw IllegalStateException("App context not set")
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                Log.d(TAG, "[MERGE] WiFi manager obtained, starting scan process")
                var attemptCount = 0
                var connected = false
                
                while (attemptCount < 3 && !connected) {
                    attemptCount++
                    Log.d(TAG, "[MERGE SCAN] ===== Attempt $attemptCount/3 =====")
                    Log.d(TAG, "[MERGE SCAN] Triggering WiFi scan...")
                    
                    wifiManager.startScan()
                    delay(2000)
                    
                    val allNetworks = wifiManager.scanResults
                    Log.d(TAG, "[MERGE SCAN] Total networks detected: ${allNetworks.size}")
                    
                    // If bootstrapSSID is provided, look for it specifically; otherwise use pattern matching
                    val meshHotspots = if (!bootstrapSsid.isNullOrEmpty()) {
                        Log.d(TAG, "[MERGE SCAN] Looking for specific bootstrap SSID: $bootstrapSsid")
                        val bootstrap = allNetworks.filter { it.SSID == bootstrapSsid }
                        val pattern = allNetworks.filter { it.SSID.startsWith(ssidPattern) && it.SSID != bootstrapSsid }
                        (bootstrap + pattern).sortedByDescending { it.level }
                    } else {
                        Log.d(TAG, "[MERGE SCAN] No bootstrap SSID, using pattern matching: $ssidPattern")
                        allNetworks.filter { it.SSID.startsWith(ssidPattern) }.sortedByDescending { it.level }
                    }
                    
                    Log.d(TAG, "[MERGE SCAN] Mesh hotspots found: ${meshHotspots.size}")
                    meshHotspots.forEachIndexed { idx, hs ->
                        Log.d(TAG, "[MERGE SCAN]   [$idx] SSID=${hs.SSID}, Signal=${hs.level}dBm, Freq=${hs.frequency}MHz, BSSID=${hs.BSSID}")
                    }
                    
                    for ((index, hotspot) in meshHotspots.withIndex()) {
                        Log.d(TAG, "[MERGE CONNECT] --- Hotspot ${index + 1}/${meshHotspots.size} ---")
                        Log.d(TAG, "[MERGE CONNECT] Attempting merge connection to ${hotspot.SSID} (signal: ${hotspot.level} dBm)")
                        
                        try {
                            val band = when {
                                hotspot.frequency in 2400..2500 -> ConnectBand.BAND_2GHZ
                                hotspot.frequency in 5000..6000 -> ConnectBand.BAND_5GHZ
                                else -> ConnectBand.BAND_UNKNOWN
                            }
                            Log.d(TAG, "[MERGE CONNECT] Band detected: $band (${hotspot.frequency}MHz)")
                            
                            // Get port from MMCP originating message connectConfig
                            // This is for mesh-to-mesh merge without QR code - port comes from neighbor's broadcast
                            // Find the originating message whose connectConfig has matching SSID
                            val originatorMessages = node.originatingMessageManager.getOriginatorMessages()
                            val matchingMessage = originatorMessages.values.find { 
                                it.originatorMessage.connectConfig?.ssid == hotspot.SSID 
                            }
                            val meshPort = matchingMessage?.originatorMessage?.connectConfig?.port
                                ?: throw IllegalStateException("[MERGE CONNECT] No connectConfig with SSID ${hotspot.SSID} found in MMCP messages")
                            Log.d(TAG, "[MERGE CONNECT] Using port from MMCP: $meshPort")
                            
                            val config = com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig(
                                nodeVirtualAddr = 0,
                                ssid = hotspot.SSID,
                                passphrase = password,
                                linkLocalAddr = null,
                                port = meshPort,
                                hotspotType = HotspotType.LOCALONLY_HOTSPOT,
                                persistenceType = com.ustadmobile.meshrabiya.vnet.wifi.HotspotPersistenceType.NONE,
                                band = band,
                                bssid = hotspot.BSSID
                            )
                            
                            Log.d(TAG, "[MERGE CONNECT] Config created: ssid=${config.ssid}, port=${config.port}, band=${config.band}")
                            Log.d(TAG, "[MERGE CONNECT] Calling connectAsStation()...")
                            node.connectAsStation(config)
                            Log.d(TAG, "[MERGE SUCCESS] ✅ Successfully merged with ${hotspot.SSID}")
                            Log.d(TAG, "[MERGE SUCCESS] Meshes are now merged, stopping scan loop")
                            connected = true
                            break
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "[MERGE FAIL] ❌ Failed to merge with ${hotspot.SSID}", e)
                            Log.w(TAG, "[MERGE FAIL] Error: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                    
                    if (!connected && attemptCount < 3) {
                        delay(2000)
                    }
                }
                
                if (connected) {
                    Log.d(TAG, "[MERGE RESULT] ========== MERGE MESH SUCCESS ==========")
                    Log.d(TAG, "[MERGE RESULT] Mesh merge completed successfully")
                    Log.d(TAG, "[MERGE RESULT] Two meshes are now unified")
                    Log.d(TAG, "[MERGE RESULT] New mesh state: ${getMeshStatus()}")
                    Log.d(TAG, "[MERGE RESULT] Neighbor count after merge: ${node.currentNodeState.originatorMessages.size}")
                    
                    // Initialize broadcast handler if not already done (NETWORK_BROADCAST_v2 implementation)
                    if (broadcastHandler == null) {
                                                broadcastHandler = com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastMessageHandler(
                            virtualNode = node,
                            logger = { priority, message -> node.logger(priority, message) },
                            cacheDir = appContext?.cacheDir ?: throw IllegalStateException("Context required for broadcast handler"),
                            getDropFolderCallback = { getDropFolderAsDocumentFile() }
                        )
                        // Wire handler to VirtualNode
                        node.broadcastMessageHandler = broadcastHandler
                        Log.d("MeshrabiyaApiImpl", "Broadcast handler initialized and wired to VirtualNode (mergeMesh)")
                        
                        // Apply any listeners registered before handler was created
                        applyPendingBroadcastListeners()
                    }
                    
                    callback(Result.success(Unit))
                } else {
                    Log.e(TAG, "[MERGE RESULT] ========== MERGE MESH FAILURE ==========")
                    Log.e(TAG, "[MERGE RESULT] No mesh hotspots available after 3 scan attempts")
                    Log.e(TAG, "[MERGE RESULT] Scanned for pattern: $ssidPattern")
                    Log.e(TAG, "[MERGE RESULT] Original mesh still intact, merge aborted")
                    callback(Result.failure(
                        Exception("No mesh hotspots available for merge after 3 scan attempts")
                    ))
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "[MERGE ERROR] ========== MERGE MESH EXCEPTION ==========")
                Log.e(TAG, "[MERGE ERROR] Exception during merge process", e)
                Log.e(TAG, "[MERGE ERROR] Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "[MERGE ERROR] Exception message: ${e.message}")
                callback(Result.failure(e))
            }
        }
        
        Log.d(TAG, "[MERGE] mergeMesh() returning (async coroutine launched)")
    }

    // --- Gateway Controls ---
    // TODO: Reimplement using GatewaySelector from canonical workflows (2025-12-04)
    
    /**
     * Load persisted role preferences from dataStore and apply to EmergentRoleManager
     * Called when mesh starts to restore user's role preferences
     */
    private suspend fun loadAndApplyPersistedRolePreferences() {
        val roleManager = myNode?.emergentRoleManager ?: return
        val context = appContext ?: return
        
        try {
            val preferredRoles = mutableSetOf<MeshRole>()
            val prefs = context.dataStore.data.first()
            
            // Load gateway preferences
            val torGatewayEnabled = prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_TOR_GATEWAY_ENABLED)] ?: false
            Log.i(TAG, "[INIT] Loaded Tor Gateway preference: $torGatewayEnabled")
            if (torGatewayEnabled) {
                preferredRoles.add(MeshRole.TOR_GATEWAY)
            }
            
            val clearnetGatewayEnabled = prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_CLEARNET_GATEWAY_ENABLED)] ?: false
            Log.i(TAG, "[INIT] Loaded Internet Gateway preference: $clearnetGatewayEnabled")
            if (clearnetGatewayEnabled) {
                preferredRoles.add(MeshRole.CLEARNET_GATEWAY)
            }
            
            // Load storage participation preference
            val storageEnabled = prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_STORAGE_PARTICIPATION_ENABLED)] ?: false
            Log.i(TAG, "[INIT] Loaded Storage participation preference: $storageEnabled")
            if (storageEnabled) {
                preferredRoles.add(MeshRole.STORAGE_NODE)
            }
            
            // Load service participation preference
            val serviceEnabled = prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_SERVICE_PARTICIPATION_ENABLED)] ?: false
            Log.i(TAG, "[INIT] Loaded Service participation preference: $serviceEnabled")
            if (serviceEnabled) {
                preferredRoles.add(MeshRole.COMPUTE_NODE)
            }
            
            // Apply to role manager
            roleManager.setPreferredRoles(preferredRoles)
            Log.i(TAG, "[INIT] Loaded and applied persisted preferred roles: $preferredRoles")
            
            // Trigger initial role calculation
            roleManager.updateRoles()
            val actualRoles = roleManager.getCurrentMeshRoles()
            Log.i(TAG, "[INIT] Current mesh roles AFTER updateRoles(): $actualRoles")
        } catch (e: Exception) {
            Log.e(TAG, "[INIT] Error loading persisted role preferences: ${e.message}", e)
        }
    }
    
    // === MESH PROXY APPS (Phase 2) ===

    private val _meshProxyActiveFlow = MutableStateFlow(false)

    override fun getMeshProxyActiveFlow(): StateFlow<Boolean> = _meshProxyActiveFlow

    private val _meshInternetGatewayAvailableFlow = MutableStateFlow(false)

    override fun getMeshInternetGatewayAvailableFlow(): StateFlow<Boolean> =
        _meshInternetGatewayAvailableFlow

    override suspend fun setMeshProxyApps(packageNames: Set<String>) {
        val context = appContext ?: throw IllegalStateException("App context not provided")
        context.dataStore.edit { prefs ->
            prefs[stringSetPreferencesKey(MeshrabiyaConstants.KEY_MESH_PROXY_APP_PACKAGES)] = packageNames
        }
        Log.i(TAG, "[MESH_PROXY] Saved ${packageNames.size} proxy app packages")
    }

    override suspend fun getMeshProxyApps(): Set<String> {
        val context = appContext ?: return emptySet()
        val prefs = context.dataStore.data.first()
        return prefs[stringSetPreferencesKey(MeshrabiyaConstants.KEY_MESH_PROXY_APP_PACKAGES)]
            ?: emptySet()
    }

    @Volatile private var meshLocalSocksProxy: com.ustadmobile.meshrabiya.vnet.MeshLocalSocksProxy? = null

    override fun getMeshProxySocksPort(): Int = meshLocalSocksProxy?.localPort ?: 0

    override fun startMeshProxyServer() {
        val node = myNode ?: return
        if (meshLocalSocksProxy != null) return
        val proxy = com.ustadmobile.meshrabiya.vnet.MeshLocalSocksProxy(
            logger = node.logger,
            logPrefix = "[MeshProxy]",
            meshSocketFactory = node.socketFactory,
            getGatewayAddress = {
                val gateways = node.getAvailableGatewayAddresses()
                gateways.firstOrNull()?.let { addr -> node.getInetAddressFor(addr) }
            }
        )
        proxy.start()
        meshLocalSocksProxy = proxy
        _meshProxyActiveFlow.value = true
        Log.i(TAG, "[MESH_PROXY] MeshLocalSocksProxy started on port ${proxy.localPort}")
    }

    override fun stopMeshProxyServer() {
        meshLocalSocksProxy?.stop()
        meshLocalSocksProxy = null
        _meshProxyActiveFlow.value = false
        Log.i(TAG, "[MESH_PROXY] MeshLocalSocksProxy stopped")
    }

    override fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        val roleManager = myNode?.emergentRoleManager
        if (roleManager == null) {
            callback(Result.failure(IllegalStateException("Role manager not initialized")))
            return
        }
        
        try {
            Log.i(TAG, "[GATEWAY_TOGGLE] Setting Tor Gateway to: $enabled")
            
            // Persist preference to dataStore
            val context = appContext ?: throw IllegalStateException("App context not provided")
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_TOR_GATEWAY_ENABLED)] = enabled
                }
            }
            
            // Update preferred roles
            val currentRoles = roleManager.getPreferredRoles().toMutableSet()
            Log.i(TAG, "[GATEWAY_TOGGLE] Preferred roles BEFORE: $currentRoles")
            
            if (enabled) {
                currentRoles.add(MeshRole.TOR_GATEWAY)
                Log.i(TAG, "[GATEWAY_TOGGLE] Added TOR_GATEWAY to preferred roles")
            } else {
                currentRoles.remove(MeshRole.TOR_GATEWAY)
                Log.i(TAG, "[GATEWAY_TOGGLE] Removed TOR_GATEWAY from preferred roles")
            }
            
            roleManager.setPreferredRoles(currentRoles)
            Log.i(TAG, "[GATEWAY_TOGGLE] Preferred roles AFTER: $currentRoles")
            
            // Trigger role recalculation
            Log.i(TAG, "[GATEWAY_TOGGLE] Triggering updateRoles()...")
            roleManager.updateRoles(userInitiated = true)
            
            // Log the actual current mesh roles after update
            val actualRoles = roleManager.getCurrentMeshRoles()
            Log.i(TAG, "[GATEWAY_TOGGLE] Current mesh roles AFTER updateRoles(): $actualRoles")
            
            callback(Result.success(Unit))
        } catch (e: Exception) {
            Log.e(TAG, "[GATEWAY_TOGGLE] Error setting Tor Gateway: ${e.message}", e)
            callback(Result.failure(e))
        }
    }
    override fun getTorGatewayStatus(): Boolean {
        val context = appContext ?: return false
        return runBlocking {
            context.dataStore.data.first()[booleanPreferencesKey(MeshrabiyaConstants.KEY_TOR_GATEWAY_ENABLED)] ?: false
        }
    }
    override fun setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        val roleManager = myNode?.emergentRoleManager
        if (roleManager == null) {
            callback(Result.failure(IllegalStateException("Role manager not initialized")))
            return
        }
        
        try {
            Log.i(TAG, "[GATEWAY_TOGGLE] Setting Internet Gateway to: $enabled")
            
            // Persist preference to dataStore
            val context = appContext ?: throw IllegalStateException("App context not provided")
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_CLEARNET_GATEWAY_ENABLED)] = enabled
                }
            }
            
            // Update preferred roles
            val currentRoles = roleManager.getPreferredRoles().toMutableSet()
            Log.i(TAG, "[GATEWAY_TOGGLE] Preferred roles BEFORE: $currentRoles")
            
            if (enabled) {
                currentRoles.add(MeshRole.CLEARNET_GATEWAY)
                Log.i(TAG, "[GATEWAY_TOGGLE] Added CLEARNET_GATEWAY to preferred roles")
            } else {
                currentRoles.remove(MeshRole.CLEARNET_GATEWAY)
                Log.i(TAG, "[GATEWAY_TOGGLE] Removed CLEARNET_GATEWAY from preferred roles")
            }
            
            roleManager.setPreferredRoles(currentRoles)
            Log.i(TAG, "[GATEWAY_TOGGLE] Preferred roles AFTER: $currentRoles")
            
            // Trigger role recalculation
            Log.i(TAG, "[GATEWAY_TOGGLE] Triggering updateRoles()...")
            roleManager.updateRoles(userInitiated = true)
            
            // Log the actual current mesh roles after update
            val actualRoles = roleManager.getCurrentMeshRoles()
            Log.i(TAG, "[GATEWAY_TOGGLE] Current mesh roles AFTER updateRoles(): $actualRoles")
            
            callback(Result.success(Unit))
        } catch (e: Exception) {
            Log.e(TAG, "[GATEWAY_TOGGLE] Error setting Internet Gateway: ${e.message}", e)
            callback(Result.failure(e))
        }
    }
    override fun getInternetGatewayStatus(): Boolean {
        val context = appContext ?: return false
        return runBlocking {
            context.dataStore.data.first()[booleanPreferencesKey(MeshrabiyaConstants.KEY_CLEARNET_GATEWAY_ENABLED)] ?: false
        }
    }
    override fun getGatewayStatus(): Boolean {
        val roleManager = myNode?.emergentRoleManager ?: return false
        val roles = roleManager.getCurrentMeshRoles()
        return roles.contains(MeshRole.TOR_GATEWAY) || 
               roles.contains(MeshRole.CLEARNET_GATEWAY) ||
               roles.contains(MeshRole.I2P_GATEWAY)
    }

    // --- V3: Gateway Preference Implementation ---
    override fun setGatewayPreference(preference: GatewayPreference, callback: (Result<Unit>) -> Unit) {
        try {
            val context = appContext ?: throw IllegalStateException("App context not provided")
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs[stringPreferencesKey(GatewayPreference.KEY_GATEWAY_PREFERENCE)] = GatewayPreference.toString(preference )  
                }
                currentGatewayPreference = preference
            }
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun getGatewayPreference(): GatewayPreference {
        return currentGatewayPreference
    }

    override fun isTorActive(): Boolean {
        return isTorRunning
    }

    /**
     * V3: Internal method to update Tor status from TorStatusMonitor.
     * Called by TorStatusMonitor BroadcastReceiver when Orbot status changes.
     */
    internal fun updateTorStatus(isActive: Boolean) {
        isTorRunning = isActive
    }

    /**
     * V3: Load gateway preference from DataStore on initialization.
     */
    private suspend fun loadGatewayPreference(context: Context) {
        val prefs = context.dataStore.data.first()
        val prefString = prefs[stringPreferencesKey(GatewayPreference.KEY_GATEWAY_PREFERENCE)]
        currentGatewayPreference = GatewayPreference.fromString(prefString)
    }

    // --- Storage Participation ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        try {
            Log.i(TAG, "[STORAGE_TOGGLE] Setting Storage Participation to: $enabled (mesh started: ${myNode != null})")
            
            // ALWAYS persist preference to dataStore first - this works even if mesh isn't started
            val context = appContext ?: throw IllegalStateException("App context not provided")
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_STORAGE_PARTICIPATION_ENABLED)] = enabled
                }
            }
            Log.i(TAG, "[STORAGE_TOGGLE] Persisted to dataStore: $enabled")
            
            // If mesh is started, apply changes immediately to running managers
            val roleManager = myNode?.emergentRoleManager
            val storageManager = myNode?.distributedStorageManager
            
            if (roleManager != null) {
                Log.i(TAG, "[STORAGE_TOGGLE] Mesh is running, updating roles")
                
                // Configure storage manager if available
                if (storageManager != null) {
                    Log.i(TAG, "[STORAGE_TOGGLE] Configuring storage manager")
                    val config = com.ustadmobile.meshrabiya.storage.DistributedStorageManager.StorageParticipationConfig(
                        participationEnabled = enabled,
                        totalQuota = storageManager.storageConfig.defaultQuota,
                        allowedDirectories = emptyList(),  // Use defaults
                        encryptionRequired = storageManager.storageConfig.encryptionEnabled
                    )
                    storageManager.configureStorageParticipation(config)
                } else {
                    Log.i(TAG, "[STORAGE_TOGGLE] Storage manager not available, skipping storage config")
                }
                
                // Update preferred roles
                val currentRoles = roleManager.getPreferredRoles().toMutableSet()
                Log.i(TAG, "[STORAGE_TOGGLE] Preferred roles BEFORE: $currentRoles")
                
                if (enabled) {
                    currentRoles.add(MeshRole.STORAGE_NODE)
                    Log.i(TAG, "[STORAGE_TOGGLE] Added STORAGE_NODE to preferred roles")
                } else {
                    currentRoles.remove(MeshRole.STORAGE_NODE)
                    Log.i(TAG, "[STORAGE_TOGGLE] Removed STORAGE_NODE from preferred roles")
                }
                
                roleManager.setPreferredRoles(currentRoles)
                Log.i(TAG, "[STORAGE_TOGGLE] Preferred roles AFTER: $currentRoles")
                
                // Trigger role recalculation
                Log.i(TAG, "[STORAGE_TOGGLE] Triggering updateRoles()...")
                roleManager.updateRoles(userInitiated = true)
                
                // Debug: check if roles actually changed
                val actualRoles = roleManager.getCurrentMeshRoles()
                Log.i(TAG, "[STORAGE_TOGGLE] Current mesh roles AFTER updateRoles(): $actualRoles")
            } else {
                Log.i(TAG, "[STORAGE_TOGGLE] Mesh not started yet, preference saved for later application")
            }
            
            callback(Result.success(Unit))
        } catch (e: Exception) {
            Log.e(TAG, "[STORAGE_TOGGLE] Error setting Storage Participation: ${e.message}", e)
            callback(Result.failure(e))
        }
    }
    override fun getStorageParticipationStatus(): Boolean {
        val context = appContext ?: return false
        return runBlocking {
            context.dataStore.data.first()[booleanPreferencesKey(MeshrabiyaConstants.KEY_STORAGE_PARTICIPATION_ENABLED)] ?: false
        }
    }
    override fun getAvailableStorageDevices(): List<StorageDeviceDto> {
        // TODO properly implement getAvailableStorageDevices()
        // Storage device enumeration not yet implemented in DistributedStorageManager
        // Return empty list until backend implementation available
        return emptyList()
    }
    
    override fun setStorageAllocation(deviceId: String,
    path: String, allocatedMB: Long, ) {
        try {
            val current = MeshrabiyaConstants.getStorageAllocations().toMutableList()
            val idx = current.indexOfFirst { it.path == path }
            if (idx >= 0) {
                current[idx] = StorageAllocation(path, allocatedMB,deviceId,)
            } else {
                current.add(StorageAllocation(path, allocatedMB,deviceId,))
            }
            MeshrabiyaConstants.setStorageAllocations(current)
            // callback(Result.success(Unit))
        } catch (e: Exception) {
            // callback(Result.failure(e))
        }
    }

    override fun getStorageAllocations(): List<StorageAllocationDto> {
        val storage =MeshrabiyaConstants.getStorageAllocations()
        return storage.map {
            it.toDto()
        }
    }
    
    override fun enableDistributedStorage() {
        val storageManager = myNode?.distributedStorageManager
        val listener = myNode?.obtainMeshEcosystemListener()
        
        if (storageManager != null && listener != null) {
            storageManager.registerWithEcosystemListener(listener)
        }
    }
    
    override fun disableDistributedStorage() {
        val storageManager = myNode?.distributedStorageManager
        val listener = myNode?.obtainMeshEcosystemListener()
        
        if (storageManager != null && listener != null) {
            storageManager.unregisterFromEcosystemListener(listener)
        }
    }

    private var onDropFolderUpdateHandler: ((List<DropFolderItemDto>) -> Unit)? = null

    override fun setOnDropFolderUpdate(handler: (List<DropFolderItemDto>) -> Unit) {
        onDropFolderUpdateHandler = handler
    }

    internal fun notifyDropFolderUpdate(changes: List<DropFolderItem>) {
        val dtos = changes.map { it.toDto() }
        onDropFolderUpdateHandler?.invoke(dtos)
    }

    

    override fun setDropFolderUri(uri: String) {
        MeshrabiyaConstants.setDropFolderUri(uri)
        Log.d(TAG, "Drop folder URI set: $uri")
    }
    
    override fun getDropFolderUri(): String? {
        return MeshrabiyaConstants.getDropFolderUri()
    }
    // TODO: Reimplement using TaskManager from canonical workflows (2025-12-04)
    override fun isComputeLayerParticipating(): Boolean {
        return MeshrabiyaConstants.isComputeLayerParticipating()
    }

    private fun getDropFolderAsDocumentFile(): DocumentFile? {
        val uriString = getDropFolderUri() ?: return null
        return try {
            val context = appContext ?: return null
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                DocumentFile.fromFile(java.io.File(uri.path ?: return null))
            } else {
                DocumentFile.fromTreeUri(context, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get drop folder DocumentFile", e)
            null
        }
    }

    override fun setStorageQuotaBytes(quotaBytes: Long) {
        MeshrabiyaConstants.setStorageQuotaBytes(quotaBytes)
        Log.d(TAG, "Storage quota set: ${quotaBytes / (1024 * 1024)}MB ($quotaBytes bytes)")
    }
    
    override fun getStorageQuotaBytes(): Long {
        return MeshrabiyaConstants.getStorageQuotaBytes()
    }

    override fun setComputeLayerParticipatingEnabled(enabled: Boolean) {
        MeshrabiyaConstants.setComputeLayerParticipatingEnabled(enabled)
    }

    // --- Drop Folder Management ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    // override fun selectDropFolder(path: String, callback: (Result<Unit>) -> Unit) {
    //     try {
    //         val context = appContext ?: throw IllegalStateException("Context required")
    //         val folder = File(path)
            
    //         // Validate folder
    //         if (!folder.exists() || !folder.isDirectory) {
    //             callback(Result.failure(IllegalArgumentException("Invalid folder path: $path")))
    //             return
    //         }
    //         if (!folder.canWrite()) {
    //             callback(Result.failure(IllegalArgumentException("Folder not writable: $path")))
    //             return
    //         }
            
    //         // Save to SharedPreferences
    //         val prefs = context.getSharedPreferences("meshrabiya_prefs", Context.MODE_PRIVATE)
    //         prefs.edit().putString("drop_folder_path", path).apply()
            
    //         Log.i(TAG, "Drop folder set: $path")
    //         callback(Result.success(Unit))
    //     } catch (e: Exception) {
    //         Log.e(TAG, "Failed to set drop folder", e)
    //         callback(Result.failure(e))
    //     }
    // }
    
    override fun getDropFolderFiles(): List<File> = emptyList() // distributedStorageManager?.getDropFolderFiles() ?: emptyList()

    // =========================================================
    // Section 1: File Operations (Canonical Workflow Refactor)
    // =========================================================
    // All file operations below are refactored to match canonical workflow requirements.
    // Each method includes explicit TODOs, error handling, and implementation notes.
    // Remove TODOs and update implementation when DistributedStorageManager supports each operation.
    override fun storeFile(file: File, recipients:List<RecipientEntryDto>
    ) {
        val storageManager = myNode?.distributedStorageManager
        if (storageManager == null) {
            getOnOperationFailed()?.invoke("storeFile", IllegalStateException("Storage manager not initialized"))
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fileBytes = file.readBytes()
                val senderId = myNode?.address?.hostAddress
                val recipientEntryList: List<RecipientEntry> = recipients.map { it.toInternal() }
                val fileRef = storageManager.storeFile(
                    path = file.absolutePath,
                    data = fileBytes,
                    recipients = recipientEntryList, // TODO: Specify recipients if needed
                )
                if (fileRef != null) {
                    // callback(Result.success(fileRef.id))
                    onFileStored?.invoke(fileRef.fileId, file, Result.success(fileRef.fileId))
                } else {
                    // callback(Result.failure(Exception("Failed to store file")))
                     getOnOperationFailed()?.invoke("storeFile",  Exception("Failed to store file"))
                }
            } catch (e: Exception) {
                // callback(Result.failure(e))
                getOnOperationFailed()?.invoke("storeFile", Exception("Failed to store file"))
            }
        }
    }
    // TODO retreieveFile should not return anything. when files is retrieved, generic handler shpu;d be calles
    override suspend fun retrieveFile(fileId: String): ByteArray? {
        if (fileId.isBlank()) {
            getOnOperationFailed()?.invoke("retrieveFile", IllegalArgumentException("File ID cannot be blank"))
            return null
        }
        val storageManager = myNode?.distributedStorageManager
        if (storageManager == null) {
            getOnOperationFailed()?.invoke("retrieveFile", IllegalStateException("Storage manager not initialized"))
            return null
        }
        val metadata = storageManager.getFileMetadata(fileId)
        if (metadata == null) {
            getOnOperationFailed()?.invoke("retrieveFile", java.io.FileNotFoundException("File not found: $fileId"))
            return null
        }
        return try {
            storageManager.retrieveFile(fileId)
        } catch (e: Exception) {
            getOnOperationFailed()?.invoke("retrieveFile", e)
            null
        }
    }

    override fun streamFile(fileId: String, callback: (Result<Unit>) -> Unit) {
        // Streaming not implemented in DistributedStorageManager; return error for now
        callback(Result.failure(NotImplementedError("streamFile not implemented in DistributedStorageManager")))
    }

    override fun deleteFile(fileId: String, callback: (Result<Unit>) -> Unit) {
        if (fileId.isBlank()) {
            callback(Result.failure(IllegalArgumentException("File ID cannot be blank")))
            return
        }
        val storageManager = myNode?.distributedStorageManager
        if (storageManager == null) {
            callback(Result.failure(IllegalStateException("Storage manager not initialized")))
            return
        }
        val metadata = storageManager.getFileMetadata(fileId)
        if (metadata == null) {
            callback(Result.failure(java.io.FileNotFoundException("File not found: $fileId")))
            return
        }
        // No deleteFile API in DistributedStorageManager; return error for now
        callback(Result.failure(NotImplementedError("deleteFile not implemented in DistributedStorageManager")))
    }
    override fun getAllMeshFiles(): List<MeshFileDto> {
        // Check storage manager availability
        val storageManager = myNode?.distributedStorageManager ?: return emptyList()
        
        try {
            // Get all file metadata from storage manager's file metadata map
            val fileMetadataMap = storageManager.fileMetadataStore
            
            if (fileMetadataMap.isEmpty()) {
                return emptyList()
            }
            
            // Convert FileMetadata to MeshFile
            return fileMetadataMap.values.map { metadata ->
                MeshFileDto(
                    fileId = metadata.fileId,
                    fileName = File(metadata.path).name,  // Extract filename from path
                    owner = metadata.owner.toDto(),
                    recipients = metadata.recipients.map { it.toDto() },
                    sizeBytes = metadata.sizeBytes,
                    createdAt = metadata.createdAt,
                    relativePath = metadata.relativePath,
                    path = metadata.path
                )
            }
        } catch (e: Exception) {
            onOperationFailed?.invoke("getAllMeshFiles", e)
            return emptyList()
        }
    }

    // --- Distributed Service Layer ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        try {
            Log.i(TAG, "[SERVICE_TOGGLE] Setting Service Participation ($serviceId) to: $enabled (mesh started: ${myNode != null})")
            
            // ALWAYS persist preference to dataStore (works even if mesh not started)
            val context = appContext ?: throw IllegalStateException("App context not provided")
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_SERVICE_PARTICIPATION_ENABLED)] = enabled
                }
            }
            Log.i(TAG, "[SERVICE_TOGGLE] Persisted to dataStore: $enabled")
            
            // IF mesh is started, apply to runtime role manager
            val roleManager = myNode?.emergentRoleManager
            if (roleManager != null) {
                // Update preferred roles
                val currentRoles = roleManager.getPreferredRoles().toMutableSet()
                Log.i(TAG, "[SERVICE_TOGGLE] Preferred roles BEFORE: $currentRoles")
                
                if (enabled) {
                    currentRoles.add(MeshRole.COMPUTE_NODE)
                    Log.i(TAG, "[SERVICE_TOGGLE] Added COMPUTE_NODE to preferred roles")
                } else {
                    currentRoles.remove(MeshRole.COMPUTE_NODE)
                    Log.i(TAG, "[SERVICE_TOGGLE] Removed COMPUTE_NODE from preferred roles")
                }
                
                roleManager.setPreferredRoles(currentRoles)
                Log.i(TAG, "[SERVICE_TOGGLE] Preferred roles AFTER: $currentRoles")
                
                // Trigger role recalculation
                Log.i(TAG, "[SERVICE_TOGGLE] Triggering updateRoles()...")
                roleManager.updateRoles(userInitiated = true)
                
                // Debug: check if roles actually changed
                val actualRoles = roleManager.getCurrentMeshRoles()
                Log.i(TAG, "[SERVICE_TOGGLE] Current mesh roles AFTER updateRoles(): $actualRoles")
            } else {
                Log.i(TAG, "[SERVICE_TOGGLE] Mesh not started yet, preference saved for later application")
            }
            
            callback(Result.success(Unit))
        } catch (e: Exception) {
            Log.e(TAG, "[SERVICE_TOGGLE] Error setting Service Participation: ${e.message}", e)
            callback(Result.failure(e))
        }
    }
    override fun getAvailableServices(): List<String> = emptyList() // myNode?.getAvailableServices() ?: emptyList()
    override fun getServiceParticipationStatus(serviceId: String): Boolean {
        val context = appContext ?: return false
        return runBlocking {
            context.dataStore.data.first()[booleanPreferencesKey(MeshrabiyaConstants.KEY_SERVICE_PARTICIPATION_ENABLED)] ?: false
        }
    }

    // --- Compute/Task Operations ---
    /**
     * Submit a compute task to the mesh network.
     * 
     * @param requestParams Map containing:
     *   - taskId (String, optional): Unique task identifier (auto-generated if not provided)
     *   - taskType (String, required): Execution engine (python, jvm, javascript, ml-native)
     *   
     * @return ApiResult.Success if task submitted, ApiResult.Failure on error
     * 
     * Note: JobType removed 2025-12-06 - no concept of "supported job types".
     * Use taskType to specify execution engine. Service discovery handles capability matching.
     */
    override fun addTask(serviceId: String,requestParams: Map<String, Any>, recipients: List<RecipientEntryDto>): Any? {
        return try {
            // Extract parameters
            val taskId = requestParams["taskId"] as? String ?: java.util.UUID.randomUUID().toString()
            
            
            // Check compute client availability
            val computeClient = myNode?.obtainDistributedComputeClient() 
                ?: return ApiResult.Failure(IllegalStateException("Compute client not initialized"))
            val recipientEntryList: List<RecipientEntry> = recipients.map { it.toInternal() }
            // Create LocalComputeTaskRequest
            val request = LocalComputeTaskRequest(
                requestId = java.util.UUID.randomUUID().toString(),
                taskId = taskId,
                serviceId = serviceId, 
                inputs = requestParams,
                timestamp = System.currentTimeMillis(),
                recipients = recipientEntryList,
            )
            
            // Submit task asynchronously
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    computeClient.processTaskRequest(request)
                } catch (e: Exception) {
                    onOperationFailed?.invoke("addTask", e)
                }
            }
            
            ApiResultDto.Success  // Return immediately, status updates via callback
        } catch (e: Exception) {
            onOperationFailed?.invoke("addTask", e)
        }
    }

    // override fun startTask(taskId: String, callback: (Result<Unit>) -> Unit) {
    //     callback(Result.failure(NotImplementedError("startTask not yet implemented in canonical workflows")))
    //     // intelligentDistributedComputeService?.startTask(taskId, callback)
    // }

    override fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(NotImplementedError("cancelTask not yet implemented in canonical workflows")))
        // intelligentDistributedComputeService?.cancelTask(taskId, callback)
    }
    
    // DEPRECATED 2025-12-06: getJobTypes() removed
    // JobType is for ServiceLibraryEntry categorization only, not for API-level task validation
    // Use taskType (execution engine: python, jvm, js, ml-native) for task submission
    // override fun getJobTypes(): List<JobType> = emptyList()
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // These implementations reference scheduler types that are unused in Phase 3-4
    // override fun getTaskStatus(taskId: String): ExecutionPlan? = intelligentDistributedComputeService?.getTaskStatus(taskId)
    // override fun getAllTasks(): List<ComputeTask> = intelligentDistributedComputeService?.getAllTasks() ?: emptyList()
    private var onFileRetrieved: ((fileId: String, file: File) -> Unit)? = null
    private var onFileStored: ((fileId: String, file: File, result: Result<String>) -> Unit)? = null
    private var onPermissionUpdated: ((fileId: String, success: Boolean) -> Unit)? = null
    private var onOperationFailed: ((operation: String, error: Throwable) -> Unit)? = null
    // UNUSED SCHEDULER API - Commented 2025-12-04
    // private var onTaskCompleted: ((taskId: String, result: ExecutionPlan) -> Unit)? = null
    private var onFileShared: ((fileId: String, recipientId: String) -> Unit)? = null
    private var onFileAddedToDropFolder: ((fileId: String, file: File) -> Unit)? = null
    
    // Broadcast event handlers (added 2026-02-01)
    private var onBroadcastSent: ((com.ustadmobile.meshrabiya.api.model.BroadcastResultDto) -> Unit)? = null
    private var onBroadcastFailed: ((broadcastId: String, error: Throwable) -> Unit)? = null

    override fun setOnFileRetrieved(handler: (fileId: String, file: File) -> Unit) {
        onFileRetrieved = handler
    }
    override fun setOnFileStored(handler: (fileId: String, file: File, result: Result<String>) -> Unit) {
        onFileStored = handler
    }
    fun getOnFileStored(): ((fileId: String, file: File, result: Result<String>) -> Unit)? {
        return onFileStored
    }
    override fun setOnPermissionUpdated(handler: (fileId: String, success: Boolean) -> Unit) {
        onPermissionUpdated = handler
    }
    override fun setOnOperationFailed(handler: (operation: String, error: Throwable) -> Unit) {
        onOperationFailed = handler
    }

    fun getOnOperationFailed(): ((operation: String, error: Throwable) -> Unit)? {
        return onOperationFailed
    }
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // override fun setOnTaskCompleted(handler: (taskId: String, result: ExecutionPlan) -> Unit) {
    //     onTaskCompleted = handler
    // }
    
    
    override fun setOnFileShared(handler: (fileId: String, recipientId: String) -> Unit) {
        onFileShared = handler
    }
    override fun setOnFileAddedToDropFolder(handler: (fileId: String, file: File) -> Unit) {
        onFileAddedToDropFolder = handler
    }
    
    override fun setOnBroadcastSent(handler: (com.ustadmobile.meshrabiya.api.model.BroadcastResultDto) -> Unit) {
        onBroadcastSent = handler
    }
    
    override fun setOnBroadcastFailed(handler: (broadcastId: String, error: Throwable) -> Unit) {
        onBroadcastFailed = handler
    }
    
    fun getOnBroadcastSent(): ((com.ustadmobile.meshrabiya.api.model.BroadcastResultDto) -> Unit)? {
        return onBroadcastSent
    }
    
    fun getOnBroadcastFailed(): ((broadcastId: String, error: Throwable) -> Unit)? {
        return onBroadcastFailed
    }

    // --- Settings and State ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun getSettings(): Map<String, Any> {
        return mapOf(
            "dropFolderPath" to "",
            "availableServices" to emptyList<String>()
            // DEPRECATED 2025-12-06: jobTypes removed - use ServiceLibraryEntries for capability discovery
        )
    }
    override fun setSetting(key: String, value: Any, callback: (Result<Unit>) -> Unit) {
        // try {
        //     when (key) {
        //         "dropFolderPath" -> distributedStorageManager?.selectDropFolder(value as String)
        //         else -> throw IllegalArgumentException("Unknown setting key: $key")
        //     }
            callback(Result.success(Unit))
        // } catch (e: Exception) {
        //     callback(Result.failure(e))
        // }
    }

    // --- Service Bundle & Gateway Controls ---
    // override fun announceService(serviceAnnouncement: ServiceAnnouncement, signedBundle: ByteArray, callback: (Result<Unit>) -> Unit) {
    //     try {
    //         myNode?.announceService(serviceAnnouncement, signedBundle)
    //         callback(Result.success(Unit))
    //     } catch (e: Exception) {
    //         callback(Result.failure(e))
    //     }
    // }
    // override fun requestServiceBundle(serviceId: String, requesterOnionAddress: String, callback: (Result<ByteArray?>) -> Unit) {
    //     try {
    //         val result = myNode?.requestServiceBundle(serviceId, requesterOnionAddress)
    //         callback(Result.success(result))
    //     } catch (e: Exception) {
    //         callback(Result.failure(e))
    //     }
    // }
    private var onGatewayTraffic: ((packet: VirtualPacket) -> Boolean)? = null
    override fun setOnGatewayTraffic(handler: (packet: VirtualPacket) -> Boolean) {
        onGatewayTraffic = handler
    }
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun getMeshTrafficRouterStatus(): String = "Inactive" // {
        // val router = myNode?.getMeshTrafficRouter()
        // return if (router != null) "Active: ${router.javaClass.name}" else "Inactive"
    // }

    // --- Event/Callback Integration ---
    private var onMeshStateChanged: ((MeshStateDto) -> Unit)? = null
    private var onPeerCountChanged: ((Int) -> Unit)? = null
    // private var onServiceBundleReceived: ((String, ByteArray) -> Unit)? = null
    // private var onServiceAnnounced: ((String, ServiceAnnouncement) -> Unit)? = null
    private var onGossipMessage: ((Int, ByteArray) -> Unit)? = null
    private var onTaskStatusUpdate: ((String, String) -> Unit)? = null  // Section 9

    override fun setOnMeshStateChanged(handler: (newState: MeshStateDto) -> Unit) {
        onMeshStateChanged = handler
    }
    override fun setOnPeerCountChanged(handler: (newCount: Int) -> Unit) {
        onPeerCountChanged = handler
    }
    // override fun setOnServiceBundleReceived(handler: (serviceId: String, bundle: ByteArray) -> Unit) {
    //     onServiceBundleReceived = handler
    // }
    // override fun setOnServiceAnnounced(handler: (serviceId: String, announcement: ServiceAnnouncement) -> Unit) {
    //     onServiceAnnounced = handler
    // }
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun setOnGossipMessage(handler: (senderId: Int, messageBytes: ByteArray) -> Unit) {
        onGossipMessage = handler
        // myNode?.addGossipListener(handler)
    }
    
    /**
     * Section 9: Set task status update callback
     * Wired through MeshEcosystemListener when TaskCompletedMessage received
     */
    override fun setOnTaskStatusUpdate(handler: (taskId: String, status: String) -> Unit) {
        onTaskStatusUpdate = handler
    }
    
    /**
     * Section 9: Internal method called by MeshEcosystemListener to trigger callback
     * This provides a public accessor for the listener to invoke the callback
     */
    fun triggerTaskStatusUpdate(taskId: String, status: String) {
        onTaskStatusUpdate?.invoke(taskId, status)
    }

    // --- TaskType enablement API ---
    override fun isTaskTypeEnabled(taskType: TaskTypeDto): Boolean {
        return MeshrabiyaConstants.isTaskTypeEnabled(taskType.toInternal())
    }

    override fun setTaskTypeEnabled(taskType: TaskTypeDto, enabled: Boolean) {
        MeshrabiyaConstants.setTaskTypeEnabled(taskType.toInternal(), enabled)
    }

    override fun getAllTaskTypeEnabled(): Map<TaskTypeDto, Boolean> {
        return MeshrabiyaConstants.getAllTaskTypeEnabled()
            .mapNotNull { (key, value) ->
                try {
                    key.toDto() to value
                } catch (e: IllegalArgumentException) {
                    // Optionally log: println("TaskType $key not present in TaskTypeDto")
                    null
                }
            }.toMap()
    }


    // --- User Identity API Implementation ---
    // Allow provider to be injected for JVM testability; default to AndroidKeyStore
    private var keyProvider: String =
        if (System.getProperty("java.vendor")?.contains("Android") == true) "AndroidKeyStore" else "BC"

    fun setKeyProviderForTest(provider: String) {
        keyProvider = provider
    }

    
    override fun getUserInfo(): User {
        println("[DEBUG] MeshrabiyaApiImpl.getUserInfo: keyProvider='$keyProvider'")
        val keypair = UserKeyManager.getKeypair(provider = keyProvider)
        println("[DEBUG] MeshrabiyaApiImpl.getUserInfo: keypair=$keypair")
        if (keypair == null) throw IllegalStateException("User keypair not initialized (provider='$keyProvider')")
        val publicKey = keypair.public
        val userId = publicKey.toHash()
        val nickname = MeshrabiyaConstants.getNickname() ?: ""
        println("[DEBUG] MeshrabiyaApiImpl.getUserInfo: userId='$userId', nickname='$nickname'")
        val userEntry = RecipientEntry(
            publicKey = java.util.Base64.getEncoder().encodeToString(publicKey.encoded),
            recipientType = RecipientType.USER,
            recipientId = userId
        )
        return User(userId, publicKey, nickname, keypair, userEntry)
    }

        // TODO update setUserNickname to update user object
    override fun setUserNickname(nickname: String) {
        println("[DEBUG] MeshrabiyaApiImpl.setUserNickname: nickname='$nickname'")
        MeshrabiyaConstants.setNickname(nickname)
    }

    // TODO update rotateUserKey to update user object
    override fun rotateUserKey(): User  {
        val context = getAppContext() ?: throw IllegalStateException("App context not set")
        println("[DEBUG] MeshrabiyaApiImpl.rotateUserKey: keyProvider='$keyProvider'")
        val keypair = UserKeyManager.rotateKeypair(context, provider = keyProvider)
        println("[DEBUG] MeshrabiyaApiImpl.rotateUserKey: keypair=$keypair")
        val publicKey = keypair.public
        val userId = publicKey.toHash()
        println("[DEBUG] MeshrabiyaApiImpl.rotateUserKey: new userId='$userId'")
        MeshrabiyaConstants.setUserId(userId)
        MeshrabiyaConstants.setUserPublicKey(android.util.Base64.encodeToString(publicKey.encoded, android.util.Base64.DEFAULT))
         val userEntry = RecipientEntry(
            publicKey = java.util.Base64.getEncoder().encodeToString(publicKey.encoded),
            recipientType = RecipientType.USER,
            recipientId = userId
        )
        return User(userId, publicKey, MeshrabiyaConstants.getNickname() ?: "", keypair, userEntry)
    }

    /**
     * Initialize user info on first run: generate keypair, set nickname, store userId and nickname.
     * Should be called during app startup or mesh initialization.
     */
    fun initializeUser(context: Context, nicknameProvider: (() -> String)? = null) {
        // Check if userId is already set
        val userId = MeshrabiyaConstants.getUserId()
        println("[DEBUG] MeshrabiyaApiImpl.initializeUser: userId='$userId', keyProvider='$keyProvider'")
        if (userId.isNullOrEmpty()) {
            println("[DEBUG] MeshrabiyaApiImpl.initializeUser: Generating keypair")
            val keypair = UserKeyManager.generateKeypair(context, provider = keyProvider)
            println("[DEBUG] MeshrabiyaApiImpl.initializeUser: Generated keypair: $keypair")
            val publicKey = keypair.public
            val newUserId = publicKey.toHash()
            println("[DEBUG] MeshrabiyaApiImpl.initializeUser: newUserId='$newUserId'")
            MeshrabiyaConstants.setUserId(newUserId)
            // Prompt for nickname or set default
            val nickname = nicknameProvider?.invoke() ?: "MeshUser"
            println("[DEBUG] MeshrabiyaApiImpl.initializeUser: nickname='$nickname'")
            MeshrabiyaConstants.setNickname(nickname)
        }
    }

        // --- Network Overview Metrics StateFlow ---
    // private val _networkOverviewMetricsFlow = MutableStateFlow(NetworkOverviewMetricsDto())
    // val networkOverviewMetricsFlow: StateFlow<NetworkOverviewMetricsDto> = _networkOverviewMetricsFlow.asStateFlow()

    // private var lastUploadBytes: Long = 0L
    // private var lastDownloadBytes: Long = 0L
    // private var lastTimestamp: Long = System.currentTimeMillis()

    

    // private fun getActiveNodeCount(): Int {
    //     // Implement logic to count active nodes in the mesh
    //     return meshNodeList.size // or other logic as appropriate
    // }

    // --- Broadcast Message+File Operations ---
    // Added: 2026-02-01 for NETWORK_BROADCAST_v2 implementation
    
    override suspend fun broadcastMessageAndFile(
        messageText: String,
        filePath: String,
        latitude: Double?,
        longitude: Double?
    ) {
        // Validate at least one input provided
        if (messageText.isEmpty() && filePath.isEmpty()) {
            val error = IllegalArgumentException("Either message or file must be provided")
            onBroadcastFailed?.invoke("", error)
            throw error
        }
        
        // Validate message length
        if (messageText.length > MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH) {
            val error = IllegalArgumentException(
                "Message exceeds ${MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH} character limit"
            )
            onBroadcastFailed?.invoke("", error)
            throw error
        }
        
        
        
        // Validate mesh is running
        val handler = broadcastHandler
        if (handler == null) {
            val error = IllegalStateException("Mesh is not running")
            onBroadcastFailed?.invoke("", error)
            throw error
        }
        
        // Delegate to handler with callback that invokes event handlers
        handler.sendBroadcast(messageText, filePath, latitude, longitude) { result ->
            if (result.isSuccess) {
                val broadcastResult = result.getOrNull()
                if (broadcastResult != null) {
                    onBroadcastSent?.invoke(broadcastResult)
                }
            } else {
                val error = result.exceptionOrNull() ?: Exception("Unknown error")
                onBroadcastFailed?.invoke("", error)
            }
        }
    }
    
   override fun registerBroadcastListener(listener: (com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit) {
        pendingBroadcastListeners.add(listener)
        broadcastHandler?.addReceiveListener(listener)
        Log.d(TAG, "Registered broadcast listener (persistent=${pendingBroadcastListeners.size})")
    }
    
   override fun unregisterBroadcastListener(listener: (com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit) {
        pendingBroadcastListeners.remove(listener)
        broadcastHandler?.removeReceiveListener(listener)
    }

    /**
     * Apply all queued listeners to the broadcast handler
     * Called automatically when broadcastHandler is initialized
     * Added: 2026-02-15 for deferred listener registration
     */
    private fun applyPendingBroadcastListeners() {
        val handler = broadcastHandler ?: return
        pendingBroadcastListeners.forEach { handler.addReceiveListener(it) }
        if (pendingBroadcastListeners.isNotEmpty()) {
            Log.d(TAG, "Applied ${pendingBroadcastListeners.size} persistent broadcast listeners to new handler")
        }
    }

    // ========================================
    override suspend fun connectToNonMeshWifi(ssid: String, passphrase: String): NonMeshWifiConnectionStateDto {
        Log.i(TAG, "[NONMESH] connectToNonMeshWifi start ssid='$ssid' passphrasePresent=${passphrase.isNotEmpty()} meshInitialized=${myNode != null}")

        // do **not** abort just because the mesh hasn’t been started;
        // the caller asked for a plain Wi‑Fi connection.
        // keep the hotspot‑self check though.
        getHotspotInfo()?.ssid?.let { current ->
            if (current == ssid) {
                val failed = NonMeshWifiConnectionStateDto(
                    status = NonMeshWifiStatusDto.FAILED,
                    errorMessage = "Cannot connect to own hotspot"
                )
                Log.w(TAG, "[NONMESH] abort – cannot connect to own hotspot ($ssid)")
                _nonMeshWifiState.value = failed
                return failed
            }
        }

        _nonMeshWifiState.value =
            NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.CONNECTING)
        val result = try {
            myNode?.meshrabiyaWifiManager
                ?.connectToInternetWifi(ssid, passphrase)
                ?: Result.failure(IllegalStateException("Mesh node unavailable"))
        } catch (e: Exception) {
            Log.e(TAG, "[NONMESH] exception from manager", e)
            Result.failure(e)
        }

        if (result.isSuccess) {
            Log.i(TAG, "[NONMESH] manager reported success for $ssid")
            _nonMeshWifiState.value = NonMeshWifiConnectionStateDto(
                status = NonMeshWifiStatusDto.CONNECTED,
                connectedSsid = ssid,
            )
            val finalState = try {
                withTimeout(10_000) {
                    _nonMeshWifiState.first { it.hasInternetAccess }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "[NONMESH] validation timeout for $ssid")
                _nonMeshWifiState.value
            }
            Log.i(TAG, "[NONMESH] final state for $ssid = $finalState")
            _networkInfoFlow.value = getNetworkInfo()   // immediate UI update
            return finalState
        } else {
            val error = result.exceptionOrNull()
            Log.w(TAG, "[NONMESH] connection failed for $ssid", error)
            val failed = NonMeshWifiConnectionStateDto(
                status = NonMeshWifiStatusDto.FAILED,
                errorMessage = error?.message,
            )
            _nonMeshWifiState.value = failed
            _networkInfoFlow.value = getNetworkInfo()
            return failed
        }
    }

    override suspend fun disconnectFromNonMeshWifi(): Boolean {
        val node = myNode ?: return false
        // Notify mesh peers before dropping internet WiFi if this node acts as a gateway
        val roles = emergentRoleManager?.getCurrentMeshRoles() ?: emptySet()
        if (roles.any { it == MeshRole.TOR_GATEWAY || it == MeshRole.CLEARNET_GATEWAY }) {
            node.broadcastGatewayDown()
        }
        node.meshrabiyaWifiManager.disconnectFromInternetWifi()
        _nonMeshWifiState.value = NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.IDLE)
        _networkInfoFlow.value = getNetworkInfo()
        return true
    }

    override fun getNonMeshWifiStateFlow(): StateFlow<NonMeshWifiConnectionStateDto> {
        return _nonMeshWifiState.asStateFlow()
    }

    override suspend fun scanAvailableWifiNetworks(): List<NonMeshWifiNetworkDto> {
        val ctx = appContext ?: return emptyList()
        val wifiManager = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        // Block until the OS signals SCAN_RESULTS_AVAILABLE (guarantees fresh results).
        // Falls back to cached results if broadcast doesn't arrive within 5 s.
        val scanCompleted = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                }
                ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
                cont.invokeOnCancellation {
                    try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
                }
                @Suppress("DEPRECATION")
                wifiManager.startScan()
            }
        }
        if (scanCompleted == null) {
            Log.w(TAG, "scanAvailableWifiNetworks: scan broadcast timed out, using cached results")
        }
        @Suppress("DEPRECATION")
        val results = wifiManager.scanResults ?: return emptyList()
        val list = results
            .filter { it.SSID.isNotEmpty() }
            .map { scanResult ->
                NonMeshWifiNetworkDto(
                    ssid = scanResult.SSID,
                    bssid = scanResult.BSSID,
                    signalStrength = scanResult.level,
                    isSecured = scanResult.capabilities.contains("WPA") ||
                                scanResult.capabilities.contains("WEP"),
                )
            }
            .sortedByDescending { it.signalStrength }
        Log.i(TAG, "scanAvailableWifiNetworks: found ${list.size} SSIDs ${list.map{it.ssid}}")
        return list
    }

    override fun isInternetWifiFeatureAvailable(): Boolean {
        val node = myNode ?: return false
        val wifiState = node.meshrabiyaWifiManager.currentWifiState
        if (wifiState.hotspotIsStarted && wifiState.concurrentApStationSupported) {
            return true
        }
        if (!wifiState.hotspotIsStarted &&
            wifiState.wifiStationState.status == com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState.Status.AVAILABLE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            wifiState.staStaConcurrencySupported) {
            return true
        }
        return false
    }

    override fun isWifiEnabled(): Boolean {
        return myNode?.meshrabiyaWifiManager?.isWifiEnabled() ?: false
    }

}

## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/model/NetworkInfo.kt
package com.ustadmobile.meshrabiya.model

/**
 * Represents information about the current mesh network state.
 * V3: Enhanced with gateway type breakdown (Tor, clearnet)
 */
data class NetworkInfo(
    val ssid: String = "",
    val bssid: String = "",
    val ipAddress: String = "",
    val connectedPeers: Int = 0,
    val isConnected: Boolean = false,
    
    // Phase 3B: Gateway statistics
    val torGateways: Int = 0,
    val clearnetGateways: Int = 0,
) {
    /**
     * Total gateway nodes (Tor + clearnet).
     * Note: Some nodes may advertise both roles, so this may not equal unique gateway count.
     */
    val totalGateways: Int
        get() = torGateways + clearnetGateways
}

## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt
@file:Suppress("DEPRECATION") // WifiConfiguration needed for pre-API 30 device support

package com.ustadmobile.meshrabiya.vnet.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier

import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ustadmobile.meshrabiya.ext.addOrLookupNetwork
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.bssidDataStore
import com.ustadmobile.meshrabiya.ext.firstOrNull
import com.ustadmobile.meshrabiya.ext.requireHostAddress
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.util.findFreePort
import com.ustadmobile.meshrabiya.vnet.VirtualNodeDatagramSocket
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import com.ustadmobile.meshrabiya.vnet.WifiRole
import kotlinx.coroutines.delay
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketFactory
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketServer
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid.OnNewWifiConnectionListener
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import android.net.wifi.WifiNetworkSuggestion
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.net.LinkProperties
import java.net.Inet4Address
import android.content.pm.PackageManager

/**
 *
 */
class MeshrabiyaWifiManagerAndroid(
    private val appContext: Context,
    private val logger: MNetLogger,
    private val localNodeAddr: Int,
    private val router: VirtualNode,
    private val chainSocketFactory: ChainSocketFactory,
    private val ioExecutor: ExecutorService,
    private val onNewWifiConnectionListener: OnNewWifiConnectionListener = OnNewWifiConnectionListener { },
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val wifiDirectManager: WifiDirectManager = WifiDirectManager(
        appContext = appContext,
        logger = logger,
        localNodeAddr = localNodeAddr,
        router = router,
        dataStore = dataStore,
        json = json,
        ioExecutorService = ioExecutor,
    ),

    
) : Closeable, MeshrabiyaWifiManager {

    private val logPrefix = "[MeshrabiyaWifiManagerAndroid: ${localNodeAddr.addressToDotNotation()}] "

    private val nodeScope = CoroutineScope(Dispatchers.Main + Job())

    private inner class ConnectNetworkCallback(
        private val config: WifiConnectConfig
    ): NetworkCallback() {
        override fun onAvailable(network: Network) {
            logger(Log.DEBUG, "$logPrefix connectToHotspot: connection available. Network=$network")
            _state.update { prev ->
                prev.copy(
                    wifiStationState = prev.wifiStationState.copy(
                        status = WifiStationState.Status.AVAILABLE,
                        network = network,
                    )
                )
            }
            nodeScope.launch {
                try {
                    createStationNetworkBoundSockets(network, config)
                }catch(e: Exception) {
                    logger(Log.ERROR, "$logPrefix ConnectNetworkCallback: Exception creating station sockets", e)
                }
            }
        }

        override fun onUnavailable() {
            logger(Log.WARN, "$logPrefix [NET_CB] onUnavailable: ssid=${config.ssid} sdk=${Build.VERSION.SDK_INT}")
            _state.update { prev ->
                prev.copy(
                    wifiStationState = prev.wifiStationState.copy(
                        status = WifiStationState.Status.UNAVAILABLE,
                    )
                )
            }
        }

        override fun onLost(network: Network) {
            logger(Log.WARN, "$logPrefix [NET_CB] onLost: ssid=${config.ssid} network=$network")
            _state.update { prev ->
                prev.copy(
                    wifiStationState = prev.wifiStationState.copy(
                        status = WifiStationState.Status.LOST,
                    )
                )
            }
            // Auto-reconnect after a brief settling delay to handle sleep/wake disconnects.
            // WifiNetworkSpecifier requests may not re-fire onAvailable automatically on all
            // devices/OEM builds when WiFi reconnects at the OS level after sleep.
            if (!closed.get()) {
                nodeScope.launch {
                    delay(3000)
                    if (!closed.get() && _state.value.wifiStationState.status == WifiStationState.Status.LOST) {
                        logger(Log.INFO, "$logPrefix [NET_CB] onLost: auto-reconnect attempt for ${config.ssid}")
                        try {
                            connectToHotspotInternal(config)
                        } catch (e: Exception) {
                            logger(Log.WARN, "$logPrefix [NET_CB] onLost: auto-reconnect failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    fun interface OnNewWifiConnectionListener {
        fun onNewWifiConnection(connectEvent: WifiConnectEvent)
    }


    private val connectivityManager: ConnectivityManager = appContext.getSystemService(
        ConnectivityManager::class.java
    )

    private val wifiManager: WifiManager = appContext.getSystemService(WifiManager::class.java)
    
    /**
     * Helper function to convert integer IP to readable string format
     */
    private fun intToIpString(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }
    
    /**
     * Log detailed WiFi state for diagnostics
     */
    private fun logDetailedWifiState(prefix: String) {
        try {
            val info = wifiManager.connectionInfo
            val dhcpInfo = wifiManager.dhcpInfo
            
            logger(Log.INFO, "$prefix WiFi State:")
            logger(Log.INFO, "  networkId: ${info.networkId}")
            logger(Log.INFO, "  SSID: ${info.ssid}")
            logger(Log.INFO, "  BSSID: ${info.bssid}")
            logger(Log.INFO, "  IP: ${info.ipAddress} (${intToIpString(info.ipAddress)})")
            logger(Log.INFO, "  LinkSpeed: ${info.linkSpeed} Mbps")
            logger(Log.INFO, "  RSSI: ${info.rssi}")
            logger(Log.INFO, "  Gateway: ${intToIpString(dhcpInfo.gateway)}")
            logger(Log.INFO, "  DNS1: ${intToIpString(dhcpInfo.dns1)}")
            
            // List all configured networks
            val configured = wifiManager.configuredNetworks
            logger(Log.INFO, "  Configured Networks: ${configured?.size ?: 0}")
            configured?.forEachIndexed { index, config ->
                logger(Log.INFO, "    [$index] ${config.SSID} (id=${config.networkId}, status=${config.status})")
            }
        } catch (e: Exception) {
            logger(Log.ERROR, "$prefix Failed to log WiFi state", e)
        }
    }

    private val _state = MutableStateFlow(MeshrabiyaWifiState(
        concurrentApStationSupported = false  // Start with false, detect asynchronously in init
    ))

    private val localOnlyHotspotManager: LocalOnlyHotspotManager = LocalOnlyHotspotManager(
        appContext = appContext,
        logger = logger,
        name = localNodeAddr.addressToDotNotation(),
        localNodeAddr = localNodeAddr,
        router = router,
        dataStore = dataStore,
        concurrentApStationSupported = { _state.value.concurrentApStationSupported },
    )

    // implement required interface property
    override val apCapable: Boolean
        get() = _state.value.apCapable

    override val state: Flow<MeshrabiyaWifiState> = _state.asStateFlow()

    /**
     * When this device is connected as a station, we will create a new DatagramSocket and
     * ChainSocketServer that is bound to the Android Network object. This helps prevent older
     * versions of Android from disconnecting when it realizes the connection has no Internet
     * (e.g. Android will see activity on the network).
     */
    private val stationBoundSockets = AtomicReference<Pair<VirtualNodeDatagramSocket, ChainSocketServer>?>()

    /** Synchronous read of AP+STA concurrency support flag (API 30+). */
    val concurrentApStationSupported: Boolean
        get() = _state.value.concurrentApStationSupported

    /** Synchronous read of STA/STA concurrency support flag (API 31+). */
    val staStaConcurrencySupported: Boolean
        get() = _state.value.staStaConcurrencySupported

    /** Returns true if the Android WiFi radio is currently enabled. All SDK versions. */
    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled


    /** Synchronous snapshot of the current WiFi state. */
    val currentWifiState: MeshrabiyaWifiState
        get() = _state.value

    /**
     * Holds the Network object for the current internet WiFi connection.
     * Set by connectToInternetWifi() and cleared by disconnectFromInternetWifi().
     * Used by ClearnetGatewayForwarder to bind outbound sockets to the internet interface.
     */
    @Volatile
    var internetWifiNetwork: Network? = null
        private set

    /** NetworkCallback registered for the internet WiFi connection. Cleared on disconnect. */
    @Volatile
    private var internetWifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /** Active network suggestions for internet WiFi; stored for removal in disconnectFromInternetWifi(). */
    private var activeInternetWifiSuggestions: List<WifiNetworkSuggestion> = emptyList()

    data class InternetWifiNetworkState(
        val network: Network? = null,
        val hasInternetAccess: Boolean = false,
        val ipAddress: String? = null,
    )

    private val _internetWifiNetworkState = MutableStateFlow(InternetWifiNetworkState())

    val internetWifiNetworkStateFlow: kotlinx.coroutines.flow.StateFlow<InternetWifiNetworkState> =
        _internetWifiNetworkState.asStateFlow()

    private val closed = AtomicBoolean(false)

    private var wifiLock: WifiManager.WifiLock? = null

    private val connectRequest = AtomicReference<Pair<WifiConnectConfig, NetworkCallback>?>(null)

    init {
        wifiDirectManager.onBeforeGroupStart = WifiDirectManager.OnBeforeGroupStart {
            // Do nothing - in future may need to stop other WiFi stuff
        }

        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "meshrabiya").also {
            it.acquire()
        }

        nodeScope.launch {
            wifiDirectManager.state.collect {
                _state.update { prev ->
                    prev.copy(
                        wifiDirectState = it,
                        wifiRole = if(it.config != null) {
                            WifiRole.WIFI_DIRECT_GROUP_OWNER
                        }else if(prev.wifiRole == WifiRole.WIFI_DIRECT_GROUP_OWNER) {
                            WifiRole.NONE
                        }else {
                            prev.wifiRole
                        }
                    )
                }
            }
        }

        nodeScope.launch {
            localOnlyHotspotManager.state.collect { hotspotState ->
                _state.update { prev ->
                    prev.copy(
                        localOnlyHotspotState = hotspotState
                    )
                }
                
                // Note: We don't create a separate hotspot socket - the main VirtualNodeDatagramSocket
                // receives packets on all interfaces. OriginatingMessageManager will handle sending
                // broadcasts appropriately when hotspot is active.
            }
        }

        // Detect concurrent AP+Station support and AP capability after WiFi system initialization
        nodeScope.launch {
            val (apStaSupported, staStaSupported) = detectWifiConcurrencyCapabilities()
            val apCap = detectApCapability()
            _state.update { prev ->
                prev.copy(
                    concurrentApStationSupported = apStaSupported,
                    staStaConcurrencySupported = staStaSupported,
                    apCapable = apCap,
                )
            }
            logger(Log.INFO, "$logPrefix WiFi concurrency: AP+STA=$apStaSupported, STA+STA=$staStaSupported, APcapable=$apCap")
        }

    }

    /**
     * Detect if device supports concurrent AP+Station mode.
     * Delays briefly to ensure WiFi system is fully initialized before querying capability.
     */
    /**
     * Detect device WiFi concurrency capabilities.
     * Returns Pair(concurrentApStationSupported, staStaConcurrencySupported).
     */
    private suspend fun detectWifiConcurrencyCapabilities(): Pair<Boolean, Boolean> {
        delay(WIFI_CONCURRENCY_DETECT_INIT_DELAY_ANDROID_MS) // brief delay for WiFi system initialization

        val apStaSupported = if (Build.VERSION.SDK_INT >= 30) {
            val result = wifiManager.isStaApConcurrencySupported
            logger(Log.INFO, "$logPrefix isStaApConcurrencySupported = $result (SDK ${Build.VERSION.SDK_INT})")
            result
        } else {
            logger(Log.INFO, "$logPrefix AP+STA not supported: SDK ${Build.VERSION.SDK_INT} < 30")
            false
        }

        val staStaSupported = if (Build.VERSION.SDK_INT >= 31) {
            val result = wifiManager.isStaConcurrencyForLocalOnlyConnectionsSupported
            logger(Log.INFO, "$logPrefix isStaConcurrencyForLocalOnlyConnectionsSupported = $result (SDK ${Build.VERSION.SDK_INT})")
            result
        } else {
            logger(Log.INFO, "$logPrefix STA/STA not supported: SDK ${Build.VERSION.SDK_INT} < 31")
            false
        }

        return apStaSupported to staStaSupported
    }

    // helper added in MeshrabiyaWifiManagerAndroid class
    private suspend fun detectApCapability(): Boolean {
        // check hardware/OS feature – compile SDK may not declare FEATURE_WIFI_AP
        val hasFeature = appContext.packageManager
            .hasSystemFeature("android.hardware.wifi.accesspoint")
        logger(Log.INFO, "$logPrefix detectApCapability: hasSystemFeature(wifi.accesspoint)=$hasFeature")
        if (hasFeature) return true

        // Fallback: query the AP state machine via reflection.
        // IMPORTANT: use getWifiApState() NOT isWifiApEnabled().
        // isWifiApEnabled() returns current-on/off state (false at boot even on capable devices).
        // getWifiApState() returns a state constant (10–14) even when AP is off:
        //   DISABLING=10, DISABLED=11, ENABLING=12, ENABLED=13, FAILED=14
        // Any value in that range means the device has AP hardware support.
        val wifiManager = appContext.getSystemService(WifiManager::class.java)
            ?: return false
        return try {
            val method = WifiManager::class.java.getDeclaredMethod("getWifiApState")
            method.isAccessible = true
            val apState = method.invoke(wifiManager) as? Int ?: -1
            val capable = apState in 10..14
            logger(Log.INFO, "$logPrefix detectApCapability: getWifiApState()=$apState, apCapable=$capable")
            capable
        } catch (e: Exception) {
            logger(Log.WARN, "$logPrefix detectApCapability: reflection failed (${e.javaClass.simpleName}: ${e.message}), assuming not AP-capable")
            false
        }
    }

    private fun assertNotClosed() {
        if(closed.get())
            throw IllegalStateException("$logPrefix is closed!")
    }

    override val is5GhzSupported: Boolean
        get() = wifiManager.is5GHzBandSupported


    override suspend fun requestHotspot(
        requestMessageId: Int,
        request: LocalHotspotRequest
    ): LocalHotspotResponse {
        assertNotClosed()

        logger(Log.DEBUG, "$logPrefix requestHotspot requestId=$requestMessageId", null)

        // Check if concurrent AP+STA is supported
        val currentState = _state.value
        if (!currentState.concurrentApStationSupported && currentState.wifiStationState.status != WifiStationState.Status.INACTIVE) {
            logger(Log.INFO, "$logPrefix Concurrent AP+STA not supported, disconnecting from WiFi before starting hotspot", null)
            // Disconnect from WiFi first
            withContext(Dispatchers.Main) {
                try {
                    wifiManager.disconnect()
                    logger(Log.DEBUG, "$logPrefix WiFi disconnected successfully", null)
                    // Give it a moment to disconnect
                    delay(WIFI_CLIENT_DISCONNECT_SETTLE_DELAY_ANDROID_MS)
                } catch (e: Exception) {
                    logger(Log.WARN, "$logPrefix Failed to disconnect WiFi: ${e.message}", e)
                }
            }
        }

        /**
         * The user might explicityl specify WifiDirect or Localonlyhotspot. If so, honor that
         * request.
         */
        fun HotspotType.overrideWithRequestTypeIfSpecified(): HotspotType? {
            return HotspotType.forceTypeIfSpecified(
                specifiedType = request.preferredType,
                autoType = this,
            )
        }

        val spotTypeCreated = withContext(Dispatchers.Main) {

            val prevState = _state.getAndUpdate { prev ->
                when(prev.hotspotTypeToCreate?.overrideWithRequestTypeIfSpecified()) {
                    HotspotType.WIFIDIRECT_GROUP -> prev.copy(
                        wifiDirectState = prev.wifiDirectState.copy(
                            hotspotStatus = HotspotStatus.STARTING
                        )
                    )

                    else -> prev
                }
            }

            when(prevState.hotspotTypeToCreate?.overrideWithRequestTypeIfSpecified()) {
                HotspotType.WIFIDIRECT_GROUP -> {
                    localOnlyHotspotManager.stopLocalOnlyHotspot(waitForStop = true)
                    wifiDirectManager.startWifiDirectGroup(request.preferredBand)
                }
                HotspotType.LOCALONLY_HOTSPOT -> {
                    wifiDirectManager.stopWifiDirectGroup()
                    localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand, request.preferredPassphrase)
                }
                else -> {
                    //Do nothing
                }
            }

            prevState.hotspotTypeToCreate
        }

        val configResult = _state.filter {
            it.hotspotIsStarted || spotTypeCreated != null && it.hotspotError(spotTypeCreated) != 0
        }.first()

        return LocalHotspotResponse(
            responseToMessageId = requestMessageId,
            errorCode = spotTypeCreated?.let { configResult.hotspotError(it) } ?: 0,
            config = configResult.connectConfig,
            redirectAddr = 0
        )
    }

    override suspend fun deactivateHotspot() {
        assertNotClosed()

        wifiDirectManager.stopWifiDirectGroup()
        localOnlyHotspotManager.stopLocalOnlyHotspot(waitForStop = false)
    }

    /**
     * Connect to the given hotspot as a station.
     */
    @Suppress("DEPRECATION") //Must use deprecated classes to support pre-SDK29
    private suspend fun connectToHotspotInternal(
        config: WifiConnectConfig,
    ): Network {
        logger(Log.INFO,
            "$logPrefix Connecting to hotspot: ssid=${config.ssid} passphrase=${config.passphrase} bssid=${config.bssid}"
        )

        val networkCallback = ConnectNetworkCallback(config)

        val networkRequest = if(Build.VERSION.SDK_INT >= 29) {
            //Use the suggestion API as per https://developer.android.com/guide/topics/connectivity/wifi-bootstrap
            /*
             * Dialog behavior notes
             *
             * On Android 11+ if the network is in the CompanionDeviceManager approved list (which
             * works on the basis of BSSID only), then no approval dialog will be shown:
             * See:
             * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r1:frameworks/opt/net/wifi/service/java/com/android/server/wifi/WifiNetworkFactory.java;l=1321
             *
             * On Android 10:
             * No WifiNetworkFactory uses a list of approved access points. The BSSID, SSID, and
             * network type must match.
             * See:
             * https://cs.android.com/android/platform/superproject/+/android-10.0.0_r47:frameworks/opt/net/wifi/service/java/com/android/server/wifi/WifiNetworkFactory.java;l=1224
             */
            logger(Log.DEBUG, "$logPrefix connectToHotspot: building network specifier", null)
            val bssid = config.bssid ?: config.linkLocalToMacAddress?.toString()
            val specifier = WifiNetworkSpecifier.Builder()
                .apply {
                    setSsid(config.ssid)
                    if(bssid != null)
                        setBssid(MacAddress.fromString(bssid))

                    //Normally it would be nice to set the band here to speed up connection (avoid
                    //the need to scan other bands).
                    //
                    //Testing on Android 13 / Samsung Tab: specifying the band caused connection to fail
                    //Will receive callback that network is available followed immediately by unavailable callback
                    //Thanks, Google.
                }
                .setWpa2Passphrase(config.passphrase)
                .build()

            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

                .setNetworkSpecifier(specifier)
                .build()
        }else {
            //use pre-Android 10 WifiManager API
            val wifiConfig = WifiConfiguration().apply {
                SSID =  "\"${config.ssid}\""
                preSharedKey = "\"${config.passphrase}\""
                hiddenSSID = true
            }
            val configNetworkId = wifiManager.addOrLookupNetwork(wifiConfig, logger)
            @Suppress("DEPRECATION")
            val currentlyConnectedNetworkId = wifiManager.connectionInfo.networkId
            logger(Log.DEBUG, "$logPrefix connectToHotspot: Currently connected to networkId: $currentlyConnectedNetworkId", null)

            if(currentlyConnectedNetworkId == configNetworkId) {
                logger(Log.DEBUG, "$logPrefix connectToHotspot: Already connected to target networkid", null)
            }else {
                //If currently connected to another network, we need to disconnect.
                wifiManager.takeIf { currentlyConnectedNetworkId != -1 }?.disconnect()
                wifiManager.enableNetwork(configNetworkId, true)
            }

            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        }

        logger(Log.DEBUG, "$logPrefix connectToHotspot: requesting network for ${config.ssid}", null)
        val prevRequest = connectRequest.getAndUpdate {
            config to networkCallback
        }

        prevRequest?.second?.also {
            logger(Log.DEBUG, "$logPrefix connectToHotspot: unregister previous callback: $it")
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {
                logger(Log.WARN, "$logPrefix connectToHotspot: previous callback already unregistered (watchdog or prior failure) — continuing")
            }
        }

        logger(Log.INFO, "$logPrefix [NET_CB] registering requestNetwork: ssid=${config.ssid} sdk=${Build.VERSION.SDK_INT}")
        connectivityManager.requestNetwork(networkRequest, networkCallback)

        _state.update { prev ->
            prev.copy(
                wifiStationState = prev.wifiStationState.copy(
                    status = WifiStationState.Status.CONNECTING,
                    config = config,
                    network = null,
                    stationBoundSocketsPort = -1,
                )
            )
        }

        val resultState = _state.map { it.wifiStationState }.filter {
            it.status != WifiStationState.Status.CONNECTING
        }.first()

        if (resultState.network != null) {
            logger(Log.INFO, "$logPrefix connectToHotspot: ${config.ssid} - success status=${resultState.status}")

            val bindSuccess = connectivityManager.bindProcessToNetwork(resultState.network)
            logger(Log.INFO, "$logPrefix connectToHotspot: bindProcessToNetwork result=$bindSuccess", null)
            if (!bindSuccess) {
                logger(Log.WARN, "$logPrefix connectToHotspot: Failed to bind process to mesh network - device may switch networks", null)
            }

            return resultState.network
        }else {
            logger(Log.ERROR, "$logPrefix connectToHotspot: ${config.ssid} - fail status=${resultState.status}")
            throw WifiConnectException("ConnectToHotspot: ${config.ssid} status=${resultState.status} network=null")
        }
    }

    /**
     * Connect to an internet (non-mesh) WiFi network while the mesh remains active.
     *
     * AP+STA mode (hotspot running): requires API 30 + isStaApConcurrencySupported = true.
     * STA/STA mode (Join Mesh, no hotspot): requires API 31 + isStaStaConcurrencySupported = true.
     *
     * On success: stores the resulting Network in [internetWifiNetwork] for per-socket binding
     * by ClearnetGatewayForwarder. Does NOT call bindProcessToNetwork.
     */
    suspend fun connectToInternetWifi(ssid: String, passphrase: String): Result<Network> {
        val currentState = _state.value
        val hotspotRunning = currentState.hotspotIsStarted

        if (hotspotRunning) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return Result.failure(IllegalStateException(
                    "Internet WiFi while hotspot running requires API 30+ (AP+STA). Device SDK: ${Build.VERSION.SDK_INT}"
                ))
            }
            if (!currentState.concurrentApStationSupported) {
                return Result.failure(IllegalStateException(
                    "This device does not support concurrent AP+STA mode (isStaApConcurrencySupported = false)"
                ))
            }
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return Result.failure(IllegalStateException(
                    "Internet WiFi while in Join Mesh mode requires API 31+ (STA/STA). Device SDK: ${Build.VERSION.SDK_INT}"
                ))
            }
            if (!currentState.staStaConcurrencySupported) {
                return Result.failure(IllegalStateException(
                    "This device does not support simultaneous dual-STA mode (isStaStaConcurrencySupported = false)"
                ))
            }
        }

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .apply {
                if (passphrase.isNotEmpty()) {
                    setWpa2Passphrase(passphrase)
                }
            }
            .build()

        val suggestionList = listOf(suggestion)
        wifiManager.removeNetworkSuggestions(activeInternetWifiSuggestions) // clear any stale suggestion
        val addResult = wifiManager.addNetworkSuggestions(suggestionList)
        if (addResult != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS &&
            addResult != WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
            return Result.failure(IllegalStateException(
                "connectToInternetWifi: addNetworkSuggestions failed, status=$addResult"
            ))
        }
        activeInternetWifiSuggestions = suggestionList
        wifiManager.startScan() // request immediate scan so suggestion is acted upon quickly

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        logger(Log.INFO, "$logPrefix connectToInternetWifi: suggestion added for SSID=$ssid, awaiting primary STA connection, hotspotRunning=$hotspotRunning")

        return suspendCancellableCoroutine { continuation ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    logger(Log.INFO, "$logPrefix connectToInternetWifi: onAvailable: SSID=$ssid network=$network")
                    internetWifiNetwork = network
                    val ipAddress = connectivityManager.getLinkProperties(network)
                        ?.linkAddresses
                        ?.firstOrNull { it.address is Inet4Address && !it.address.isLinkLocalAddress }
                        ?.address?.hostAddress
                    _internetWifiNetworkState.value = InternetWifiNetworkState(
                        network = network,
                        hasInternetAccess = false,
                        ipAddress = ipAddress,
                    )
                    if (continuation.isActive) {
                        continuation.resume(Result.success(network))
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val validated = networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                    logger(Log.INFO, "$logPrefix connectToInternetWifi: onCapabilitiesChanged: SSID=$ssid validated=$validated")
                    val ipAddress = connectivityManager.getLinkProperties(network)
                        ?.linkAddresses
                        ?.firstOrNull { it.address is Inet4Address && !it.address.isLinkLocalAddress }
                        ?.address?.hostAddress
                    _internetWifiNetworkState.update { prev ->
                        prev.copy(
                            hasInternetAccess = validated,
                            ipAddress = ipAddress ?: prev.ipAddress,
                        )
                    }
                }

                override fun onLost(network: Network) {
                    logger(Log.WARN, "$logPrefix connectToInternetWifi: onLost: network=$network")
                    if (internetWifiNetwork == network) {
                        internetWifiNetwork = null
                    }
                    _internetWifiNetworkState.value = InternetWifiNetworkState()
                }

                override fun onUnavailable() {
                    logger(Log.WARN, "$logPrefix connectToInternetWifi: onUnavailable for SSID=$ssid")
                    _internetWifiNetworkState.value = InternetWifiNetworkState()
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(IllegalStateException(
                            "connectToInternetWifi: network unavailable for SSID=$ssid after 60s"
                        )))
                    }
                }
            }

            internetWifiNetworkCallback = callback
            connectivityManager.requestNetwork(networkRequest, callback, 60_000)

            continuation.invokeOnCancellation {
                connectivityManager.unregisterNetworkCallback(callback)
                wifiManager.removeNetworkSuggestions(activeInternetWifiSuggestions)
                activeInternetWifiSuggestions = emptyList()
                internetWifiNetwork = null
                internetWifiNetworkCallback = null
            }
        }
    }

    /**
     * Disconnect from the internet WiFi connection and clear all tracking state.
     */
    fun disconnectFromInternetWifi() {
        if (activeInternetWifiSuggestions.isNotEmpty()) {
            wifiManager.removeNetworkSuggestions(activeInternetWifiSuggestions)
            activeInternetWifiSuggestions = emptyList()
        }
        val callback = internetWifiNetworkCallback
        if (callback != null) {
            connectivityManager.unregisterNetworkCallback(callback)
            internetWifiNetworkCallback = null
        }
        internetWifiNetwork = null
        _internetWifiNetworkState.value = InternetWifiNetworkState()
        logger(Log.INFO, "$logPrefix disconnectFromInternetWifi: removed suggestion, cleared internet WiFi network and callback")
    }

    data class InternetWifiSignalInfo(
        val rssiDbm: Int = 0,
        val linkSpeedMbps: Int = 0,
    )

    @Suppress("DEPRECATION")
    fun getInternetWifiSignalInfo(): InternetWifiSignalInfo {
        if (internetWifiNetwork == null) return InternetWifiSignalInfo()
        val info = wifiManager.connectionInfo ?: return InternetWifiSignalInfo()
        return InternetWifiSignalInfo(
            rssiDbm = info.rssi,
            linkSpeedMbps = info.linkSpeed,
        )
    }

    override suspend fun connectToHotspot(
        config: WifiConnectConfig,
        timeout: Long,
    ) {
        if(config.band == ConnectBand.BAND_5GHZ && !wifiManager.is5GHzBandSupported) {
            throw WifiConnectException("ERROR: 5Ghz not supported by device: ${config.ssid} uses 5Ghz band")
        }

        withTimeout(timeout) {
            connectToHotspotInternal(config)

            val resultState = _state.filter {
                it.wifiStationState.stationBoundSocketsPort != -1 || it.wifiStationState.status in WifiStationState.Status.FAIL_STATES
            }.first()
            val stationStatus = resultState.wifiStationState.status

            if(stationStatus in WifiStationState.Status.FAIL_STATES) {
                throw WifiConnectException("Attempted to connect to ${config.ssid}, status=$stationStatus")
            }
        }
    }

    /**
     * Disconnect the client station connection - remove the network request, close sockets. If
     * the station mode is already inactive, this will have no effect.
     */
    suspend fun disconnectStation() {
        logger(Log.ERROR, "$logPrefix ========== disconnectStation() CALLED ==========")
        logger(Log.ERROR, "$logPrefix This log MUST appear if function is called")
        
        // Log detailed state BEFORE disconnect
        logDetailedWifiState("$logPrefix [BEFORE DISCONNECT]")
        
        val prevState = _state.getAndUpdate { prev ->
            if(prev.wifiStationState.status != WifiStationState.Status.INACTIVE) {
                prev.copy(
                    wifiStationState = prev.wifiStationState.copy(
                        status = WifiStationState.Status.INACTIVE,
                    )
                )
            }else {
                prev
            }
        }

        if(prevState.wifiStationState.status != WifiStationState.Status.INACTIVE) {
            val prevNetworkCallback = connectRequest.getAndUpdate {
                null
            }

            val previousSockets = stationBoundSockets.getAndUpdate {
                null
            }

            try {
                previousSockets?.also {
                    withContext(Dispatchers.IO) {
                        it.first.close()
                        it.second.close()
                        logger(Log.DEBUG, "$logPrefix : disconnectStation: closed sockets")
                    }
                }
            }catch(e: Exception) {
                logger(Log.WARN, "$logPrefix : disconnectionStation: exception closing sockets", e)
            }

            try {
                prevNetworkCallback?.second?.also {
                    connectivityManager.unregisterNetworkCallback(it)
                    logger(Log.DEBUG, "$logPrefix unregistered network request callback")
                }
                
                // CRITICAL: Unbind network so device can use regular WiFi again
                connectivityManager.bindProcessToNetwork(null)
                logger(Log.DEBUG, "$logPrefix disconnectStation: unbound process from mesh network", null)
                
            }catch(e: Exception) {
                logger(Log.WARN, "$logPrefix disconnectStation: exception unregistering network callback")
            }
        }
        
        // CRITICAL FIX: Actually disconnect from WiFi using WifiManager with verification loop
        try {
            val currentNetworkId = wifiManager.connectionInfo?.networkId ?: -1
            val currentSSID = wifiManager.connectionInfo?.ssid ?: "null"
            val wasConnected = currentNetworkId != -1
            
            logger(Log.INFO, "$logPrefix disconnectStation: WiFi connection status BEFORE disconnect: networkId=$currentNetworkId, SSID=$currentSSID")
            
            if (wasConnected) {
                // CRITICAL: On Android 10+, apps cannot programmatically disable WiFi
                // Attempting to disable WiFi will succeed silently but Android ignores it
                // The ONLY solution is to instruct the user to manually disable WiFi
                
                logger(Log.ERROR, "$logPrefix disconnectStation: ❌ CRITICAL: Device is connected to WiFi ($currentSSID)")
                logger(Log.ERROR, "$logPrefix disconnectStation: ❌ Android prevents apps from disabling WiFi programmatically")
                logger(Log.ERROR, "$logPrefix disconnectStation: ❌ User MUST manually disable WiFi in Settings before starting mesh")
                
                throw IllegalStateException(
                    "❌ Cannot start mesh hotspot while WiFi is enabled.\n\n" +
                    "📱 Please manually disable WiFi in Android Settings:\n" +
                    "   Settings → Network & Internet → WiFi → Turn OFF\n\n" +
                    "Currently connected to: $currentSSID\n\n" +
                    "Why? Android prevents apps from disabling WiFi for security reasons. " +
                    "The hotspot and WiFi cannot run simultaneously on this device."
                )
            } else {
                logger(Log.INFO, "$logPrefix disconnectStation: WiFi was not connected (networkId=-1)")
                // Still disable WiFi to prevent reconnection during hotspot operation
                @Suppress("DEPRECATION")
                if (wifiManager.isWifiEnabled) {
                    logger(Log.INFO, "$logPrefix disconnectStation: Disabling WiFi subsystem to prevent reconnection")
                    try {
                        wifiManager.isWifiEnabled = false
                        delay(WIFI_SUBSYSTEM_DISABLE_SETTLE_DELAY_ANDROID_MS)
                        logger(Log.INFO, "$logPrefix disconnectStation: WiFi subsystem disabled successfully")
                    } catch (e: SecurityException) {
                        logger(Log.ERROR, "$logPrefix disconnectStation: PERMISSION DENIED - Cannot disable WiFi", e)
                        throw IllegalStateException("Cannot disable WiFi - permission denied. Please manually disable WiFi in Android Settings before starting mesh.", e)
                    } catch (e: Exception) {
                        logger(Log.ERROR, "$logPrefix disconnectStation: FAILED to disable WiFi subsystem", e)
                        throw IllegalStateException("Failed to disable WiFi. Please manually disable WiFi in Android Settings before starting mesh.", e)
                    }
                }
            }
        } catch (e: IllegalStateException) {
            // Re-throw WiFi disable failures with clear message
            throw e
        } catch (e: Exception) {
            logger(Log.ERROR, "$logPrefix disconnectStation: Exception during WiFi disconnect", e)
            throw e
        }
        
        // Update state to clear station configuration
        _state.update { prev ->
            prev.copy(
                wifiStationState = prev.wifiStationState.copy(
                    config = null,
                    network = null,
                    stationBoundSocketsPort = -1,
                    stationBoundDatagramSocket = null,
                )
            )
        }
    }

    private suspend fun createBoundSocket(
        port: Int, bindAddress:
        InetAddress?,
        maxAttempts: Int,
        interval: Long = SOCKET_BIND_RETRY_INTERVAL_ANDROID_MS,
    ): DatagramSocket {
        for(i in 0 until maxAttempts) {
            try {
                return DatagramSocket(port, bindAddress).also {
                    logger(Log.DEBUG, "$logPrefix : createBoundSocket: success after ${i+1} attempts")
                }
            }catch(e: Exception) {
                delay(interval)
            }
        }

        logger(Log.WARN, "$logPrefix : createBoundSocket: failed after $maxAttempts")
        throw IllegalStateException("createBoundSocket: failed after $maxAttempts")
    }

    /**
     * Create a datagramsocket that is bound to the the network object for the wifi station network.
     *
     * Binding to the network object (network.bindSocket etc) helps to avoid Android deciding to
     * disconnect from the network because it doesn't have Internet access. This is especially true
     * on older versions (pre-Android 10) where we use WifiManager itself to connect to the network
     * (without user intervention). On Android 10+ because the connection required user approval,
     * this behavior does not seem to be as prevalent.
     */
    private suspend fun createStationNetworkBoundSockets(network: Network, config: WifiConnectConfig) {
        withContext(Dispatchers.IO) {
            val linkProperties = connectivityManager
                .getLinkProperties(network)
            val networkInterface = NetworkInterface.getByName(linkProperties?.interfaceName)

            val interfaceInet6Addrs = networkInterface.inetAddresses.toList()
            logger(Log.INFO, "$logPrefix : connectToHotspot - addrs = ${interfaceInet6Addrs.joinToString()}")

            val netAddress = networkInterface.inetAddresses.firstOrNull {
                it is Inet6Address && it.isLinkLocalAddress
            }

            logger(Log.INFO, "$logPrefix : connectToHotspot: Got link local address = " +
                    "$netAddress on interface ${linkProperties?.interfaceName}", null)

            val socketPort = findFreePort(0)

            val socket = if(config.hotspotType == HotspotType.WIFIDIRECT_GROUP) {
                /**
                 * When using a Wifi Direct group we MUST use the LinkLocal IPv6 address to the
                 * IPv4 conflict issue - where all WiFi Direct group owners are assigned 192.168.49.1
                 *  See README
                 *
                 * Strange issue: Android 13 (perhaps not exclusively) will not bind (immediately)
                 * to link local ipv6 addr for station network. This can take longer if the WiFi
                 * direct group has been created. It will bind eventually, so we can retry at short
                 * intervals until its ready.
                 *
                 * If the socket is created before this is ready, it wont even send traffic via the
                 * link local address.
                 */
                try {
                    createBoundSocket(socketPort, netAddress, WIFI_DIRECT_SOCKET_BIND_MAX_ATTEMPTS_ANDROID).also {
                        logger(Log.DEBUG, "$logPrefix : createStationNetworkBoundSockets : succeeded on retry")
                    }
                }catch(e: IOException) {
                    logger(Log.ERROR, "$logPrefix : createStationNetworkBoundSockets : " +
                            "Exception trying to create bound sockets. Cannot continue", e
                    )
                    throw e
                }
            }else {
                /**
                 * LocalOnlyHotspot IP address ranges are randomized and do not appear to suffer from
                 * this issue.
                 */
                DatagramSocket(socketPort)
            }

            network.bindSocket(socket)

            val networkBoundDatagramSocket = VirtualNodeDatagramSocket(
                socket = socket,
                localNodeVirtualAddress = localNodeAddr,
                ioExecutorService = ioExecutor,
                router = router,
                logger = logger,
                name = "network bound to ${config.ssid}",
                boundNetwork = network,
            )

            val chainSocketServer = ChainSocketServer(
                serverSocket = ServerSocket(socketPort),
                executorService = ioExecutor,
                chainSocketFactory = chainSocketFactory,
                name = "network bound to ${config.ssid}",
                logger = logger,
            )

            val previousSockets = stationBoundSockets.getAndUpdate {
                networkBoundDatagramSocket to chainSocketServer
            }

            previousSockets?.first?.close()
            previousSockets?.second?.close(true)

            logger(Log.INFO, "$logPrefix : addWifiConnection:Created network bound port on ${networkBoundDatagramSocket.localPort}", null)
            _state.update { prev ->
                prev.copy(
                    wifiRole = if(config.hotspotType == HotspotType.LOCALONLY_HOTSPOT) {
                        WifiRole.WIFI_DIRECT_GROUP_OWNER
                    }else {
                        WifiRole.CLIENT
                    },
                    wifiStationState = prev.wifiStationState.copy(
                        stationBoundDatagramSocket = networkBoundDatagramSocket,
                        stationBoundSocketsPort = socketPort,
                    )
                )
            }

            val peerAddr = config.linkLocalAddr?.let {
                logger(Log.DEBUG,
                    "$logPrefix : createStationBoundSockets: determining peer address using " +
                            "linkLocalAddr supplied in config")
                Inet6Address.getByAddress(it.requireHostAddress(), it.address, networkInterface)
            } ?: if(Build.VERSION.SDK_INT >= 30) {
                logger(Log.DEBUG, "$logPrefix - createStationBoundSockets : determining peer " +
                        "address using linkProperties.dhcpServerAddress")
                linkProperties?.dhcpServerAddress
            }else {
                logger(Log.DEBUG, "$logPrefix - createStationBoundSockets : determining peer " +
                        "address using wifimanager.dhcpInfo")
                @Suppress("DEPRECATION") //Must use deprecated property to support PRE-SDK30
                wifiManager.dhcpInfo?.serverAddress?.let {
                    //Strangely - seems like these are Little Endian
                    InetAddress.getByAddress(
                        ByteBuffer.wrap(ByteArray(4))
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(it)
                            .array()
                    )
                }
            }

            logger(Log.DEBUG, "$logPrefix : addWifiConnectionConnect: Peer address is: $peerAddr", null)

            if(peerAddr != null) {
                //Once connected,
                onNewWifiConnectionListener.onNewWifiConnection(WifiConnectEvent(
                    neighborPort = config.port,
                    neighborInetAddress = peerAddr,
                    socket = networkBoundDatagramSocket,
                    neighborVirtualAddress = config.nodeVirtualAddr,
                ))
            }
        }
    }

    override fun close() {
        if(!closed.getAndSet(true)) {
            nodeScope.cancel()
            wifiDirectManager.close()
            wifiLock?.also {
                it.release()
            }
            
            // Re-enable WiFi subsystem when mesh stops
            @Suppress("DEPRECATION")
            if (!wifiManager.isWifiEnabled) {
                logger(Log.INFO, "$logPrefix close: Re-enabling WiFi subsystem")
                wifiManager.isWifiEnabled = true
            }
        }
    }

    /**
     * Create network-bound sockets for the hotspot interface. This is critical for mesh discovery
     * because it ensures broadcast packets from the hotspot host are sent on the correct interface
     * and can be received by joining nodes with network-bound sockets.
     * 
     * Without this, the hotspot's broadcasts go to the default network interface and never reach
     * joining nodes that are listening on their network-bound hotspot interface.
     *
     * NOTE: This method has been removed. The main VirtualNodeDatagramSocket already receives
     * packets on all interfaces. We cannot create a separate socket on the same port as it causes
     * EADDRINUSE errors. Instead, OriginatingMessageManager sends broadcasts when hotspot is active.
     */

    suspend fun lookupStoredBssid(ssid: String) : String? {
        val prefKey = stringPreferencesKey("${PREFIX_SSID}$ssid")

        return appContext.bssidDataStore.data.map {
            it[prefKey]
        }.first().also {
            logger(Log.DEBUG, "MeshrabiyaWifiManagerAndroid: lookupStoredBssid ssid=$ssid bssid=$it")
        }

    }

    suspend fun storeBssidForAddress(ssid: String, bssid: String) {
        logger(Log.DEBUG, "MeshrabiyaWifiManagerAndroid: storeBssidForAddress ssid=$ssid bssid=$bssid")
        val prefKey = stringPreferencesKey("${PREFIX_SSID}$ssid")
        appContext.bssidDataStore.edit {
            it[prefKey] = bssid
        }
        logger(Log.DEBUG, "MeshrabiyaWifiManagerAndroid: storeBssidForAddress ssid=$ssid bssid=$bssid : Done")
    }

    companion object {

        const val PREFIX_SSID = "ssid_"

        const val HOTSPOT_TIMEOUT = 10000L

        const val WIFI_DIRECT_SERVICE_TYPE = "_meshr._tcp"

        /**
         * Settle delay (ms) before querying Android WiFi concurrency APIs in
         * detectWifiConcurrencyCapabilities(). Gives WifiManager time to fully initialize
         * before isStaApConcurrencySupported / isStaStaConcurrencySupported are called.
         * _ANDROID suffix: Android-platform-specific timing, not a mesh protocol value.
         */
        const val WIFI_CONCURRENCY_DETECT_INIT_DELAY_ANDROID_MS = 200L

        /**
         * Settle delay (ms) after `wifiManager.disconnect()` in requestHotspot().
         * Allows the Android WiFi client association to fully drop before the hotspot starts.
         * _ANDROID suffix: Android-platform-specific timing, not a mesh protocol value.
         */
        const val WIFI_CLIENT_DISCONNECT_SETTLE_DELAY_ANDROID_MS = 500L

        /**
         * Settle delay (ms) after `wifiManager.isWifiEnabled = false` in disconnectStation().
         * Allows the Android WiFi subsystem to fully shut down before continuing.
         * _ANDROID suffix: Android-platform-specific timing, not a mesh protocol value.
         */
        const val WIFI_SUBSYSTEM_DISABLE_SETTLE_DELAY_ANDROID_MS = 500L

        /**
         * Default retry interval (ms) between socket bind attempts in createBoundSocket().
         * On Android, link-local IPv6 addresses on the WiFi Direct station interface may not
         * be immediately available after network bring-up; short retries cover the window.
         * _ANDROID suffix: Android-platform-specific timing, not a mesh protocol value.
         */
        const val SOCKET_BIND_RETRY_INTERVAL_ANDROID_MS = 200L

        /**
         * Maximum socket bind attempts in createBoundSocket() for WiFi Direct connections.
         * Android 13+ may delay link-local IPv6 address assignment on the station interface;
         * retrying at SOCKET_BIND_RETRY_INTERVAL_ANDROID_MS intervals covers the window.
         * _ANDROID suffix: Android-platform-specific count, not a mesh protocol value.
         */
        const val WIFI_DIRECT_SOCKET_BIND_MAX_ATTEMPTS_ANDROID = 10

    }

}
