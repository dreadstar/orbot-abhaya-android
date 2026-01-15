package org.torproject.android.ui.mesh

import org.torproject.android.R
// import com.ustadmobile.meshrabiya.model.MeshState

import android.Manifest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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


/**
 * EnhancedMeshFragment: Mesh UI fragment using MeshrabiyaApi for all mesh logic.
 * All mesh operations are routed through MeshrabiyaApi. No deprecated logic is used.
 */
class EnhancedMeshFragment : Fragment() {

	private lateinit var meshrabiyaApi: MeshrabiyaApi
	
	// Folder picker for storage allocation
	private lateinit var folderPickerLauncher: ActivityResultLauncher<Uri?>
	private var selectedFolderUri: Uri? = null
	
	// Flags to prevent recursive toggle updates from programmatic changes
	private var isStorageToggleProgrammatic = false
	private var isServiceToggleProgrammatic = false
	
	companion object {
		private const val PREF_STORAGE_FOLDER_URI = "mesh_storage_folder_uri"
		private const val PREF_STORAGE_QUOTA_BYTES = "mesh_storage_quota_bytes"
		private const val DEFAULT_STORAGE_QUOTA = 100_000_000L // 100MB default
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
		bindUI(view)
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
		
		// Initial UI update to show current mesh state
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Calling updateUI()...")
		updateUI()
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] ===== onViewCreated() COMPLETE =====")
	}

	override fun onResume() {
		super.onResume()
		// Refresh UI when fragment becomes visible (tab switches, screen rotation, etc.)
		updateUI()
	}

	private fun bindUI(view: View) {
		// Use MeshUIBindings.bindViews() to ensure all views are bound correctly
		MeshUIBindings.bindViews(view)
	}
	
	/**
	 * Setup observer for mesh roles StateFlow to automatically update UI when roles change
	 */
	private fun setupRoleObserver() {
		viewLifecycleOwner.lifecycleScope.launch {
			(meshrabiyaApi as? MeshrabiyaApiImpl)?.currentMeshRolesFlow?.collect { roles ->
				android.util.Log.e("EnhancedMeshFragment", "[ROLE_OBSERVER] Roles changed: $roles")
				activity?.runOnUiThread {
					// Update roles text - show "Roles: --" when mesh not started or no roles determined yet
					val meshState = meshrabiyaApi.getMeshStatus()
					val meshStarted = meshState == MeshStateDto.CONNECTED || meshState == MeshStateDto.CONNECTING
					
					MeshUIBindings.meshRolesText.text = if (!meshStarted) {
						"Roles: --" // Show label with placeholder when mesh not started
					} else if (roles.isNotEmpty()) {
						"Roles: ${roles.joinToString(", ")}"
					} else {
						"Roles: --" // Show label with placeholder when no roles determined yet
					}
					
					// Also update gateway status texts to reflect current persisted settings
					val torGatewayEnabled = meshrabiyaApi.getTorGatewayStatus()
					MeshUIBindings.torGatewayStatus.text = if (torGatewayEnabled) "Enabled" else "Disabled"
					
					val internetGatewayEnabled = meshrabiyaApi.getInternetGatewayStatus()
					MeshUIBindings.internetGatewayStatus.text = if (internetGatewayEnabled) "Enabled" else "Disabled"
					
					android.util.Log.e("EnhancedMeshFragment", "[ROLE_OBSERVER] UI updated - meshStarted: $meshStarted, roles: ${roles.joinToString(", ")}, Tor: $torGatewayEnabled, Internet: $internetGatewayEnabled")
				}
			}
		}
	}

	private fun setupListeners() {
		// Example: Service Layer Participation Toggle
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
						// Revert toggle on error
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

		// Storage participation toggle with role update
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
						// Revert toggle on error
						isStorageToggleProgrammatic = true
						MeshUIBindings.storageParticipationToggle.isChecked = !isChecked
						isStorageToggleProgrammatic = false
					}
				}
			}
		}

		// Storage allocation slider - save value when changed
		MeshUIBindings.storageAllocationSlider.addOnChangeListener { _, value, fromUser ->
			if (fromUser) {
				val quotaBytes = (value * 1024 * 1024 * 1024).toLong() // Convert GB to bytes
				requireActivity().getPreferences(android.content.Context.MODE_PRIVATE).edit()
					.putLong(PREF_STORAGE_QUOTA_BYTES, quotaBytes)
					.apply()
				android.util.Log.d("EnhancedMeshFragment", "Storage quota updated to: ${value}GB ($quotaBytes bytes)")
				
				// Update the allocation text
				MeshUIBindings.storageAllocationText.text = "${value.toInt()} GB"
				
				// If storage participation is enabled, refresh the configuration
				if (meshrabiyaApi.getStorageParticipationStatus()) {
					meshrabiyaApi.setStorageParticipationEnabled(true) { _ ->
						android.util.Log.i("EnhancedMeshFragment", "Storage participation refreshed with new quota: ${value}GB")
					}
				}
			}
		}
		
		// Drop folder selection - open folder picker
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
			// Show dialog to create folder (simplified implementation)
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

		// Mesh toggle button with debouncing and permission checks
		var meshOperationInProgress = false
		MeshUIBindings.meshToggleButton.setOnClickListener {
			android.util.Log.d("EnhancedMeshFragment", "Mesh toggle button clicked")
			
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
				if (checkLocationPermissions()) {
					android.util.Log.d("EnhancedMeshFragment", "Permissions already granted, calling startMesh()")
					meshrabiyaApi.startMesh { result ->
						// Callback runs on background thread - must switch to main thread for UI updates
						activity?.runOnUiThread {
							android.util.Log.d("EnhancedMeshFragment", "startMesh callback: success=${result.isSuccess}, error=${result.exceptionOrNull()}")
							if (result.isFailure) {
								android.util.Log.e("EnhancedMeshFragment", "startMesh failed", result.exceptionOrNull())
								view?.let { v ->
									Snackbar.make(v, "Failed to start mesh: ${result.exceptionOrNull()?.message}", Snackbar.LENGTH_LONG).show()
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
						Snackbar.make(v, "Failed to start mesh: ${result.exceptionOrNull()?.message}", Snackbar.LENGTH_LONG).show()
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
		android.util.Log.d("EnhancedMeshFragment", "updateUI() called")
		
		// Ensure all UI updates happen on the main thread
		activity?.runOnUiThread {
			// Mesh status
			val meshState = meshrabiyaApi.getMeshStatus()
			android.util.Log.d("EnhancedMeshFragment", "Current mesh state: $meshState")
			MeshUIBindings.meshStatusText.text = meshState.toString()
			
			// Update button text based on current mesh state
			// Show "Stop Mesh" when mesh is active (CONNECTING or CONNECTED), "Start Mesh" when DISCONNECTED
			val meshActive = meshState == MeshStateDto.CONNECTED || meshState == MeshStateDto.CONNECTING
			MeshUIBindings.meshToggleButton.text = if (meshActive) "Stop Mesh" else "Start Mesh"
			android.util.Log.d("EnhancedMeshFragment", "Button text updated to: ${MeshUIBindings.meshToggleButton.text}")

			// Network Status - show local node IP address
			val networkInfo = meshrabiyaApi.getNetworkInfo()
			MeshUIBindings.nodeInfoText.text = if (networkInfo != null) {
				"IP: ${networkInfo.ipAddress}"
			} else {
				"Mesh not initialized"
			}

			// Mesh Roles - show current node roles with label
			// meshActive already declared above
			val roles = meshrabiyaApi.getNodeRoleNames()
			MeshUIBindings.meshRolesText.text = if (!meshActive) {
				"Roles: --" // Show label with placeholder when mesh not started
			} else if (roles.isNotEmpty()) {
				"Roles: ${roles.joinToString(", ")}"
			} else {
				"Roles: --" // Show label with placeholder when no roles determined yet
			}

			// Network Information - show detailed network stats
			MeshUIBindings.networkStatsText.text = if (networkInfo != null) {
				"Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
			} else {
				"No network data"
			}

			// Last update
			val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
			MeshUIBindings.lastUpdateText.text = "Last Updated: ${dateFormat.format(Date())}"

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
				android.util.Log.e("EnhancedMeshFragment", "[UI_UPDATE] Storage toggle loaded from dataStore: $storageEnabled")
			}
			MeshUIBindings.storageStatusText.text = if (storageEnabled) "Storage participation is enabled" else "Storage participation is disabled"
			
			// Service participation toggle (read from persisted dataStore)
			val serviceEnabled = meshrabiyaApi.getServiceParticipationStatus("compute_node")
			if (MeshUIBindings.serviceLayerParticipationSwitch.isChecked != serviceEnabled) {
				isServiceToggleProgrammatic = true
				MeshUIBindings.serviceLayerParticipationSwitch.isChecked = serviceEnabled
				isServiceToggleProgrammatic = false
				android.util.Log.e("EnhancedMeshFragment", "[UI_UPDATE] Service toggle loaded from dataStore: $serviceEnabled")
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
			android.util.Log.d("EnhancedMeshFragment", "Loaded storage quota from preferences: ${quotaGB}GB (clamped to ${clampedQuotaGB}GB)")

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
		}
	}
	
	/**
	 * Update storage allocation after folder selection
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
	// internal fun notifyDropFolderUpdate(changes: List<DropFolderItem>) {
	// 	onDropFolderUpdateHandler?.invoke(changes)
	// }
	private val onDropFolderUpdateHandler: (List<DropFolderItemDto>) -> Unit = { changes ->
		// Handle drop folder updates
		// For example, refresh the RecyclerView adapter with new data
		// val adapter = MeshUIBindings.folderContentsRecyclerView.adapter as DropFolderContentsAdapter
		// adapter.updateItems(changes)
	}
}
