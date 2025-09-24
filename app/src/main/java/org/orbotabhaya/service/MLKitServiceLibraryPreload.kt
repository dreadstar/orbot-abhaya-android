package org.orbotabhaya.service

import org.torproject.android.service.compute.LocalDeviceServiceLibrary
import org.torproject.android.service.compute.ResourceRequirements
import org.torproject.android.service.compute.CPUIntensity

object MLKitServiceLibraryPreload {
    fun preload(library: LocalDeviceServiceLibrary) {
        // Populate the local device service library with minimal ML service manifests.
        // Use the canonical ResourceRequirements defined in SupportTypes.kt
        val defaultReqs = ResourceRequirements(
            minRAMMB = 32,
            preferredRAMMB = 64,
            cpuIntensity = CPUIntensity.MODERATE,
            requiresGPU = false,
            requiresNPU = false,
            requiresStorage = true,
            minStorageGB = 0.01f,
            thermalConstraints = setOf(),
            maxNetworkLatencyMs = 1000,
            minBatteryLevel = 25
        )

        fun add(id: String, name: String, tags: List<String>) {
            val manifest = LocalDeviceServiceLibrary.ServiceManifest(
                packageId = id,
                serviceType = name,
                runtimeRequired = emptyList(),
                runtimeOptional = emptyList(),
                deviceProfile = LocalDeviceServiceLibrary.DeviceProfile.MID_RANGE,
                resourceRequirements = defaultReqs
            )
            library.addService(manifest)
        }

        add("mlkit_text_recognition", "Text Recognition", listOf("OCR"))
        add("mlkit_face_detection", "Face Detection", listOf("FaceDetection"))
        add("mlkit_barcode_scanning", "Barcode Scanning", listOf("BarcodeScanning"))
        add("mlkit_image_labeling", "Image Labeling", listOf("ImageLabeling"))
        add("mlkit_object_detection", "Object Detection", listOf("ObjectDetection"))
        add("mlkit_pose_detection", "Pose Detection", listOf("PoseDetection"))
        add("mlkit_language_id", "Language Identification", listOf("LanguageIdentification"))
        add("mlkit_smart_reply", "Smart Reply", listOf("SmartReply"))
        add("mlkit_translation", "Translation", listOf("Translation"))
    }
}
