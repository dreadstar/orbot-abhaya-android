package org.torproject.android.ui.mesh

import org.torproject.android.ui.mesh.MeshManagers
import org.torproject.android.ui.mesh.MeshUIBindings

object MeshServiceLayerUI {
    fun initializeDistributedServiceLayerUI() {
        val isParticipating = false // Load from preferences if needed
        MeshUIBindings.serviceLayerParticipationSwitch.isChecked = isParticipating
        updateServiceLayerStatus(isParticipating)
        if (isParticipating) {
            MeshManagers.meshrabiyaApi.enableDistributedStorage()
        } else {
            MeshManagers.meshrabiyaApi.disableDistributedStorage()
        }
    }
    fun updateServiceLayerStatus(isParticipating: Boolean) {
        MeshUIBindings.serviceLayerStatusText.text = if (isParticipating) {
            "Service Layer Active"
        } else {
            "Service Layer Inactive"
        }
    }
}
