package com.ustadmobile.meshrabiya.service.delegation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Adapter that exposes a GossipBus interface backed by a VirtualNodeToSignedJsonFlowAdapter.
 * It forwards incoming signed JSON payloads to listeners by parsing the wrapper and
 * delivering the inner payload as ResourceOffer/Request/Assignment objects when possible.
 */
class VirtualNodeFlowGossipBus(
    private val adapter: VirtualNodeToSignedJsonFlowAdapter
) : GossipBus {

    private val listeners = mutableListOf<(Any) -> Unit>()
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    init {
        scope.launch {
            adapter.incomingSignedJsonFlow.collect { signedJson ->
                try {
                    // signedJson is the wrapper that contains __delegation_type + payload
                    val jo = JSONObject(signedJson)
                    val type = jo.optString("__delegation_type")
                    val payload = jo.opt("payload")
                    val obj = when (type) {
                        "ResourceRequest" -> if (payload is JSONObject) ResourceRequest.fromJson(payload) else null
                        "ResourceOffer" -> if (payload is JSONObject) ResourceOffer.fromJson(payload) else null
                        "Assignment" -> if (payload is JSONObject) Assignment.fromJson(payload) else null
                        "AssignmentResult" -> if (payload is JSONObject) AssignmentResult.fromJson(payload) else null
                        else -> null
                    }

                    if (obj != null) {
                        synchronized(listeners) { listeners.forEach { it.invoke(obj) } }
                    }
                } catch (_: Throwable) {
                    // ignore malformed payloads
                }
            }
        }
    }

    override fun publish(msg: Any) {
        // Wrap payload into canonical delegation wrapper and ask adapter to publish signed json.
        try {
            val wrapper = JSONObject()
            val payloadJson = when (msg) {
                is ResourceRequest -> msg.toJson()
                is ResourceOffer -> msg.toJson()
                is Assignment -> msg.toJson()
                is AssignmentResult -> msg.toJson()
                else -> null
            }
            if (payloadJson == null) return

            wrapper.put("__delegation_type", when (msg) {
                is ResourceRequest -> "ResourceRequest"
                is ResourceOffer -> "ResourceOffer"
                is Assignment -> "Assignment"
                is AssignmentResult -> "AssignmentResult"
                else -> "Unknown"
            })
            wrapper.put("payload", payloadJson)

            // The adapter.publishSignedJson is suspend; call it from a coroutine so publish
            // remains a non-suspending API on GossipBus.
            scope.launch { adapter.publishSignedJson(wrapper.toString()) }
        } catch (_: Throwable) { }
    }

    override fun subscribe(listener: (Any) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun unsubscribe(listener: (Any) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }
}
