package org.torproject.android.ui.mesh

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.torproject.android.GatewayCapabilitiesManager
import org.torproject.android.R
import org.torproject.android.service.MeshServiceCoordinator
import org.torproject.android.service.routing.MeshTrafficRouter
import org.torproject.android.service.routing.MeshTrafficRouterImpl
import java.text.SimpleDateFormat
import java.util.*

/**
 * Enhanced mesh networking fragment based on integration guidance.
 * Provides comprehensive mesh network management with:
 * - Network overview statistics
 * - Service monitoring and management
 * - Node information display
 * - Real-time status updates
 */
class EnhancedMeshFragment : Fragment(), GatewayCapabilitiesManager.GatewayCapabilityListener {
    
    // Core managers
    private lateinit var gatewayManager: GatewayCapabilitiesManager
    private lateinit var meshCoordinator: MeshServiceCoordinator
    private lateinit var trafficRouter: MeshTrafficRouter
    private var virtualNode: AndroidVirtualNode? = null
    
    // Status display elements
    private lateinit var meshStatusText: TextView
    private lateinit var nodeInfoText: TextView
    private lateinit var networkStatsText: TextView
    private lateinit var lastUpdateText: TextView
    
    // Control elements
    private lateinit var gatewayToggle: SwitchMaterial
    private lateinit var internetGatewayToggle: SwitchMaterial
    private lateinit var refreshButton: MaterialButton
    private lateinit var startMeshButton: MaterialButton
    private lateinit var stopMeshButton: MaterialButton
    
    // Service cards
    private lateinit var torGatewayCard: MaterialCardView
    private lateinit var internetGatewayCard: MaterialCardView
    private lateinit var networkOverviewCard: MaterialCardView
    
    // Service status texts
    private lateinit var torGatewayStatus: TextView
    private lateinit var internetGatewayStatus: TextView
    private lateinit var activeNodesText: TextView
    private lateinit var networkLoadText: TextView
    private lateinit var stabilityText: TextView
    
    private var isNetworkActive = false
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_mesh_enhanced, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupManagers()
        setupListeners()
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
        startMeshButton = view.findViewById(R.id.startMeshButton)
        stopMeshButton = view.findViewById(R.id.stopMeshButton)
        
        // Service cards
        torGatewayCard = view.findViewById(R.id.torGatewayCard)
        internetGatewayCard = view.findViewById(R.id.internetGatewayCard)
        networkOverviewCard = view.findViewById(R.id.networkOverviewCard)
        
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
        
        // TODO: Get virtual node from application
        // virtualNode = (requireActivity().application as OrbotApp).virtualNode
    }
    
    private fun setupListeners() {
        gatewayToggle.setOnCheckedChangeListener { _, isChecked ->
            gatewayManager.shareTor = isChecked
            
            // Update mesh service preferences when Tor gateway changes
            val currentPrefs = meshCoordinator.getUserSharingPreferences()
            meshCoordinator.setUserSharingPreferences(
                allowTorGateway = isChecked,
                allowInternetGateway = currentPrefs["allowInternetGateway"] ?: false,
                allowStorageSharing = currentPrefs["allowStorageSharing"] ?: true
            )
            
            updateGatewayStatus()
        }
        
        internetGatewayToggle.setOnCheckedChangeListener { _, isChecked ->
            gatewayManager.shareInternet = isChecked
            
            // Update mesh service preferences when Internet gateway changes
            val currentPrefs = meshCoordinator.getUserSharingPreferences()
            meshCoordinator.setUserSharingPreferences(
                allowTorGateway = currentPrefs["allowTorGateway"] ?: false,
                allowInternetGateway = isChecked,
                allowStorageSharing = currentPrefs["allowStorageSharing"] ?: true
            )
            
            updateGatewayStatus()
        }
        
        refreshButton.setOnClickListener {
            updateUI()
        }
        
        startMeshButton.setOnClickListener {
            startMeshNetwork()
        }
        
        stopMeshButton.setOnClickListener {
            stopMeshNetwork()
        }
    }
    
    private fun startPeriodicUpdates() {
        lifecycleScope.launch {
            while (true) {
                updateNetworkStats()
                delay(5000) // Update every 5 seconds
            }
        }
    }
    
    private fun updateUI() {
        updateGatewayStatus()
        updateNetworkStats()
        updateNodeInformation()
        updateServiceCards()
        
        lastUpdateText.text = "Last updated: ${timeFormatter.format(Date())}"
    }
    
    private fun updateGatewayStatus() {
        val status = gatewayManager.getCurrentStatus()
        
        gatewayToggle.isChecked = status.shareTor
        internetGatewayToggle.isChecked = status.shareInternet
        
        // Update Tor gateway status
        torGatewayStatus.text = when {
            status.shareTor && status.isTorAvailable -> "Active"
            status.shareTor && !status.isTorAvailable -> "Enabled (Tor Not Available)"
            else -> "Disabled"
        }
        
        // Update Internet gateway status
        internetGatewayStatus.text = when {
            status.shareInternet && status.hasInternetConnection -> "Active"
            status.shareInternet && !status.hasInternetConnection -> "Enabled (No Internet)"
            else -> "Disabled"
        }
    }
    
    private fun updateNetworkStats() {
        // Get real mesh service status instead of mock data
        val meshStatus = meshCoordinator.getMeshServiceStatus()
        val healthCheck = meshCoordinator.performHealthCheck()
        
        // Update with real data from MeshServiceCoordinator
        activeNodesText.text = "${meshStatus.nodeCount} nodes"
        networkLoadText.text = "${if (meshStatus.trafficBytes > 0) "Active" else "Idle"} traffic"
        stabilityText.text = "${if (healthCheck.success) "Stable" else "Unstable"}"
        
        // Update mesh status with real information
        meshStatusText.text = when {
            meshStatus.isRunning && meshStatus.nodeCount > 0 -> 
                "Mesh network active with ${meshStatus.nodeCount} connected nodes"
            meshStatus.isRunning && meshStatus.nodeCount == 0 -> 
                "Mesh network running, discovering peers..."
            !meshStatus.isRunning -> 
                "Mesh network stopped"
            else -> 
                "Mesh network status unknown"
        }
        
        // Update network active state based on real status
        isNetworkActive = meshStatus.isRunning
    }
    
    private fun updateNodeInformation() {
        // Get real mesh service information
        val meshStatus = meshCoordinator.getMeshServiceStatus()
        val currentRoles = meshCoordinator.getCurrentlyAssignedRoles()
        val userPrefs = meshCoordinator.getUserSharingPreferences()
        val virtualNode = meshCoordinator.getVirtualNode()
        
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
                
                // Get neighbors from virtual node if available
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
        
        nodeInfoText.text = nodeInfo
    }
    
    private fun updateServiceCards() {
        // Update card backgrounds based on service status
        val activeColor = requireContext().getColor(R.color.bright_green)
        val inactiveColor = requireContext().getColor(R.color.panel_background_main)
        
        torGatewayCard.setCardBackgroundColor(
            if (gatewayManager.shareTor) activeColor else inactiveColor
        )
        
        internetGatewayCard.setCardBackgroundColor(
            if (gatewayManager.shareInternet) activeColor else inactiveColor
        )
        
        networkOverviewCard.setCardBackgroundColor(
            if (isNetworkActive) activeColor else inactiveColor
        )
    }
    
    private fun startMeshNetwork() {
        lifecycleScope.launch {
            meshStatusText.text = "Starting mesh network..."
            
            // Actually start the mesh networking through MeshServiceCoordinator
            val success = meshCoordinator.startMeshNetworking()
            
            if (success) {
                isNetworkActive = true
                meshStatusText.text = "Mesh network started successfully"
                
                // Update user sharing preferences based on UI toggles
                meshCoordinator.setUserSharingPreferences(
                    allowTorGateway = gatewayToggle.isChecked,
                    allowInternetGateway = internetGatewayToggle.isChecked,
                    allowStorageSharing = true // Default to allowing storage sharing
                )
                
                updateUI()
            } else {
                meshStatusText.text = "Failed to start mesh network"
            }
        }
    }
    
    private fun stopMeshNetwork() {
        lifecycleScope.launch {
            meshStatusText.text = "Stopping mesh network..."
            
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
    
    override fun onDestroyView() {
        super.onDestroyView()
        gatewayManager.removeListener(this)
    }
}
