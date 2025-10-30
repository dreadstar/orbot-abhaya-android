package org.torproject.android.ui.mesh

import org.torproject.android.ui.mesh.MeshManagers
import org.torproject.android.ui.mesh.MeshUIBindings

object MeshStorageUI {
    fun initializeStorageUI() {
        val userPrefs = MeshManagers.meshCoordinator.getUserSharingPreferences()
        val userAllocation = userPrefs["storageAllocationGB"] as? Int ?: 5
        MeshUIBindings.storageAllocationSlider.value = userAllocation.toFloat()
        MeshUIBindings.storageAllocationText.text = "${userAllocation} GB"
    }
    fun updateStorageStatus() {
        val userPrefs = MeshManagers.meshCoordinator.getUserSharingPreferences()
        val userEnabledStorage = userPrefs["allowStorageSharing"] as? Boolean ?: false
        val userAllocation = userPrefs["storageAllocationGB"] as? Int ?: 5
        val storageStatus = MeshManagers.meshCoordinator.getStorageParticipationStatus()
        MeshUIBindings.storageParticipationToggle.isChecked = userEnabledStorage
        MeshUIBindings.storageStatusText.text = when {
            !userEnabledStorage -> "Storage participation disabled"
            userEnabledStorage && storageStatus.isEnabled && storageStatus.participationHealth == "Active" ->
                "Participating in distributed storage (${storageStatus.usedGB}/${userAllocation} GB used)"
            userEnabledStorage && storageStatus.isEnabled ->
                "Storage configured - ${userAllocation} GB allocated"
            userEnabledStorage -> "Initializing storage participation - ${userAllocation} GB allocated"
            else -> "Storage participation disabled"
        }
    }
}
