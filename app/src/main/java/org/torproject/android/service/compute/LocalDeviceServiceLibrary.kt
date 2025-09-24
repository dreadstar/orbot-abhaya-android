package org.torproject.android.service.compute

/**
 * LocalDeviceServiceLibrary
 *
 * Implements manifest-driven runtime selection, device profiling, and modular runtime management for distributed compute services.
 * Default: JVM (Kotlin/Java) and native (C/C++ via NDK) always available.
 * Optional: Python, Node.js, Rust, Go, WASM supported as modular downloads for capable devices.
 *
 * Usage: Validate service manifest, select runtimes, and manage user opt-in for advanced runtimes.
 */
object LocalDeviceServiceLibrary {
    enum class DeviceProfile { FLAGSHIP, MID_RANGE, BUDGET }
    enum class Runtime { JVM, NATIVE, PYTHON, NODEJS, RUST, GO, WASM }

    data class ServiceManifest(
        val packageId: String,
        val serviceType: String,
        val runtimeRequired: List<Runtime>,
        val runtimeOptional: List<Runtime>,
        val deviceProfile: DeviceProfile,
        val resourceRequirements: ResourceRequirements
    )

    /**
     * In-memory service library storage. Replace with persistent storage as needed.
     */
    private val serviceLibrary = mutableListOf<ServiceManifest>()

    /**
     * Add a service manifest to the library.
     */
    fun addService(manifest: ServiceManifest) {
        serviceLibrary.add(manifest)
    }

    /**
     * Retrieve a service manifest by packageId.
     */
    fun getServiceByPackageId(packageId: String): ServiceManifest? =
        serviceLibrary.find { it.packageId == packageId }

    /**
     * Query services by serviceType.
     */
    fun findServicesByType(serviceType: String): List<ServiceManifest> =
        serviceLibrary.filter { it.serviceType.equals(serviceType, ignoreCase = true) }

    /**
     * Query services by required runtime.
     */
    fun findServicesByRuntime(runtime: Runtime): List<ServiceManifest> =
        serviceLibrary.filter { it.runtimeRequired.contains(runtime) || it.runtimeOptional.contains(runtime) }

    /**
     * Returns the list of available runtimes for the current device profile.
     */
    fun getAvailableRuntimes(profile: DeviceProfile): List<Runtime> = when (profile) {
        DeviceProfile.FLAGSHIP -> Runtime.values().toList()
        DeviceProfile.MID_RANGE -> listOf(Runtime.JVM, Runtime.NATIVE, Runtime.PYTHON, Runtime.NODEJS)
        DeviceProfile.BUDGET -> listOf(Runtime.JVM, Runtime.NATIVE)
    }

    /**
     * Validates if the service can be installed on the current device profile.
     * Warns or rejects if required runtimes are not available.
     */
    fun validateService(manifest: ServiceManifest, profile: DeviceProfile): ValidationResult {
        val available = getAvailableRuntimes(profile)
        val missing = manifest.runtimeRequired.filter { it !in available }
        return when {
            missing.isEmpty() -> ValidationResult.Valid
            else -> ValidationResult.Rejected("Missing required runtimes: $missing for profile $profile")
        }
    }

    /**
     * Allows user to opt-in to install extra runtimes for advanced services.
     */
    fun userOptInRuntimes(profile: DeviceProfile, optIn: List<Runtime>): List<Runtime> {
        val available = getAvailableRuntimes(profile)
        return available + optIn.filter { it !in available }
    }

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Rejected(val reason: String) : ValidationResult()
    }
}
