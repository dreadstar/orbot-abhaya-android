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
import com.ustadmobile.meshrabiya.api.model.NetworkInfoDto
import com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto
import com.ustadmobile.meshrabiya.api.model.MeshRoleDto
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
	private enum class LocationRequestOrigin { NONE, START_MESH, BROADCAST }
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
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Setting up listeners...")
		setupListeners()
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Listeners setup complete")
		
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
					// Optionally update other UI elements as needed
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
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Network info observer setup complete")
		
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
					latestNetworkInfo?.let { networkInfo: NetworkInfoDto ->
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
		// Refresh UI when fragment becomes visible (tab switches, screen rotation, etc.)
		updateUI()
		
		// Start periodic UI updates (every 2 seconds) to refresh peer count and network stats
		viewLifecycleOwner.lifecycleScope.launch {
			while (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
				kotlinx.coroutines.delay(2000) // Update every 2 seconds
				updateUI()
			}
		}

		viewLifecycleOwner.lifecycleScope.launch {
			while (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
				val status = meshrabiyaApi.getMeshStatus()
				
				kotlinx.coroutines.delay(10_000) // Every 10 seconds
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
                    val meshState = meshrabiyaApi.getMeshStatus()
                    val meshStarted = meshState == MeshStateDto.CONNECTED || meshState == MeshStateDto.CONNECTING
                    
                    android.util.Log.d("EnhancedMeshFragment", "[ROLE_OBSERVER] Updating meshRolesText (meshState=$meshState, meshStarted=$meshStarted)")
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
					
					val uiUpdateDuration = System.currentTimeMillis() - uiUpdateStart
					android.util.Log.d("EnhancedMeshFragment", "[ROLE_OBSERVER] ✓ UI update completed in ${uiUpdateDuration}ms")
					
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
		
		// Send Broadcast button
		MeshUIBindings.sendBroadcastButton.setOnClickListener {
			showBroadcastDialog()
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
		if (!checkLocationPermissions()) {
			android.util.Log.d("EnhancedMeshFragment", "Permissions not granted, requesting now")
			locationRequestOrigin = LocationRequestOrigin.START_MESH
			requestLocationPermissions()
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
				// Disable broadcast button when disconnected
				MeshUIBindings.sendBroadcastButton.isEnabled = false
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
				// Disable broadcast button while connecting
				MeshUIBindings.sendBroadcastButton.isEnabled = false
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
				// Enable broadcast button when CONNECTED
				MeshUIBindings.sendBroadcastButton.isEnabled = true
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
				// Disable broadcast button in error states
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
}