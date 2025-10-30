package org.torproject.android.ui.mesh

import android.view.View
import androidx.lifecycle.LifecycleCoroutineScope
import org.torproject.android.ui.mesh.MeshManagers
import org.torproject.android.ui.mesh.MeshUIBindings

object MeshListeners {
    fun setupListeners(view: View, lifecycleScope: LifecycleCoroutineScope) {
        MeshUIBindings.gatewayToggle.setOnCheckedChangeListener { _, isChecked ->
            MeshManagers.meshrabiyaApi.setGatewaySharing(isChecked)
        }
        MeshUIBindings.internetGatewayToggle.setOnCheckedChangeListener { _, isChecked ->
            MeshManagers.meshrabiyaApi.setInternetSharing(isChecked)
        }
        MeshUIBindings.refreshButton.setOnClickListener {
            // Use API to refresh mesh/gateway/storage state
            MeshManagers.meshrabiyaApi.refreshStatus()
        }
        MeshUIBindings.meshToggleButton.setOnClickListener {
            // Use API to start/stop mesh network
            MeshManagers.meshrabiyaApi.toggleMeshNetwork()
        }
        MeshUIBindings.storageParticipationToggle.setOnCheckedChangeListener { _, isChecked ->
            val allocation = MeshUIBindings.storageAllocationSlider.value.toInt()
            MeshManagers.meshrabiyaApi.setStorageParticipation(isChecked, allocation)
        }
        MeshUIBindings.storageAllocationSlider.addOnChangeListener { _, value, _ ->
            val allocation = value.toInt()
            val isParticipating = MeshUIBindings.storageParticipationToggle.isChecked
            MeshManagers.meshrabiyaApi.setStorageParticipation(isParticipating, allocation)
        }
        MeshUIBindings.selectFolderButton.setOnClickListener {
            MeshManagers.meshrabiyaApi.selectStorageFolder()
        }
        MeshUIBindings.createFolderButton.setOnClickListener {
            MeshManagers.meshrabiyaApi.createStorageFolder()
        }
        MeshUIBindings.serviceLayerParticipationSwitch.setOnCheckedChangeListener { _, isChecked ->
            MeshManagers.meshrabiyaApi.setServiceLayerParticipation(isChecked)
        }
    }
}