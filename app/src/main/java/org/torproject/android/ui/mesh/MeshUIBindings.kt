
package org.torproject.android.ui.mesh

import org.torproject.android.R

import android.view.View
import android.widget.TextView
import android.widget.ImageView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.slider.Slider
import androidx.recyclerview.widget.RecyclerView
import android.widget.LinearLayout
// import org.torproject.android.ui.mesh.adapter.FolderContentsAdapter

object MeshUIBindings {
        lateinit var textUploadBitrate: TextView
        lateinit var textDownloadBitrate: TextView
        lateinit var textActiveNodeCount: TextView
    lateinit var meshStatusText: TextView
    lateinit var nodeInfoText: TextView  // kept for backward compat; not updated post-C4
    lateinit var meshIpAddressText: TextView
    lateinit var meshChipMesh: com.google.android.material.chip.Chip
    lateinit var meshChipSta: com.google.android.material.chip.Chip
    lateinit var meshChipAp: com.google.android.material.chip.Chip
    lateinit var internetWifiRow: android.widget.LinearLayout
    lateinit var internetWifiIpText: TextView
    lateinit var internetWifiChipSta: com.google.android.material.chip.Chip
    lateinit var internetWifiChipWeb: com.google.android.material.chip.Chip
    lateinit var meshExtenderApButton: android.widget.ImageButton
    lateinit var meshExtenderApRow: android.widget.LinearLayout
    lateinit var meshExtenderApIpText: TextView
    lateinit var meshExtenderApChipAp: com.google.android.material.chip.Chip
    lateinit var meshExtenderApChipMesh: com.google.android.material.chip.Chip
    lateinit var meshRolesText: TextView
    lateinit var networkStatsText: TextView
    lateinit var lastUpdateText: TextView
    lateinit var gatewayToggle: SwitchMaterial
    lateinit var internetGatewayToggle: SwitchMaterial
    
    lateinit var sendBroadcastButton: MaterialButton
    lateinit var meshToggleButton: MaterialButton
    
    // Mesh control card and new buttons
    lateinit var meshControlCard: MaterialCardView
    lateinit var meshControlHeader: LinearLayout
    lateinit var joinMeshButton: MaterialButton
    lateinit var mergeMeshButton: MaterialButton
    lateinit var wifiApConnectionButton: MaterialButton
    lateinit var expandCollapseIndicator: ImageView
    
    // Expandable content
    lateinit var meshExpandableContent: LinearLayout
    
    // QR code container
    lateinit var qrCodeContainer: LinearLayout
    lateinit var qrCodeTitle: TextView
    lateinit var qrCodeSubtitle: TextView
    lateinit var qrCodeImageView: ImageView
    lateinit var qrCodeNetworkInfo: TextView
    lateinit var copyNetworkInfoButton: MaterialButton
    
    // Camera preview container
    lateinit var cameraPreviewContainer: LinearLayout
    lateinit var cameraPreviewView: androidx.camera.view.PreviewView
    lateinit var scanningOverlay: View
    lateinit var scanningStatusText: TextView
    lateinit var cancelScanButton: MaterialButton
    lateinit var toggleFlashlightButton: MaterialButton
    
    lateinit var torGatewayCard: MaterialCardView
    lateinit var internetGatewayCard: MaterialCardView
    lateinit var networkOverviewCard: MaterialCardView
    lateinit var storageParticipationCard: MaterialCardView
    lateinit var storageParticipationToggle: SwitchMaterial
    lateinit var storageAllocationSlider: Slider
    lateinit var storageStatusText: TextView
    lateinit var storageAllocationText: TextView
    lateinit var storageDropFolderCard: MaterialCardView
    lateinit var selectFolderButton: MaterialButton
    lateinit var createFolderButton: MaterialButton
    lateinit var selectedFolderText: TextView
    lateinit var folderContentsRecyclerView: RecyclerView
    // lateinit var folderContentsAdapter: FolderContentsAdapter
    lateinit var distributedServiceLayerCard: MaterialCardView
    lateinit var serviceLayerParticipationSwitch: SwitchMaterial
    lateinit var serviceLayerStatusText: TextView
    lateinit var pythonServiceStatus: TextView
    lateinit var mlInferenceServiceStatus: TextView
    lateinit var distributedStorageServiceStatus: TextView
    lateinit var taskSchedulerServiceStatus: TextView
    lateinit var torGatewayStatus: TextView
    lateinit var internetGatewayStatus: TextView
    lateinit var activeNodesText: TextView
    // lateinit var networkLoadText: TextView
    // lateinit var stabilityText: TextView

    fun bindImmediateViews(view: View) {
        // Cards 1-3: Always present in initial layout
        meshStatusText = view.findViewById(R.id.meshStatusText)
        meshRolesText = view.findViewById(R.id.meshRolesText)
        lastUpdateText = view.findViewById(R.id.lastUpdateText)
        
        sendBroadcastButton = view.findViewById(R.id.sendBroadcastButton)
        meshToggleButton = view.findViewById(R.id.meshToggleButton)
        
        // Mesh control card and new buttons
        meshControlCard = view.findViewById(R.id.meshControlCard)
        meshControlHeader = view.findViewById(R.id.meshControlHeader)
        joinMeshButton = view.findViewById(R.id.joinMeshButton)
        mergeMeshButton = view.findViewById(R.id.mergeMeshButton)
        wifiApConnectionButton = view.findViewById(R.id.wifiApConnectionButton)
        // AP extender button is now part of the immediate mesh control header
        meshExtenderApButton = view.findViewById(R.id.meshExtenderApButton)
        expandCollapseIndicator = view.findViewById(R.id.expandCollapseIndicator)
        
        // Expandable content
        meshExpandableContent = view.findViewById(R.id.meshExpandableContent)
        
        // QR code container
        qrCodeContainer = view.findViewById(R.id.qrCodeContainer)
        qrCodeTitle = view.findViewById(R.id.qrCodeTitle)
        qrCodeSubtitle = view.findViewById(R.id.qrCodeSubtitle)
        qrCodeImageView = view.findViewById(R.id.qrCodeImageView)
        qrCodeNetworkInfo = view.findViewById(R.id.qrCodeNetworkInfo)
        copyNetworkInfoButton = view.findViewById(R.id.copyNetworkInfoButton)
        
        // Camera preview container
        cameraPreviewContainer = view.findViewById(R.id.cameraPreviewContainer)
        cameraPreviewView = view.findViewById(R.id.cameraPreviewView)
        scanningOverlay = view.findViewById(R.id.scanningOverlay)
        scanningStatusText = view.findViewById(R.id.scanningStatusText)
        cancelScanButton = view.findViewById(R.id.cancelScanButton)
        toggleFlashlightButton = view.findViewById(R.id.toggleFlashlightButton)
        
        // Network overview card (Card 3 - always immediate)
        networkOverviewCard = view.findViewById(R.id.networkOverviewCard)
        textUploadBitrate = view.findViewById(R.id.text_upload_bitrate)
        textDownloadBitrate = view.findViewById(R.id.text_download_bitrate)
        textActiveNodeCount = view.findViewById(R.id.text_active_node_count)
        // networkLoadText = view.findViewById(R.id.networkLoadText)
        // stabilityText = view.findViewById(R.id.stabilityText)
    }
    
    fun bindDeferredViews(view: View) {
        // Cards 4-9: Loaded from ViewStub after 300ms
        // nodeInfoText id removed from XML in C4; keep field for compile compat but do not bind
        // nodeInfoText = view.findViewById(R.id.nodeInfoText)
        meshIpAddressText = view.findViewById(R.id.meshIpAddressText)
        meshChipMesh = view.findViewById(R.id.meshChipMesh)
        meshChipSta = view.findViewById(R.id.meshChipSta)
        meshChipAp = view.findViewById(R.id.meshChipAp)
        internetWifiRow = view.findViewById(R.id.internetWifiRow)
        internetWifiIpText = view.findViewById(R.id.internetWifiIpText)
        internetWifiChipSta = view.findViewById(R.id.internetWifiChipSta)
        internetWifiChipWeb = view.findViewById(R.id.internetWifiChipWeb)
        // AP extender button is bound immediately; remaining deferred elements may still exist
        meshExtenderApRow = view.findViewById(R.id.meshExtenderApRow)
        meshExtenderApIpText = view.findViewById(R.id.meshExtenderApIpText)
        meshExtenderApChipAp = view.findViewById(R.id.meshExtenderApChipAp)
        meshExtenderApChipMesh = view.findViewById(R.id.meshExtenderApChipMesh)
        networkStatsText = view.findViewById(R.id.networkStatsText)
        
        torGatewayCard = view.findViewById(R.id.torGatewayCard)
        gatewayToggle = view.findViewById(R.id.gatewayToggle)
        torGatewayStatus = view.findViewById(R.id.torGatewayStatus)
        
        internetGatewayCard = view.findViewById(R.id.internetGatewayCard)
        internetGatewayToggle = view.findViewById(R.id.internetGatewayToggle)
        internetGatewayStatus = view.findViewById(R.id.internetGatewayStatus)
        
        storageParticipationCard = view.findViewById(R.id.storageParticipationCard)
        storageParticipationToggle = view.findViewById(R.id.storageParticipationToggle)
        storageAllocationSlider = view.findViewById(R.id.storageAllocationSlider)
        storageStatusText = view.findViewById(R.id.storageStatusText)
        storageAllocationText = view.findViewById(R.id.storageAllocationText)
        
        storageDropFolderCard = view.findViewById(R.id.storageDropFolderCard)
        selectFolderButton = view.findViewById(R.id.selectFolderButton)
        createFolderButton = view.findViewById(R.id.createFolderButton)
        selectedFolderText = view.findViewById(R.id.selectedFolderText)
        folderContentsRecyclerView = view.findViewById(R.id.folderContentsRecyclerView)
        
        distributedServiceLayerCard = view.findViewById(R.id.distributedServiceLayerCard)
        serviceLayerParticipationSwitch = view.findViewById(R.id.serviceLayerParticipationSwitch)
        serviceLayerStatusText = view.findViewById(R.id.serviceLayerStatusText)
        pythonServiceStatus = view.findViewById(R.id.pythonServiceStatus)
        mlInferenceServiceStatus = view.findViewById(R.id.mlInferenceServiceStatus)
        distributedStorageServiceStatus = view.findViewById(R.id.distributedStorageServiceStatus)
        taskSchedulerServiceStatus = view.findViewById(R.id.taskSchedulerServiceStatus)
    }
}
