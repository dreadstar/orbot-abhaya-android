package org.torproject.android.ui.mesh

import org.torproject.android.R
import com.ustadmobile.meshrabiya.model.MeshState

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.ustadmobile.meshrabiya.api.MeshrabiyaApi
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl

/**
 * EnhancedMeshFragment: Mesh UI fragment using MeshrabiyaApi for all mesh logic.
 * All mesh operations are routed through MeshrabiyaApi. No deprecated logic is used.
 */
class EnhancedMeshFragment : Fragment() {

	private lateinit var meshrabiyaApi: MeshrabiyaApi

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
		// Initialize MeshrabiyaApi singleton and provide context
		meshrabiyaApi = MeshrabiyaApiImpl.getInstance()
		meshrabiyaApi.provideAppContext(requireContext().applicationContext)
		meshrabiyaApi.initMesh(requireContext().applicationContext)

		setupListeners()
		updateUI()
	}

	private fun bindUI(view: View) {
		// Bind all UI elements from MeshUIBindings
		MeshUIBindings.meshStatusText = view.findViewById(R.id.meshStatusText)
		MeshUIBindings.nodeInfoText = view.findViewById(R.id.nodeInfoText)
		MeshUIBindings.networkStatsText = view.findViewById(R.id.networkStatsText)
		MeshUIBindings.lastUpdateText = view.findViewById(R.id.lastUpdateText)
		MeshUIBindings.gatewayToggle = view.findViewById(R.id.gatewayToggle)
		MeshUIBindings.internetGatewayToggle = view.findViewById(R.id.internetGatewayToggle)
		MeshUIBindings.refreshButton = view.findViewById(R.id.refreshButton)
		MeshUIBindings.meshToggleButton = view.findViewById(R.id.meshToggleButton)
		MeshUIBindings.torGatewayCard = view.findViewById(R.id.torGatewayCard)
		MeshUIBindings.internetGatewayCard = view.findViewById(R.id.internetGatewayCard)
		MeshUIBindings.networkOverviewCard = view.findViewById(R.id.networkOverviewCard)
		MeshUIBindings.storageParticipationCard = view.findViewById(R.id.storageParticipationCard)
		MeshUIBindings.storageParticipationToggle = view.findViewById(R.id.storageParticipationToggle)
		MeshUIBindings.storageAllocationSlider = view.findViewById(R.id.storageAllocationSlider)
		MeshUIBindings.storageStatusText = view.findViewById(R.id.storageStatusText)
		MeshUIBindings.storageAllocationText = view.findViewById(R.id.storageAllocationText)
		MeshUIBindings.storageDropFolderCard = view.findViewById(R.id.storageDropFolderCard)
		MeshUIBindings.selectFolderButton = view.findViewById(R.id.selectFolderButton)
		MeshUIBindings.createFolderButton = view.findViewById(R.id.createFolderButton)
		MeshUIBindings.selectedFolderText = view.findViewById(R.id.selectedFolderText)
		MeshUIBindings.folderContentsRecyclerView = view.findViewById(R.id.folderContentsRecyclerView)
		MeshUIBindings.distributedServiceLayerCard = view.findViewById(R.id.distributedServiceLayerCard)
		MeshUIBindings.serviceLayerParticipationSwitch = view.findViewById(R.id.serviceLayerParticipationSwitch)
		MeshUIBindings.serviceLayerStatusText = view.findViewById(R.id.serviceLayerStatusText)
		MeshUIBindings.pythonServiceStatus = view.findViewById(R.id.pythonServiceStatus)
		MeshUIBindings.mlInferenceServiceStatus = view.findViewById(R.id.mlInferenceServiceStatus)
		MeshUIBindings.distributedStorageServiceStatus = view.findViewById(R.id.distributedStorageServiceStatus)
	}

	private fun setupListeners() {
		// Example: Service Layer Participation Toggle
		MeshUIBindings.serviceLayerParticipationSwitch.setOnCheckedChangeListener { _, isChecked ->
			meshrabiyaApi.setServiceParticipationEnabled("compute_node", isChecked) { result ->
				// Handle result (success/failure)
			}
		}

		// Gateway toggles
		MeshUIBindings.gatewayToggle.setOnCheckedChangeListener { _, isChecked ->
			meshrabiyaApi.setTorGatewayEnabled(isChecked) { result ->
				// Handle result
			}
		}
		MeshUIBindings.internetGatewayToggle.setOnCheckedChangeListener { _, isChecked ->
			meshrabiyaApi.setInternetGatewayEnabled(isChecked) { result ->
				// Handle result
			}
		}

		// Storage participation toggle
		MeshUIBindings.storageParticipationToggle.setOnCheckedChangeListener { _, isChecked ->
			meshrabiyaApi.setStorageParticipationEnabled(isChecked) { result ->
				// Handle result
			}
		}

		// Drop folder selection
		MeshUIBindings.selectFolderButton.setOnClickListener {
			// Example: select drop folder via MeshrabiyaApi
			val folderPath = "DropFolder" // Replace with actual selection logic
			meshrabiyaApi.selectDropFolder(folderPath) { result ->
				// Handle result
			}
		}
		MeshUIBindings.createFolderButton.setOnClickListener {
			// Example: create new drop folder logic
		}

		// Mesh toggle button
		MeshUIBindings.meshToggleButton.setOnClickListener {
			val meshActive = meshrabiyaApi.getMeshStatus() == MeshState.CONNECTED
			if (meshActive) {
				meshrabiyaApi.stopMesh { result -> /* Handle result */ }
			} else {
				meshrabiyaApi.startMesh { result -> /* Handle result */ }
			}
		}

		// Refresh button
		MeshUIBindings.refreshButton.setOnClickListener {
			updateUI()
		}
	}

	private fun updateUI() {
		// Mesh status
		val meshState = meshrabiyaApi.getMeshStatus()
		MeshUIBindings.meshStatusText.text = meshState.toString()

		// Node info
		val nodeInfo = meshrabiyaApi.getNodeInfo("local")
		MeshUIBindings.nodeInfoText.text = nodeInfo.toString()

		// Network stats
		val networkInfo = meshrabiyaApi.getNetworkInfo()
		MeshUIBindings.networkStatsText.text = networkInfo.toString()

		// Last update
		MeshUIBindings.lastUpdateText.text = System.currentTimeMillis().toString()

		// Gateway toggles
		MeshUIBindings.gatewayToggle.isChecked = meshrabiyaApi.getTorGatewayStatus()
		MeshUIBindings.internetGatewayToggle.isChecked = meshrabiyaApi.getInternetGatewayStatus()

		// Storage participation
		MeshUIBindings.storageParticipationToggle.isChecked = meshrabiyaApi.getStorageParticipationStatus()

		// Drop folder
		val dropFolder = meshrabiyaApi.getDropFolder()
		MeshUIBindings.selectedFolderText.text = dropFolder?.absolutePath ?: "No folder selected"

		// Mesh files
		val meshFiles = meshrabiyaApi.getAllMeshFiles()
		// TODO: Update folderContentsAdapter with meshFiles
	}
}
