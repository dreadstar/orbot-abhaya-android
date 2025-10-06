package org.torproject.android.service.compute

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * Production StreamIngestor that writes sensor segments into DistributedStorageAgent
 * using streaming-to-disk semantics (no large in-memory buffers) and creates
 * the same durable artifacts as the service-side store (via DistributedStorageAgent APIs).
 */
class StreamIngestorImpl(private val storageAgent: DistributedStorageAgent) {

    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
    }

    fun stop() {
        running = false
    }

    fun ingestSensorReading(streamId: String, timestampMs: Long, payload: ByteArray) {
        if (!running) return
        // Offload to IO coroutine; create a file id and stream via agent
        scope.launch {
            try {
                val fileId = UUID.randomUUID().toString()
                val fileName = "${streamId}_${timestampMs}.seg"
                val inputSupplier = { ByteArrayInputStream(payload) as java.io.InputStream }
                storageAgent.storeBlobFromInputStreamSupplier(fileId, fileName, replicationFactor = 1, tags = setOf("sensor"), inputSupplier = inputSupplier)
            } catch (e: Exception) {
                // best-effort: swallow errors but could add logging
            }
        }
    }
}
