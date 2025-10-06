package com.ustadmobile.meshrabiya.service.delegation

import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpDelegationMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpMessageFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetAddress

/**
 * Production GossipBus adapter that bridges delegation messages to the MMCP transport.
 *
 * Note: For minimal invasive change we serialize delegation messages to JSON and wrap
 * them in an MmcpEmergencyBroadcast payload (WHAT_EMERGENCY_BROADCAST). In future this
 * should be replaced by a dedicated mmcp payload for delegation messages.
 */
class MmcpGossipBusAdapter(
    private val virtualNode: VirtualNode,
    // Optional KeyPair for signing outgoing delegation messages. When provided the
    // MmcpMessageFactory will attach the public key bytes and signature.
    private val signerKeyPair: java.security.KeyPair? = null,
) : GossipBus {

    private val listeners = mutableListOf<(Any) -> Unit>()
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    init {
        // Listen for incoming MMCP messages and dispatch any delegation payloads
        scope.launch {
            virtualNode.incomingMmcpMessages.collect { mmcpAndHeader ->
                val mmcpMessage = mmcpAndHeader.message
                // We only handle delegation message wrapper
                if (mmcpMessage is MmcpDelegationMessage) {
                    // Verify signature / signer if present. If verification fails, ignore message.
                    try {
                        if (!mmcpMessage.verify()) {
                            // ignore unsigned/invalid messages
                            return@collect
                        }
                    } catch (_: Exception) {
                        return@collect
                    }
                    try {
                        val json = JSONObject(mmcpMessage.jsonPayload)
                        val type = json.optString("__delegation_type")
                        val payload = when (type) {
                            "ResourceRequest" -> ResourceRequest.fromJson(json.getJSONObject("payload"))
                            "ResourceOffer" -> ResourceOffer.fromJson(json.getJSONObject("payload"))
                            "Assignment" -> Assignment.fromJson(json.getJSONObject("payload"))
                            "AssignmentResult" -> AssignmentResult.fromJson(json.getJSONObject("payload"))
                            else -> null
                        }

                        if (payload != null) {
                            // deliver to listeners on this bus
                            synchronized(listeners) {
                                listeners.forEach { it.invoke(payload) }
                            }
                        }
                    } catch (_: Exception) {
                        // ignore non-JSON or other parse errors
                    }
                }
            }
        }
    }

    override fun publish(msg: Any) {
        try {
            val wrapper = JSONObject()
            val payloadJson = when (msg) {
                is ResourceRequest -> JSONObject(msg.toJson().toString())
                is ResourceOffer -> JSONObject(msg.toJson().toString())
                is Assignment -> JSONObject(msg.toJson().toString())
                is AssignmentResult -> JSONObject(msg.toJson().toString())
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

            // Create an MMCP delegation message with the JSON string as the payload
            val msgId = virtualNode.nextMmcpMessageId()
            val mmcp = MmcpMessageFactory.createDelegationMessage(
                messageId = msgId,
                jsonPayload = wrapper.toString(),
                signerKeyPair = signerKeyPair
            )
            virtualNode.sendMessage(mmcp)
        } catch (_: Exception) {
            // swallow; publishing to mesh shouldn't crash callers
        }
    }

    override fun subscribe(listener: (Any) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun unsubscribe(listener: (Any) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }
}
