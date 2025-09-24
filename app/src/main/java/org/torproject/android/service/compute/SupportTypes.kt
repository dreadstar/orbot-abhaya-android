package org.torproject.android.service.compute

// Shared lightweight support types used across compute code.
data class ResourceRequirements(
    val minRAMMB: Int,
    val preferredRAMMB: Int,
    val cpuIntensity: CPUIntensity,
    val requiresGPU: Boolean = false,
    val requiresNPU: Boolean = false,
    val requiresStorage: Boolean = false,
    val minStorageGB: Float = 0f,
    val thermalConstraints: Set<ThermalState> = setOf(ThermalState.COLD, ThermalState.WARM, ThermalState.HOT, ThermalState.CRITICAL),
    val maxNetworkLatencyMs: Int = 1000,
    val minBatteryLevel: Int = 25
)

enum class PythonLibrary {
    NUMPY, PANDAS, OPENCV, PILLOW, SCIKIT_LEARN,
    MATPLOTLIB, SCIPY, REQUESTS, JSON, BASE64
}

data class LiteRTConfig(
    val useGPU: Boolean = false,
    val useNNAPI: Boolean = false,
    val numThreads: Int = 2
)

data class InferenceConfig(
    val batchSize: Int = 1,
    val precision: Precision = Precision.QUANTIZED
)

enum class Precision { FLOAT32, FLOAT16, QUANTIZED }

enum class CPUIntensity { LIGHT, MODERATE, HEAVY, BURST }

enum class ThermalState { COLD, WARM, HOT, CRITICAL }
