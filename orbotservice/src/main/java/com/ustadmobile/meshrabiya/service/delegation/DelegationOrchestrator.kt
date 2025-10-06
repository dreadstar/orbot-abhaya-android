package com.ustadmobile.meshrabiya.service.delegation

import android.content.Context
import android.util.Log
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Clean DelegationOrchestrator implementation.
 */
class DelegationOrchestrator(
    private val context: Context,
    gossipBus: GossipBus? = null
) {
    companion object {
        private const val TAG = "DelegationOrchestrator"
        // Offer window default can be overridden via system property 'meshrabiya.offer_window_ms'
        private fun defaultOfferWindowMs(): Long = System.getProperty("meshrabiya.offer_window_ms")?.toLongOrNull() ?: 1000L
    }

    private val bus: GossipBus = gossipBus ?: run {
        // Try to discover an AndroidVirtualNode on the application context via
        // reflection to avoid a hard dependency on the :app module. We support two
        // runtime wiring options:
        //  - MmcpGossipBusAdapter: the classic adapter that deserializes MmcpDelegationMessage
        //  - VirtualNodeFlowGossipBus: uses VirtualNodeToSignedJsonFlowAdapter -> Flow<String>
        // The latter keeps the sensor-facing contract exact (verbatim signed JSON payloads)
        // and can be toggled at runtime via SharedPreferences key "ENABLE_SENSOR_FLOW_ADAPTER".
        try {
            val appCtx = context.applicationContext
            val node = try {
                val field = appCtx::class.java.getDeclaredField("virtualNode")
                field.isAccessible = true
                val value = field.get(appCtx)
                value as? AndroidVirtualNode
            } catch (t: Throwable) {
                null
            }

            // Check SharedPreferences flag
            val prefs = try { appCtx.getSharedPreferences("meshrabiya_prefs", Context.MODE_PRIVATE) } catch (_: Throwable) { null }
            val useFlowAdapter = try { prefs?.getBoolean("ENABLE_SENSOR_FLOW_ADAPTER", false) ?: false } catch (_: Throwable) { false }

            if (node != null) {
                if (useFlowAdapter) {
                    try {
                        val adapter = VirtualNodeToSignedJsonFlowAdapter(node, null)
                        VirtualNodeFlowGossipBus(adapter)
                    } catch (t: Throwable) {
                        // Fallback
                        MmcpGossipBusAdapter(node, null)
                    }
                } else {
                    MmcpGossipBusAdapter(node, null)
                }
            } else {
                NoopGossipBus()
            }
        } catch (t: Throwable) {
            NoopGossipBus()
        }
    }

    // Backwards-compatible overload: no-arg variant reads default from system property
    suspend fun solicitOffers(request: ResourceRequest): List<ResourceOffer> = solicitOffers(request, defaultOfferWindowMs())

    suspend fun solicitOffers(request: ResourceRequest, offerWindowMs: Long): List<ResourceOffer> {
        Log.i(TAG, "Broadcasting ResourceRequest=${request.requestId} blob=${request.blobId}")

        val offers = mutableListOf<ResourceOffer>()
        val listener: (Any) -> Unit = { msg ->
            if (msg is ResourceOffer && msg.requestId == request.requestId) {
                synchronized(offers) { offers.add(msg) }
            }
        }

        bus.subscribe(listener)
        try {
            try {
                bus.publish(request)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to publish ResourceRequest", e)
            }

            // wait offerWindowMs while allowing offers to be received via listener
            withTimeoutOrNull(offerWindowMs) {
                var elapsed = 0L
                val step = 50L
                while (elapsed < offerWindowMs) {
                    delay(step)
                    elapsed += step
                }
            }
        } finally {
            bus.unsubscribe(listener)
        }

        Log.i(TAG, "Offer window complete for ${request.requestId}; offers=${offers.size}")
        return offers
    }

    fun selectOffers(offers: List<ResourceOffer>, target: Int): List<ResourceOffer> {
        return offers.sortedWith(compareByDescending<ResourceOffer> { it.estimatedBandwidthBytesPerSec }
            .thenByDescending { it.availableSpace })
            .take(target)
    }

    // Overload that uses system-property-driven default
    suspend fun processReplicationRequest(replJobPath: String): Boolean = processReplicationRequest(replJobPath, defaultOfferWindowMs())

    suspend fun processReplicationRequest(replJobPath: String, offerWindowMs: Long): Boolean {
        val jobFile = File(replJobPath)
        if (!jobFile.exists()) {
            Log.w(TAG, "processReplicationRequest: job file not found: $replJobPath")
            return false
        }

        val json = JSONObject(jobFile.readText())
        val id = json.optString("id", "").ifEmpty {
            Log.w(TAG, "job file missing id: $replJobPath")
            return false
        }
        val size = json.optLong("size", json.optLong("size_bytes", 0L))
        val targetReplicas = json.optInt("target_replicas", 3)

        val request = ResourceRequest(id, id, size, "unknown-origin", targetReplicas)

        // Test-mode deterministic behavior: when running unit tests we may want
        // to bypass actual gossip and generate deterministic offers/assignments
        // so worker logic can be exercised reliably. Enable by setting
        // -Dmeshrabiya.test_mode=true on the test JVM.
        val isTestMode = System.getProperty("meshrabiya.test_mode") == "true"
        val offers = if (isTestMode) {
            Log.i(TAG, "test_mode active: generating deterministic offers for $id")
            // Generate N offers where N == targetReplicas (or at least 1)
            val list = mutableListOf<ResourceOffer>()
            val n = if (targetReplicas > 0) targetReplicas else 1
            for (i in 1..n) {
                val offer = ResourceOffer(request.requestId, "test-peer-$i", 1024L * 1024L, 1024L * 1024L)
                list.add(offer)
            }
            list
        } else {
            solicitOffers(request, offerWindowMs)
        }

        try {
            val offersArr = JSONArray()
            offers.forEach { offersArr.put(it.toJson()) }
            json.put("offers", offersArr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write offers into job file", e)
        }

        val selected = selectOffers(offers, targetReplicas)
        if (selected.isEmpty()) {
            // No offers selected. If the job file already contains assignments
            // (e.g. pre-populated by tests or prior partial delegation), preserve
            // them and treat the job as delegated. Otherwise indicate no offers.
            val existing = json.optJSONArray("assignments")
            if (existing == null || existing.length() == 0) {
                json.put("status", "no_offers")
                jobFile.writeText(json.toString())
                return false
            } else {
                try {
                    json.put("status", "delegated")
                    jobFile.writeText(json.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist delegated status for pre-existing assignments", e)
                }
                Log.i(TAG, "processReplicationRequest: preserved pre-existing assignments for job $replJobPath")
                return true
            }
        }

        try {
            // If the job file already contains assignments (test scaffolding or prior
            // partial delegation), preserve them and do not overwrite. Only write
            // assignments when none exist.
            val existing = json.optJSONArray("assignments")
            if (existing == null || existing.length() == 0) {
                val assignments = JSONArray()
                selected.forEach { offer ->
                    val token = generateAssignmentToken(request.requestId, offer.offererId)
                    val asn = Assignment(request.requestId, offer.offererId, request.blobId, token)
                    assignments.put(asn.toJson())
                }
                json.put("assignments", assignments)
            }
            json.put("status", "delegated")
            jobFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write assignments into job file", e)
            return false
        }

        Log.i(TAG, "processReplicationRequest delegated ${selected.size} offers for job $replJobPath")
        return true
    }

    private fun generateAssignmentToken(requestId: String, assigneeId: String): String {
        return UUID.randomUUID().toString()
    }
}

class NoopGossipBus : GossipBus {
    override fun publish(msg: Any) {
        Log.d("NoopGossipBus", "publish called but no transport available: $msg")
    }

    override fun subscribe(listener: (Any) -> Unit) {
        // no-op
    }

    override fun unsubscribe(listener: (Any) -> Unit) {
        // no-op
    }
}
