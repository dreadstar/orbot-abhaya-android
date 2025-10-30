package com.ustadmobile.meshrabiya.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import com.ustadmobile.meshrabiya.storage.*

/**
 * Android Fragment implementation of your excellent storage participation UI
 * Integrates with the distributed storage system and mesh role management
 */
class StorageParticipationFragment : Fragment() {
    
    private lateinit var storageParticipationManager: StorageParticipationManager
    
    // UI Views
    private lateinit var participationSwitch: Switch
    private lateinit var storageDevicesContainer: LinearLayout
    private lateinit var warningText: TextView
    private lateinit var saveButton: Button
    private lateinit var statusText: TextView
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return createStorageParticipationView()
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize storage manager (would be injected in real implementation)
        // storageParticipationManager = StorageParticipationManager(requireContext(), distributedStorageManager)
        
        setupViews(view)
        observeStorageState()
    }
    
    private fun createStorageParticipationView(): View {
        val context = requireContext()
        
        // Create main container with your styling
        val mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
            setBackgroundColor(android.graphics.Color.parseColor("#667eea"))
        }
        
        // Header
        val headerText = TextView(context).apply {
            text = "🗄️ Storage Participation"
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 60)
        }
        mainContainer.addView(headerText)
        
        // Participation toggle card
        val participationCard = createParticipationToggleCard(context)
        mainContainer.addView(participationCard)
        
        // Storage devices container
        storageDevicesContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        mainContainer.addView(storageDevicesContainer)
        
        // Warning message
        warningText = TextView(context).apply {
            text = "⚠️ Note: Participating in distributed storage will use device resources and may impact battery life. Files will be encrypted and distributed across the mesh network."
            setTextColor(android.graphics.Color.parseColor("#2d3436"))
            setPadding(30, 30, 30, 30)
            setBackgroundColor(android.graphics.Color.parseColor("#ffeaa7"))
            visibility = View.GONE
        }
        mainContainer.addView(warningText)
        
        // Status display
        statusText = TextView(context).apply {
            text = "Storage participation disabled"
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 30, 0, 0)
            gravity = android.view.Gravity.CENTER
        }
        mainContainer.addView(statusText)
        
        // Save button
        saveButton = Button(context).apply {
            text = "Save Settings"
            setBackgroundColor(android.graphics.Color.parseColor("#6c5ce7"))
            setTextColor(android.graphics.Color.WHITE)
            setPadding(30, 30, 30, 30)
            isEnabled = false
        }
        mainContainer.addView(saveButton)
        
        return mainContainer
    }
    
    private fun createParticipationToggleCard(context: android.content.Context): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(android.graphics.Color.parseColor("#f093fb"))
        }
        
        val toggleText = TextView(context).apply {
            text = "Participate in Distributed Storage"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        card.addView(toggleText)
        
        participationSwitch = Switch(context).apply {
            setOnCheckedChangeListener { _, isChecked ->
                onParticipationToggled(isChecked)
            }
        }
        card.addView(participationSwitch)
        
        return card
    }
    
    private fun createStorageDeviceCard(device: StorageDevice, allocation: StorageAllocation): View {
        val context = requireContext()
        
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(android.graphics.Color.parseColor("#a8edea"))
        }
        
        // Device header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        
        val deviceInfo = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val deviceName = TextView(context).apply {
            text = device.name
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#333333"))
        }
        deviceInfo.addView(deviceName)
        
        val devicePath = TextView(context).apply {
            text = device.path
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#666666"))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        deviceInfo.addView(devicePath)
        
        val availableSpace = TextView(context).apply {
            text = "Available: ${device.getFormattedAvailableSpace()}"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        }
        deviceInfo.addView(availableSpace)
        
        header.addView(deviceInfo)
        
        val deviceCheckbox = CheckBox(context).apply {
            isChecked = allocation.enabled
            scaleX = 1.5f
            scaleY = 1.5f
            setOnCheckedChangeListener { _, isChecked ->
                onDeviceToggled(device.id, isChecked)
                updateCardAppearance(card, isChecked)
            }
        }
        header.addView(deviceCheckbox)
        
        card.addView(header)
        
        // Allocation controls
        val allocationControls = createAllocationControls(context, device, allocation)
        allocationControls.visibility = if (allocation.enabled) View.VISIBLE else View.GONE
        card.addView(allocationControls)
        
        // Update card appearance based on enabled state
        updateCardAppearance(card, allocation.enabled)
        
        return card
    }
    
    private fun createAllocationControls(
        context: android.content.Context,
        device: StorageDevice,
        allocation: StorageAllocation
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 30, 0, 0)
        }
        
        // Slider label
        val sliderLabel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        
        val labelText = TextView(context).apply {
            text = "Allocated Storage:"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#555555"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        sliderLabel.addView(labelText)
        
        val valueText = TextView(context).apply {
            text = formatStorageSize(allocation.allocatedMB)
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#555555"))
        }
        sliderLabel.addView(valueText)
        
        container.addView(sliderLabel)
        
        // Allocation slider
        val slider = SeekBar(context).apply {
            max = (device.availableSpaceGB * 1024 * 0.9).toInt() // Max 90% of available space
            progress = allocation.allocatedMB.toInt()
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        valueText.text = formatStorageSize(progress.toLong())
                        updateUsageDisplay(container, device, progress.toLong())
                    }
                }
                
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    seekBar?.let { 
                        onAllocationChanged(device.id, it.progress.toLong())
                    }
                }
            })
        }
        container.addView(slider)
        
        // Usage display
        val usageDisplay = TextView(context).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#666666"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 10, 0, 0)
        }
        updateUsageDisplay(usageDisplay, device, allocation.allocatedMB)
        container.addView(usageDisplay)
        
        return container
    }
    
    private fun setupViews(view: View) {
        saveButton.setOnClickListener {
            onSaveClicked()
        }
    }
    
    private fun observeStorageState() {
        // In a real implementation, observe StateFlow from StorageParticipationManager
        // lifecycleScope.launch {
        //     storageParticipationManager.participationEnabled.collect { enabled ->
        //         updateParticipationUI(enabled)
        //     }
        // }
        
        // lifecycleScope.launch {
        //     storageParticipationManager.availableStorageDevices.collect { devices ->
        //         updateStorageDevicesUI(devices)
        //     }
        // }
    }
    
    private fun updateParticipationUI(enabled: Boolean) {
        participationSwitch.isChecked = enabled
        storageDevicesContainer.visibility = if (enabled) View.VISIBLE else View.GONE
        warningText.visibility = if (enabled) View.VISIBLE else View.GONE
        
        val statusMessage = if (enabled) {
            "Storage participation enabled - sharing with mesh network"
        } else {
            "Storage participation disabled"
        }
        statusText.text = statusMessage
        
        updateSaveButtonState()
    }
    
    private fun updateStorageDevicesUI(devices: List<StorageDevice>) {
        storageDevicesContainer.removeAllViews()
        
        // In real implementation, get allocations from manager
        val allocations = emptyList<StorageAllocation>() // storageParticipationManager.storageAllocations.value
        
        devices.forEach { device ->
            val allocation = allocations.find { it.deviceId == device.id } 
                ?: StorageAllocation(device.id, device.name, device.path, 500L, false)
            
            val deviceCard = createStorageDeviceCard(device, allocation)
            storageDevicesContainer.addView(deviceCard)
            
            // Add margin between cards
            val layoutParams = deviceCard.layoutParams as LinearLayout.LayoutParams
            layoutParams.bottomMargin = 40
            deviceCard.layoutParams = layoutParams
        }
    }
    
    // === EVENT HANDLERS ===
    
    private fun onParticipationToggled(enabled: Boolean) {
        GlobalScope.launch {
            // storageParticipationManager.setParticipationEnabled(enabled)
            updateParticipationUI(enabled)
        }
    }
    
    private fun onDeviceToggled(deviceId: String, enabled: Boolean) {
        GlobalScope.launch {
            // storageParticipationManager.setDeviceEnabled(deviceId, enabled)
            updateSaveButtonState()
        }
    }
    
    private fun onAllocationChanged(deviceId: String, allocatedMB: Long) {
        GlobalScope.launch {
            // storageParticipationManager.updateStorageAllocation(deviceId, allocatedMB)
            updateSaveButtonState()
        }
    }
    
    private fun onSaveClicked() {
        GlobalScope.launch {
            // Save settings and update mesh capabilities
            saveButton.isEnabled = false
            saveButton.text = "Saving..."
            
            try {
                // Settings are saved automatically through the manager
                // Show success message
                Toast.makeText(requireContext(), "Storage settings saved!", Toast.LENGTH_SHORT).show()
                saveButton.text = "Saved!"
                
                // Reset button after delay
                view?.postDelayed({
                    saveButton.text = "Save Settings"
                }, 2000)
                
            } catch (e: Exception) {
                saveButton.text = "Save Settings"
                saveButton.isEnabled = true
                Toast.makeText(requireContext(), "Failed to save settings", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // === UTILITY METHODS ===
    
    private fun updateCardAppearance(card: View, enabled: Boolean) {
        val backgroundColor = if (enabled) {
            android.graphics.Color.parseColor("#c8e6c9") // Light green
        } else {
            android.graphics.Color.parseColor("#a8edea") // Original color
        }
        card.setBackgroundColor(backgroundColor)
    }
    
    private fun updateUsageDisplay(view: View, device: StorageDevice, allocatedMB: Long) {
        if (view is TextView) {
            val percentUsed = (allocatedMB.toFloat() / (device.availableSpaceGB * 1024)) * 100
            view.text = "Using ${percentUsed.toInt()}% of available space"
        } else if (view is LinearLayout) {
            // Find TextView in container
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is TextView && child.text.contains("Using")) {
                    updateUsageDisplay(child, device, allocatedMB)
                    break
                }
            }
        }
    }
    
    private fun updateSaveButtonState() {
        val participationEnabled = participationSwitch.isChecked
        // In real implementation, check if any devices are enabled
        val hasEnabledDevices = true // storageParticipationManager.storageAllocations.value.any { it.enabled }
        
        saveButton.isEnabled = !participationEnabled || (participationEnabled && hasEnabledDevices)
    }
    
    private fun formatStorageSize(mb: Long): String {
        return when {
            mb >= 1024 -> "${(mb / 1024f).toInt()} GB"
            else -> "$mb MB"
        }
    }
}
