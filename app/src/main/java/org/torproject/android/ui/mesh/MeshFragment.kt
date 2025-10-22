package org.torproject.android.ui.mesh

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import org.torproject.android.GatewayCapabilitiesManager
import org.torproject.android.R
import com.google.android.material.snackbar.Snackbar
import org.torproject.android.mesh.MeshStorageManager
import org.torproject.android.mesh.MeshStorageUiCallback

/**
 * Mesh networking fragment for managing mesh integration and gateway capabilities.
 * Handles UI for Meshrabiya library controls and best consent.
 */
class MeshFragment : Fragment() {
    private lateinit var gatewayManager: GatewayCapabilitiesManager
    private lateinit var gatewayToggle: SwitchMaterial
    private lateinit var gatewayStatusText: TextView
    private lateinit var meshStatusText: TextView
    private lateinit var peerCountText: TextView
    private lateinit var refreshButton: MaterialButton

    // --- NEW CODE: Status text for storage lifecycle ---
    private lateinit var statusTextView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_mesh, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize views
        gatewayToggle = view.findViewById(R.id.gatewayToggle)
        gatewayStatusText = view.findViewById(R.id.gatewayStatusText)
        meshStatusText = view.findViewById(R.id.meshStatusText)
        peerCountText = view.findViewById(R.id.peerCountText)
        refreshButton = view.findViewById(R.id.refreshButton)

        statusTextView = view.findViewById(R.id.statusTextView)
        MeshStorageManager.getInstance(requireContext()).setUiCallback(this)
        
        // Initialize gateway manager
        gatewayManager = GatewayCapabilitiesManager.getInstance(requireContext())

        // --- NEW CODE: Register UI callback for MeshStorageManager ---
        MeshStorageManager.getInstance(requireContext()).setUiCallback(this)
        
        // Setup UI listeners
        setupListeners()
        
        // Update initial UI state
        updateUI()
    }
    
    private fun setupListeners() {
        gatewayToggle.setOnCheckedChangeListener { _, isChecked ->
            // TODO: Toggle gateway mode
            updateGatewayStatus(isChecked)
        }
        
        refreshButton.setOnClickListener {
            // TODO: Refresh mesh status
            updateUI()
        }
    }
    

    private fun updateUI() {
        // Get actual status from gateway manager
        val isGatewayEnabled = gatewayManager.isStorageParticipationEnabled() // --- NEW CODE: Use storage participation status ---
        updateGatewayStatus(isGatewayEnabled)
        // TODO: Replace with real mesh connection and peer count
        updateMeshStatus(false, 0)
    }
    
    private fun updateGatewayStatus(isEnabled: Boolean) {
        gatewayToggle.isChecked = isEnabled
        gatewayStatusText.text = if (isEnabled) {
            getString(R.string.mesh_status_connected)
        } else {
            getString(R.string.mesh_status_disconnected)
        }
    }
    
    private fun updateMeshStatus(isConnected: Boolean, peerCount: Int) {
        meshStatusText.text = if (isConnected) {
            getString(R.string.mesh_status_connected)
        } else {
            getString(R.string.mesh_status_disconnected)
        }
        
        peerCountText.text = if (peerCount > 0) {
            getString(R.string.mesh_peers_count, peerCount)
        } else {
            getString(R.string.mesh_peers_none)
        }
    }

    override fun showNoStorageNodesAlert() {
        Snackbar.make(requireView(), "No storage nodes available. Retrying...", Snackbar.LENGTH_LONG).show()
        statusTextView.text = "No storage nodes available. Retrying..."
    }

    override fun showInsufficientSpaceAlert() {
        Snackbar.make(requireView(), "No storage nodes have enough space for this file.", Snackbar.LENGTH_LONG).show()
        statusTextView.text = "Insufficient space on available nodes."
    }

    override fun showTransferFailedAlert() {
        Snackbar.make(requireView(), "File transfer failed. Retrying...", Snackbar.LENGTH_LONG).show()
        statusTextView.text = "File transfer failed. Retrying..."
    }

    override fun showFileStored(fileId: String) {
        Snackbar.make(requireView(), "File stored and replicated! ID: $fileId", Snackbar.LENGTH_LONG).show()
        statusTextView.text = "File stored. ID: $fileId"
    }

}
