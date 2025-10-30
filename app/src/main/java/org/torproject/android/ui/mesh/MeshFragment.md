package org.torproject.android.ui.mesh

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import org.torproject.android.GatewayCapabilitiesManager
import org.torproject.android.R
import com.google.android.material.snackbar.Snackbar
import org.torproject.android.mesh.DropFolderManager
import org.torproject.android.mesh.MeshStorageUiCallback
import com.ustadmobile.meshrabiya.storage.DataStore
import com.ustadmobile.meshrabiya.storage.MeshFile
import com.ustadmobile.meshrabiya.storage.MeshChunk
import kotlinx.coroutines.*

/**
 * Mesh networking fragment for managing mesh integration and gateway capabilities.
 * Handles UI for Meshrabiya library controls and best consent.
 * Now also displays mesh files and their drop folder status.
 */
class MeshFragment : Fragment(), MeshStorageUiCallback {

    private lateinit var gatewayManager: GatewayCapabilitiesManager
    private lateinit var gatewayToggle: SwitchMaterial
    private lateinit var gatewayStatusText: TextView
    private lateinit var meshStatusText: TextView
    private lateinit var peerCountText: TextView
    private lateinit var refreshButton: MaterialButton
    private lateinit var statusTextView: TextView

    // --- NEW CODE: UI for mesh files ---
    private lateinit var meshFilesContainer: LinearLayout

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
        meshFilesContainer = view.findViewById(R.id.meshFilesContainer) // Add this to your layout XML

        MeshStorageManager.getInstance(requireContext()).setUiCallback(this)
        gatewayManager = GatewayCapabilitiesManager.getInstance(requireContext())

        setupListeners()
        updateUI()
        loadMeshFilesUI()
    }
    
    private fun setupListeners() {
        gatewayToggle.setOnCheckedChangeListener { _, isChecked ->
            // TODO: Toggle gateway mode
            updateGatewayStatus(isChecked)
        }
        refreshButton.setOnClickListener {
            updateUI()
            loadMeshFilesUI()
        }
    }

    private fun updateUI() {
        val isGatewayEnabled = gatewayManager.isStorageParticipationEnabled()
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

    // --- UI callback implementations ---
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
        loadMeshFilesUI()
    }

    // --- NEW FUNCTIONALITY: Mesh file browser UI ---
    private fun loadMeshFilesUI() {
        meshFilesContainer.removeAllViews()
        CoroutineScope(Dispatchers.IO).launch {
            val dataStore = DataStore.getInstance(requireContext())
            val meshFiles = dataStore.getAllMeshFiles()
            val dropFolder = MeshStorageManager.getInstance(requireContext()).selectedDropFolder?.toFile()
            val dropFolderFiles = dropFolder?.listFiles()?.map { it.name }?.toSet() ?: emptySet()

            withContext(Dispatchers.Main) {
                if (meshFiles.isEmpty()) {
                    val emptyView = TextView(requireContext())
                    emptyView.text = "No mesh files found."
                    meshFilesContainer.addView(emptyView)
                } else {
                    meshFiles.forEach { meshFile ->
                        val fileView = TextView(requireContext())
                        val inDropFolder = dropFolderFiles.contains(meshFile.fileName)
                        fileView.text = buildString {
                            append("File: ${meshFile.fileName} (${meshFile.fileId})\n")
                            append("Size: ${meshFile.fileSize} bytes\n")
                            append("Status: ")
                            append(if (inDropFolder) "Present in drop folder" else "Distributed only")
                        }
                        meshFilesContainer.addView(fileView)
                    }
                }
            }
        }
    }
}