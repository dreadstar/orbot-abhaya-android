package org.torproject.android.ui.mesh

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import org.torproject.android.R
import org.torproject.android.ui.mesh.adapter.FolderContentsAdapter
import org.torproject.android.ui.mesh.model.StorageItem
import com.ustadmobile.meshrabiya.api.MeshrabiyaApi
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import org.torproject.android.ui.mesh.MeshManagers
import org.torproject.android.ui.mesh.MeshUIBindings
import org.torproject.android.ui.mesh.MeshListeners
import org.torproject.android.ui.mesh.MeshStorageUI
import org.torproject.android.ui.mesh.MeshServiceLayerUI
import org.torproject.android.ui.mesh.MeshUtils

/**
 * Enhanced mesh networking fragment based on integration guidance.
 * Provides comprehensive mesh network management with:
 * - Network overview statistics
 * - Service monitoring and management
 * - Node information display
 * - Real-time status updates
 */
class EnhancedMeshFragment : Fragment(), org.torproject.android.GatewayCapabilitiesManager.GatewayCapabilityListener {
    private var serviceLayerCoordinator: org.torproject.android.service.compute.ServiceLayerCoordinator? = null
    private var isNetworkActive = false
    private var isUpdatingSliderProgrammatically = false
    private val timeFormatter = MeshUtils.timeFormatter

    // Activity result launcher for folder selection
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == androidx.appcompat.app.AppCompatActivity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFolder(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_mesh_enhanced, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        MeshUIBindings.bindViews(view)
        MeshManagers.setup(requireContext(), MeshUIBindings.folderContentsAdapter, MeshUIBindings.folderContentsRecyclerView)
        val meshrabiyaApi = MeshrabiyaApiImpl.getInstance(requireContext().applicationContext)
        MeshStorageUI.initializeStorageUI()
        initializeStorageDropFolderUI()
        MeshServiceLayerUI.initializeDistributedServiceLayerUI()
        MeshListeners.setupListeners(view, lifecycleScope)

        // --- BEGIN: Service Layer Participation Toggle Listener Integration ---
        val prefs = requireContext().getSharedPreferences("mesh_service_layer", Context.MODE_PRIVATE)
        val isComputeParticipating = prefs.getBoolean("compute_node_participation", false)
        MeshUIBindings.serviceLayerParticipationSwitch.isChecked = isComputeParticipating

        // Ensure API and event routing match persisted state
        meshrabiyaApi.setServiceParticipationEnabled("compute_node", isComputeParticipating) { /* handle result */ }

        // Add toggle listener for service layer participation
        MeshUIBindings.serviceLayerParticipationSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("compute_node_participation", isChecked).apply()
            meshrabiyaApi.setServiceParticipationEnabled("compute_node", isChecked) { /* handle result */ }
            updateServiceLayerStatus(isChecked)
        }
        // --- END: Service Layer Participation Toggle Listener Integration ---

        startPeriodicUpdates()
        updateUI()
    }
    
    private fun initializeViews(view: View) {
        // Status displays
        meshStatusText = view.findViewById(R.id.meshStatusText)
        nodeInfoText = view.findViewById(R.id.nodeInfoText)
        networkStatsText = view.findViewById(R.id.networkStatsText)
        lastUpdateText = view.findViewById(R.id.lastUpdateText)
        
        // Controls
        gatewayToggle = view.findViewById(R.id.gatewayToggle)
        internetGatewayToggle = view.findViewById(R.id.internetGatewayToggle)
        refreshButton = view.findViewById(R.id.refreshButton)
        meshToggleButton = view.findViewById(R.id.meshToggleButton)
        
        // Service cards
        torGatewayCard = view.findViewById(R.id.torGatewayCard)
        internetGatewayCard = view.findViewById(R.id.internetGatewayCard)
        networkOverviewCard = view.findViewById(R.id.networkOverviewCard)
        
        // Storage participation views
        storageParticipationCard = view.findViewById(R.id.storageParticipationCard)
        storageParticipationToggle = view.findViewById(R.id.storageParticipationToggle)
        storageAllocationSlider = view.findViewById(R.id.storageAllocationSlider)
        storageStatusText = view.findViewById(R.id.storageStatusText)
        storageAllocationText = view.findViewById(R.id.storageAllocationText)
        
        // Storage drop folder views
        storageDropFolderCard = view.findViewById(R.id.storageDropFolderCard)
        selectFolderButton = view.findViewById(R.id.selectFolderButton)
        createFolderButton = view.findViewById(R.id.createFolderButton)
        selectedFolderText = view.findViewById(R.id.selectedFolderText)
        folderContentsRecyclerView = view.findViewById(R.id.folderContentsRecyclerView)
        
        // Distributed service layer views
        distributedServiceLayerCard = view.findViewById(R.id.distributedServiceLayerCard)
        serviceLayerParticipationSwitch = view.findViewById(R.id.serviceLayerParticipationSwitch)
        serviceLayerStatusText = view.findViewById(R.id.serviceLayerStatusText)
        pythonServiceStatus = view.findViewById(R.id.pythonServiceStatus)
        mlInferenceServiceStatus = view.findViewById(R.id.mlInferenceServiceStatus)
        distributedStorageServiceStatus = view.findViewById(R.id.distributedStorageServiceStatus)
        taskSchedulerServiceStatus = view.findViewById(R.id.taskSchedulerServiceStatus)
        
        // Service status
        torGatewayStatus = view.findViewById(R.id.torGatewayStatus)
        internetGatewayStatus = view.findViewById(R.id.internetGatewayStatus)
        activeNodesText = view.findViewById(R.id.activeNodesText)
        networkLoadText = view.findViewById(R.id.networkLoadText)
        stabilityText = view.findViewById(R.id.stabilityText)
    }
    
    private fun setupManagers() {
        gatewayManager = GatewayCapabilitiesManager.getInstance(requireContext())
        gatewayManager.addListener(this)
        
        // Initialize mesh services
        meshCoordinator = MeshServiceCoordinator.getInstance(requireContext())
        meshCoordinator.initializeMeshService()

        
        // Initialize traffic router
        trafficRouter = MeshTrafficRouterImpl(requireContext())
        
        // Initialize storage drop folder manager
        storageDropFolderManager = StorageDropFolderManager.getInstance(requireContext())
        
        // Setup folder contents adapter
        folderContentsAdapter = FolderContentsAdapter { item ->
            onShareItemClicked(item)
        }
        folderContentsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = folderContentsAdapter
        }
        
        // TODO: Get virtual node from application
        // virtualNode = (requireActivity().application as OrbotApp).virtualNode
    }
    
    
    private fun startPeriodicUpdates() {
        lifecycleScope.launch {
            while (true) {
                updateNetworkStats()
                delay(5000)
            }
        }
    }
    
    private fun updateUI() {
        updateGatewayStatus()
        updateNetworkStats()
        updateNodeInformation()
        updateServiceCards()
        MeshStorageUI.updateStorageStatus()
        updateStorageDropFolderUI()
        updateDistributedServiceLayerUI()
        MeshUIBindings.lastUpdateText.text = "Last updated: ${timeFormatter.format(java.util.Date())}"
    }
    
    private fun updateDistributedServiceLayerUI() {
        // Always update service statuses to reflect current participation state
        // Delegate to MeshServiceLayerUI if needed
    }
    
    private fun updateGatewayStatus() {
        val status = MeshManagers.gatewayManager.getCurrentStatus()
        MeshUIBindings.gatewayToggle.isChecked = status.shareTor
        MeshUIBindings.internetGatewayToggle.isChecked = status.shareInternet
        MeshUIBindings.torGatewayStatus.text = when {
            status.shareTor && status.isTorAvailable -> "Active"
            status.shareTor && !status.isTorAvailable -> "Enabled (Tor Not Available)"
            else -> "Disabled"
        }
        MeshUIBindings.internetGatewayStatus.text = when {
            status.shareInternet && status.hasInternetConnection -> "Active"
            status.shareInternet && !status.hasInternetConnection -> "Enabled (No Internet)"
            else -> "Disabled"
        }
    }
    
    private fun updateNetworkStats() {
        val meshStatus = MeshManagers.meshCoordinator.getMeshServiceStatus()
        val healthCheck = MeshManagers.meshCoordinator.performHealthCheck()
        MeshUIBindings.activeNodesText.text = "${meshStatus.nodeCount} nodes"
        MeshUIBindings.networkLoadText.text = "${if (meshStatus.trafficBytes > 0) "Active" else "Idle"} traffic"
        MeshUIBindings.stabilityText.text = "${if (healthCheck.success) "Stable" else "Unstable"}"
        MeshUIBindings.meshStatusText.text = when {
            meshStatus.isRunning && meshStatus.nodeCount > 0 -> "Mesh network active with ${meshStatus.nodeCount} connected nodes"
            meshStatus.isRunning && meshStatus.nodeCount == 0 -> "Mesh network running, discovering peers..."
            !meshStatus.isRunning -> "Mesh network stopped"
            else -> "Mesh network status unknown"
        }
        isNetworkActive = meshStatus.isRunning
    }
    
    private fun updateNodeInformation() {
        val meshStatus = MeshManagers.meshCoordinator.getMeshServiceStatus()
        val currentRoles = MeshManagers.meshCoordinator.getCurrentlyAssignedRoles()
        val userPrefs = MeshManagers.meshCoordinator.getUserSharingPreferences()
        val virtualNode = MeshManagers.meshCoordinator.getVirtualNode()
        val nodeInfo = buildString {
            appendLine("Local Node Status:")
            if (virtualNode != null) {
                appendLine("• Node ID: ${virtualNode.addressAsInt}")
                appendLine("• Status: ${if (meshStatus.isRunning) "Connected" else "Disconnected"}")
                appendLine("• Gateway: ${if (meshStatus.isGateway) "Yes" else "No"}")
                if (currentRoles.isNotEmpty()) {
                    appendLine("• Current Roles: ${currentRoles.joinToString(", ")}")
                }
                appendLine("\nUser Preferences:")
                appendLine("• Tor Gateway: ${if (userPrefs["allowTorGateway"] == true) "Enabled" else "Disabled"}")
                appendLine("• Internet Gateway: ${if (userPrefs["allowInternetGateway"] == true) "Enabled" else "Disabled"}")  
                appendLine("• Storage Sharing: ${if (userPrefs["allowStorageSharing"] == true) "Enabled" else "Disabled"}")
            } else {
                appendLine("• Node: Not initialized")
            }
            if (meshStatus.isRunning && meshStatus.nodeCount > 0) {
                appendLine("\nNetwork Information:")
                appendLine("• Connected Nodes: ${meshStatus.nodeCount}")
                appendLine("• Traffic: ${meshStatus.trafficBytes} bytes")
                Log.d("EnhancedMeshFragment", "Network info display: nodeCount=${meshStatus.nodeCount}, trafficBytes=${meshStatus.trafficBytes}")
                virtualNode?.let { node ->
                    val neighbors = node.neighbors()
                    if (neighbors.isNotEmpty()) {
                        appendLine("\nConnected Peers:")
                        neighbors.take(5).forEach { (neighborId, _) ->
                            appendLine("• Node-${neighborId}: Active")
                        }
                    }
                }
            }
        }
        MeshUIBindings.nodeInfoText.text = nodeInfo
    }
    
    private fun updateServiceCards() {
        val activeColor = requireContext().getColor(R.color.bright_green)
        val inactiveColor = requireContext().getColor(R.color.panel_background_main)
        MeshUIBindings.torGatewayCard.setCardBackgroundColor(
            if (MeshManagers.gatewayManager.shareTor) activeColor else inactiveColor
        )
        MeshUIBindings.internetGatewayCard.setCardBackgroundColor(
            if (MeshManagers.gatewayManager.shareInternet) activeColor else inactiveColor
        )
        MeshUIBindings.networkOverviewCard.setCardBackgroundColor(
            if (isNetworkActive) activeColor else inactiveColor
        )
        updateButtonStates()
    }
    
    private fun updateButtonStates() {
        val enabledColor = requireContext().getColor(R.color.orbot_btn_enabled_purple)
        val whiteText = requireContext().getColor(android.R.color.white)
        if (isNetworkActive) {
            MeshUIBindings.meshToggleButton.text = "Stop Mesh"
            MeshUIBindings.meshToggleButton.setBackgroundColor(enabledColor)
            MeshUIBindings.meshToggleButton.setTextColor(whiteText)
            MeshUIBindings.meshToggleButton.isEnabled = true
        } else {
            MeshUIBindings.meshToggleButton.text = "Start Mesh"
            MeshUIBindings.meshToggleButton.setBackgroundColor(enabledColor)
            MeshUIBindings.meshToggleButton.setTextColor(whiteText)
            MeshUIBindings.meshToggleButton.isEnabled = true
        }
    }
    
    // Storage status now handled by MeshStorageUI
    
    // Storage UI initialization now handled by MeshStorageUI
    
    private fun startMeshNetwork() {
        lifecycleScope.launch {
            meshStatusText.text = "Starting mesh network..."
            meshToggleButton.isEnabled = false
            meshToggleButton.text = "Starting..."
            
            // Actually start the mesh networking through MeshServiceCoordinator
            val success = meshCoordinator.startMeshNetworking()
            
            if (success) {
                isNetworkActive = true
                meshStatusText.text = "Mesh network started successfully"
                
                // Update user sharing preferences based on UI toggles
                meshCoordinator.setUserSharingPreferences(
                    allowTorGateway = gatewayToggle.isChecked,
                    allowInternetGateway = internetGatewayToggle.isChecked,
                    allowStorageSharing = true, // Default to allowing storage sharing
                    storageAllocationGB = 5  // Default allocation
                )
                
                updateUI()
            } else {
                meshStatusText.text = "Failed to start mesh network"
                meshToggleButton.isEnabled = true
                meshToggleButton.text = "Start Mesh"
            }
        }
    }
    
    private fun stopMeshNetwork() {
        lifecycleScope.launch {
            meshStatusText.text = "Stopping mesh network..."
            meshToggleButton.isEnabled = false
            meshToggleButton.text = "Stopping..."
            
            // Actually stop the mesh networking through MeshServiceCoordinator
            val success = meshCoordinator.stopMeshNetworking()
            
            if (success) {
                isNetworkActive = false
                meshStatusText.text = "Mesh network stopped successfully"
            } else {
                meshStatusText.text = "Failed to stop mesh network"
            }
            
            updateUI()
        }
    }
    
    private fun updateNetworkInformation() {
        val meshStatus = meshCoordinator.getMeshServiceStatus()
        val healthCheck = meshCoordinator.performHealthCheck()
        val virtualNode = meshCoordinator.getVirtualNode()
        
        val networkInfo = buildString {
            appendLine("Network Interface Information:")
            if (virtualNode != null) {
                appendLine("• Mesh Interface: mesh0 (${virtualNode.addressAsInt})")
            } else {
                appendLine("• Mesh Interface: Not active")
            }
            
            appendLine("• Gateway Mode: ${when {
                gatewayManager.shareTor && gatewayManager.shareInternet -> "Tor + Internet"
                gatewayManager.shareTor -> "Tor"
                gatewayManager.shareInternet -> "Internet"
                else -> "None"
            }}")
            
            if (meshStatus.isRunning) {
                appendLine("\nMesh Statistics:")
                appendLine("• Connected Nodes: ${meshStatus.nodeCount}")
                appendLine("• Traffic Bytes: ${meshStatus.trafficBytes}")
                appendLine("• Network Latency: ${healthCheck.networkLatency}ms")
                appendLine("• Service Health: ${if (healthCheck.success) "Healthy" else "Issues Detected"}")
                
                Log.d("EnhancedMeshFragment", "Mesh statistics display: nodeCount=${meshStatus.nodeCount}, trafficBytes=${meshStatus.trafficBytes}, latency=${healthCheck.networkLatency}")
                
                val currentRoles = meshCoordinator.getCurrentlyAssignedRoles()
                if (currentRoles.isNotEmpty()) {
                    appendLine("• Active Roles: ${currentRoles.joinToString(", ")}")
                }
            }
        }
        
        networkStatsText.text = networkInfo
    }
    
    override fun onCapabilityChanged(status: GatewayCapabilitiesManager.GatewayStatus) {
        updateGatewayStatus()
    }
    
    // Storage Drop Folder Methods
    
    private fun initializeStorageDropFolderUI() {
        updateStorageDropFolderUI()
    }
    
    private fun updateStorageDropFolderUI() {
        val folderName = storageDropFolderManager.getSelectedFolderName()
        if (folderName != null) {
            selectedFolderText.text = getString(R.string.storage_folder_selected, folderName)
        } else {
            selectedFolderText.text = getString(R.string.storage_no_folder_selected)
        }
        
        // Update folder contents
        val contents = storageDropFolderManager.getFolderContents()
        folderContentsAdapter.submitList(contents)
    }
    
    private fun selectFolder() {
        // Launch the Storage Access Framework folder picker
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Optional: Set initial directory if available
            putExtra("android.content.extra.SHOW_ADVANCED", true)
        }
        
        try {
            folderPickerLauncher.launch(intent)
        } catch (e: Exception) {
            // Fallback if SAF is not available
            view?.let { v ->
                com.google.android.material.snackbar.Snackbar.make(
                    v,
                    "Unable to open folder picker. Please check your device settings.",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun handleSelectedFolder(uri: Uri) {
        try {
            // Take persistable permission for the selected directory
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags)
            
            // Get display name from the URI
            val displayName = getFolderDisplayName(uri)
            
            // Store the selected folder information
            storageDropFolderManager.setSelectedFolder(
                uri = uri.toString(),
                path = null, // We'll use URI-based access instead of file paths
                displayName = displayName
            )
            
            // Update the UI
            updateStorageDropFolderUI()
            
            view?.let { v ->
                com.google.android.material.snackbar.Snackbar.make(
                    v,
                    "Selected folder: $displayName",
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
            }
            
        } catch (e: Exception) {
            view?.let { v ->
                com.google.android.material.snackbar.Snackbar.make(
                    v,
                    "Failed to access selected folder: ${e.message}",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun getFolderDisplayName(uri: Uri): String {
        return try {
            val documentId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            when {
                documentId.contains("primary:") -> {
                    val path = documentId.substringAfter("primary:")
                    if (path.isEmpty()) "Internal Storage" else path.split("/").last()
                }
                documentId.contains("home:") -> {
                    val path = documentId.substringAfter("home:")
                    if (path.isEmpty()) "Home" else path.split("/").last()
                }
                else -> {
                    // Try to get a meaningful name from the URI
                    uri.lastPathSegment?.split("/")?.last() ?: "Selected Folder"
                }
            }
        } catch (e: Exception) {
            "Selected Folder"
        }
    }
    
    private fun showCreateFolderDialog() {
        val selectedPath = storageDropFolderManager.getSelectedFolderPath()
        if (selectedPath == null) {
            // Show message to select a folder first
            view?.let { v ->
                com.google.android.material.snackbar.Snackbar.make(
                    v,
                    "Please select a folder first",
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
            }
            return
        }
        
        // Create a simple dialog for folder name input
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        val input = android.widget.EditText(requireContext())
        input.hint = "Folder name"
        
        builder.setTitle("Create New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val folderName = input.text.toString().trim()
                if (folderName.isNotEmpty()) {
                    if (storageDropFolderManager.createFolder(folderName)) {
                        updateStorageDropFolderUI()
                        view?.let { v ->
                            com.google.android.material.snackbar.Snackbar.make(
                                v,
                                "Folder '$folderName' created successfully",
                                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        view?.let { v ->
                            com.google.android.material.snackbar.Snackbar.make(
                                v,
                                "Failed to create folder '$folderName'",
                                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun onShareItemClicked(item: StorageItem) {
        // Show dialog to select users/devices to share with
        showShareItemDialog(item)
    }
    
    private fun showShareItemDialog(item: StorageItem) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        
        // TODO: Get actual list of available users/devices from mesh service
        val availableTargets = listOf(
            "Device: Phone-1234",
            "Device: Tablet-5678",
            "Service: File Transfer",
            "Service: Media Streaming",
            "User: Alice",
            "User: Bob"
        )
        
        val selectedItems = BooleanArray(availableTargets.size) { index ->
            item.sharedWith.contains(availableTargets[index])
        }
        
        builder.setTitle("Share '${item.name}' with:")
            .setMultiChoiceItems(availableTargets.toTypedArray(), selectedItems) { _, which, isChecked ->
                selectedItems[which] = isChecked
            }
            .setPositiveButton("Update Sharing") { _, _ ->
                val newSharedWith = availableTargets.filterIndexed { index, _ -> 
                    selectedItems[index] 
                }.toSet()
                
                if (storageDropFolderManager.shareItem(item, newSharedWith)) {
                    updateStorageDropFolderUI()
                    view?.let { v ->
                        com.google.android.material.snackbar.Snackbar.make(
                            v,
                            "Sharing updated for '${item.name}'",
                            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    view?.let { v ->
                        com.google.android.material.snackbar.Snackbar.make(
                            v,
                            "Failed to update sharing for '${item.name}'",
                            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    // Distributed Service Layer Methods
    
    private fun initializeDistributedServiceLayerUI() {
        // Initialize service layer participation state from preferences
        val prefs = requireContext().getSharedPreferences("mesh_service_layer", android.content.Context.MODE_PRIVATE)
        // Default to false for new installs, but respect saved preference for existing users
        val isParticipating = prefs.getBoolean("service_layer_participation", false)
        
        serviceLayerParticipationSwitch.isChecked = isParticipating
        updateServiceLayerStatus(isParticipating)
        
        // If participation was previously enabled, start services automatically
        if (isParticipating) {
            Log.d("EnhancedMeshFragment", "Auto-starting distributed services on app launch")
            lifecycleScope.launch {
                updateServiceStatuses(starting = true)
                // Add API call here to set participation state on startup
                meshrabiyaApi.setStorageParticipationEnabled(isParticipating) { result ->
                    val success = result
                    if (!success) {
                        // If services fail to start, disable participation
                        serviceLayerParticipationSwitch.isChecked = false
                        prefs.edit().putBoolean("service_layer_participation", false).apply()
                        updateServiceStatuses()
                    }
                }
            }
        }else {
            updateServiceStatuses()
        }
    }
    
    private fun updateServiceLayerParticipation(isParticipating: Boolean) {
        // Save preference
        val prefs = requireContext().getSharedPreferences("mesh_service_layer", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("service_layer_participation", isParticipating).apply()
        
        // Update UI
        updateServiceLayerStatus(isParticipating)
        
        // Immediately update service statuses to reflect new participation state
        if (!isParticipating) {
            updateServiceStatuses() // This will show "Disabled" for all services
        }
        
        // Start or stop the actual service coordinator
        lifecycleScope.launch {
            if (isParticipating) {
                // Start distributed service layer
                updateServiceStatuses(starting = true)
                // Add API call here to set participation state on startup
                meshrabiyaApi.setStorageParticipationEnabled(isParticipating) { result ->
                    
                     val success = result
                
                    if (success) {
                        updateServiceStatuses()
                        view?.let { v ->
                            com.google.android.material.snackbar.Snackbar.make(
                                v,
                                "✅ Distributed Service Layer activated - Contributing compute resources",
                                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        serviceLayerParticipationSwitch.isChecked = false
                        updateServiceStatuses(stopped = true)
                        view?.let { v ->
                            com.google.android.material.snackbar.Snackbar.make(
                                v,
                                "❌ Failed to start Distributed Service Layer",
                                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                
               
                
            } else {
                // Stop distributed service layer
                updateServiceStatuses(stopping = true)
                
                val success = stopDistributedServices()
                
                updateServiceStatuses(stopped = true)
                view?.let { v ->
                    com.google.android.material.snackbar.Snackbar.make(
                        v,
                        "⏸️ Distributed Service Layer deactivated",
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    private suspend fun startDistributedServices(): Boolean {
        return try {
            // Require the Meshrabiya mesh adapter to be available; fail fast otherwise.
            val meshAdapter = try {
                org.torproject.android.service.MeshServiceCoordinator.getInstance(requireContext()).provideMeshNetworkInterface()
            } catch (e: Exception) {
                null
            }

            if (meshAdapter == null) {
                view?.let { v ->
                    com.google.android.material.snackbar.Snackbar.make(
                        v,
                        "Mesh adapter unavailable — initialize mesh service before starting distributed services",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).show()
                }
                return false
            }

            // Create a compute-level adapter. The adapter is a named class which
            // makes later refactors or delegation easier and improves stack traces.
            val coordinatorAdapter = try {
                org.torproject.android.service.MeshServiceCoordinator.getInstance(requireContext()).provideMeshNetworkInterface()
            } catch (e: Exception) {
                null
            }

            val computeMeshNetwork = org.torproject.android.ui.mesh.ComputeMeshNetworkAdapter(
                meshrabiyaDelegate = coordinatorAdapter
            )

            // Initialize service coordinator with the compute-level adapter
            serviceLayerCoordinator = ServiceLayerCoordinator(computeMeshNetwork)
            
            // Start services
            val success = serviceLayerCoordinator?.startServices() ?: false
            
            if (success) {
                // Start periodic statistics updates
                startServiceStatisticsUpdates()
                android.util.Log.i("EnhancedMeshFragment", "Distributed service layer started successfully")
            }
            
            success
            
        } catch (e: Exception) {
            android.util.Log.e("EnhancedMeshFragment", "Failed to start distributed services", e)
            false
        }
    }
    
    private suspend fun stopDistributedServices(): Boolean {
        return try {
            // Stop statistics updates
            stopServiceStatisticsUpdates()
            
            val success = serviceLayerCoordinator?.stopServices() ?: true
            
            if (success) {
                serviceLayerCoordinator = null
                android.util.Log.i("EnhancedMeshFragment", "Distributed service layer stopped successfully")
            }
            
            success
            
        } catch (e: Exception) {
            android.util.Log.e("EnhancedMeshFragment", "Failed to stop distributed services", e)
            false
        }
    }
    
    private var serviceStatsUpdateJob: Job? = null
    
    private fun startServiceStatisticsUpdates() {
        stopServiceStatisticsUpdates()
        
        serviceStatsUpdateJob = lifecycleScope.launch {
            while (serviceLayerCoordinator?.isServiceActive() == true) {
                updateServiceStatisticsDisplay()
                delay(15000) // Update every 15 seconds
            }
        }
    }
    
    private fun stopServiceStatisticsUpdates() {
        serviceStatsUpdateJob?.cancel()
        serviceStatsUpdateJob = null
    }
    
    private fun updateServiceStatisticsDisplay() {
        serviceLayerCoordinator?.let { coordinator ->
            val stats = coordinator.getServiceStatistics()
            val capabilities = coordinator.getServiceCapabilities()
            
            // Update service status indicators based on actual capabilities
            pythonServiceStatus.text = if (capabilities.supportedComputeTypes.contains("PYTHON_SCRIPT")) {
                coordinator.getPythonExecutionStatus()
            } else {
                requireContext().getString(R.string.service_unavailable)
            }
            
            mlInferenceServiceStatus.text = if (capabilities.supportedComputeTypes.contains("ML_INFERENCE")) {
                coordinator.getMLInferenceStatus()
            } else {
                requireContext().getString(R.string.service_unavailable)
            }
            
            distributedStorageServiceStatus.text = if (capabilities.storageEnabled) {
                // Get storage transfer statistics from service layer
                val transferStats = serviceLayerCoordinator?.getStorageTransferStats()
                if (transferStats != null && transferStats.activeTransfers > 0) {
                    val throughputMBps = transferStats.avgThroughputBytesPerSec / (1024 * 1024) // Convert to MB/s
                    "(${transferStats.activeTransfers} files ${String.format("%.1f", throughputMBps)} mb/s)"
                } else {
                    "Ready"
                }
            } else {
                requireContext().getString(R.string.service_unavailable)
            }
            
            val uptimeMinutes = (stats.serviceUptimeMs / (1000 * 60)).toInt()
            val statusText = if (capabilities.computeEnabled) {
                "Ready (${uptimeMinutes}m uptime)"
            } else {
                requireContext().getString(R.string.service_unavailable)
            }
            
            Log.d("EnhancedMeshFragment", "updateServiceLayerStatus: uptimeMs=${stats.serviceUptimeMs}, uptimeMin=$uptimeMinutes, status='$statusText'")
            
            taskSchedulerServiceStatus.text = statusText
        }
    }
    
    private fun updateServiceLayerStatus(isParticipating: Boolean) {
        serviceLayerStatusText.text = if (isParticipating) {
            getString(R.string.service_layer_active)
        } else {
            getString(R.string.service_layer_inactive)
        }
    }
    
    private fun updateServiceStatuses(
        starting: Boolean = false,
        stopping: Boolean = false,
        stopped: Boolean = false
    ) {
        val context = requireContext()
        val isParticipating = serviceLayerParticipationSwitch.isChecked
        
        Log.d("EnhancedMeshFragment", "updateServiceStatuses: isParticipating=$isParticipating, starting=$starting, stopping=$stopping, stopped=$stopped")
        
        when {
            starting -> {
                pythonServiceStatus.text = "Starting..."
                mlInferenceServiceStatus.text = "Starting..."
                distributedStorageServiceStatus.text = "Starting..."
                taskSchedulerServiceStatus.text = "Starting..."
            }
            stopping -> {
                pythonServiceStatus.text = "Stopping..."
                mlInferenceServiceStatus.text = "Stopping..."
                distributedStorageServiceStatus.text = "Stopping..."
                taskSchedulerServiceStatus.text = "Stopping..."
            }
            !isParticipating -> {
                // When participation is disabled, all services should show "Disabled"
                pythonServiceStatus.text = context.getString(R.string.service_disabled)
                mlInferenceServiceStatus.text = context.getString(R.string.service_disabled)
                distributedStorageServiceStatus.text = context.getString(R.string.service_disabled)
                taskSchedulerServiceStatus.text = context.getString(R.string.service_disabled)
            }
            stopped -> {
                // When stopped but participation is enabled, show "Unavailable"
                pythonServiceStatus.text = context.getString(R.string.service_unavailable)
                mlInferenceServiceStatus.text = context.getString(R.string.service_unavailable)
                distributedStorageServiceStatus.text = context.getString(R.string.service_unavailable)
                taskSchedulerServiceStatus.text = context.getString(R.string.service_unavailable)
            }
            else -> {
                // When participation is enabled and services are running, check actual availability
                pythonServiceStatus.text = if (isPythonServiceAvailable()) {
                    serviceLayerCoordinator?.getPythonExecutionStatus() ?: "Error"
                } else {
                    context.getString(R.string.service_error)
                }
                
                mlInferenceServiceStatus.text = if (isMLInferenceServiceAvailable()) {
                    serviceLayerCoordinator?.getMLInferenceStatus() ?: "Error"
                } else {
                    context.getString(R.string.service_error)
                }
                
                distributedStorageServiceStatus.text = if (isDistributedStorageServiceAvailable()) {
                    // Get storage transfer statistics from service layer
                    val transferStats = serviceLayerCoordinator?.getStorageTransferStats()
                    if (transferStats != null && transferStats.activeTransfers > 0) {
                        val throughputMBps = transferStats.avgThroughputBytesPerSec / (1024 * 1024) // Convert to MB/s
                        "(${transferStats.activeTransfers} files ${String.format("%.1f", throughputMBps)} mb/s)"
                    } else {
                        "Ready"
                    }
                } else {
                    context.getString(R.string.service_error)
                }
                
                taskSchedulerServiceStatus.text = if (isTaskSchedulerServiceAvailable()) {
                    // Use the proper status method from ServiceLayerCoordinator
                    serviceLayerCoordinator?.getTaskSchedulerStatus() ?: "Error"
                } else {
                    context.getString(R.string.service_error)
                }
            }
        }
    }
    
    private fun isPythonServiceAvailable(): Boolean {
        // Check if service layer coordinator is active and services are running
        return serviceLayerCoordinator?.isServiceLayerActive() == true
    }
    
    private fun isMLInferenceServiceAvailable(): Boolean {
        // Check if service layer coordinator is active and ML services are available
        return serviceLayerCoordinator?.isServiceLayerActive() == true
    }
    
    private fun isDistributedStorageServiceAvailable(): Boolean {
        // TODO: Check if distributed storage service is running
        // For now, return true if storage participation is enabled
        return storageParticipationToggle.isChecked
    }
    
    private fun isTaskSchedulerServiceAvailable(): Boolean {
        // TODO: Check if task scheduler service is running
        // For now, simulate based on mesh network status
        return isNetworkActive
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        MeshManagers.gatewayManager.removeListener(this)
        stopServiceStatisticsUpdates()
        lifecycleScope.launch {
            serviceLayerCoordinator?.stopServices()
            serviceLayerCoordinator = null
        }
    }
}
