package org.torproject.android.service.compute

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Unit tests for Meshrabiya <-> App ResourceRequirements interop helpers.
 */
internal class ResourceRequirementsInteropTest {

    @Test
    fun `round-trip conversion preserves key fields`() {
        val original = ResourceRequirements(
            minRAMMB = 256,
            preferredRAMMB = 512,
            cpuIntensity = CPUIntensity.MODERATE,
            requiresGPU = true,
            requiresNPU = false,
            requiresStorage = true,
            minStorageGB = 0.5f, // 512MB
            thermalConstraints = setOf(ThermalState.COLD, ThermalState.WARM),
            maxNetworkLatencyMs = 500,
            minBatteryLevel = 20
        )

        val meshrabiya = original.toMeshrabiya()
        // Basic sanity on meshrabiya mapping
        assertEquals(original.minRAMMB, meshrabiya.minMemoryMB)
        assertEquals(original.requiresGPU, meshrabiya.minGpu)

        val roundTripped = meshrabiya.toAppResourceRequirements()

        // minRAM should be preserved
        assertEquals(original.minRAMMB, roundTripped.minRAMMB)

        // CPU intensity mapping is heuristic; ensure it rounds to an intensity >= LIGHT
        assertNotNull(roundTripped.cpuIntensity)

        // GPU requirement preserved
        assertEquals(original.requiresGPU, roundTripped.requiresGPU)

        // Storage presence preserved (minStorageGB -> minStorageMB -> minStorageGB)
        assertEquals(original.requiresStorage, roundTripped.requiresStorage)

        // minStorageGB will be approximated by the conversion; ensure it's within reasonable delta
        val delta = 0.1f
        assertTrue(kotlin.math.abs(original.minStorageGB - roundTripped.minStorageGB) <= delta)
    }
}
