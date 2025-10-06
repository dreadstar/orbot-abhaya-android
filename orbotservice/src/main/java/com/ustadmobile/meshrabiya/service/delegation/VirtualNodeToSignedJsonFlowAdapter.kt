package com.ustadmobile.meshrabiya.service.delegation

import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.mmcp.MmcpDelegationMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpMessageFactory
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adapter that bridges a VirtualNode's MMCP delegation messages to a Flow<String> of
 * the verbatim signed JSON payloads. This keeps the sensor module decoupled from
 * VirtualNode/MMCP types by exposing the exact JSON strings received over the mesh.
 *
 * It also exposes a publishSignedJson suspend function that will create an
 * MmcpDelegationMessage (using MmcpMessageFactory) with the provided signed JSON
 * as the jsonPayload and send it via the VirtualNode.
 */
class VirtualNodeToSignedJsonFlowAdapter(
    private val virtualNode: VirtualNode,
    private val signerKeyPair: java.security.KeyPair? = null,
) {

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    // SharedFlow that emits verbatim signed JSON payloads from incoming MmcpDelegationMessage
    // Use replay=1 so a late subscriber will still receive the most recent payload
    // (avoids race where adapter emits before the test subscribes).
    private val _incomingSignedJson = MutableSharedFlow<String>(replay = 1)
    val incomingSignedJsonFlow: Flow<String> = _incomingSignedJson.asSharedFlow()

    // A readiness signal completed when the internal collectors are launched.
    private val ready = CompletableDeferred<Unit>()

    /**
     * Suspend until the adapter is ready to receive messages (its internal collector(s)
     * have been started). Tests should call this instead of using fixed delays.
     */
    suspend fun awaitReady() = ready.await()

    private val incomingSource: Flow<com.ustadmobile.meshrabiya.mmcp.MmcpMessageAndPacketHeader> = virtualNode.incomingMmcpMessages
    private val sendMessageFn: (MmcpMessage) -> Unit = { mmcp -> virtualNode.sendMessage(mmcp) }
    private val nextMsgIdFn: () -> Int = { virtualNode.nextMmcpMessageId() }

    // Test-friendly constructor: allow injecting a source Flow and send/nextId functions
    constructor(
        incomingFlow: Flow<com.ustadmobile.meshrabiya.mmcp.MmcpMessageAndPacketHeader>,
        sendMessage: (MmcpMessage) -> Unit,
        nextMsgId: () -> Int,
        signerKeyPair: java.security.KeyPair? = null,
    ) : this(object : VirtualNode() {
        // Minimal anonymous VirtualNode used only for satisfying primary constructor. We won't use it.
        override val meshrabiyaWifiManager: com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager
            get() = throw UnsupportedOperationException()

        override fun getCurrentFitnessScore(): Int = 0
        override fun getCurrentNodeRole(): Byte = 0
    }, signerKeyPair) {
        // Replace the incomingSource and functions with the injected ones
        @Suppress("UNCHECKED_CAST")
        (this as java.lang.Object)
        // start a collector on the injected incoming flow instead of the virtualNode's flow
        val collectorStarted = CompletableDeferred<Unit>()
        scope.launch {
            // Signal that the collector coroutine has been scheduled
            try { collectorStarted.complete(Unit) } catch (_: Throwable) {}
            try { println("D: VirtualNodeToSignedJsonFlowAdapter (test ctor) collector started") } catch (_: Throwable) {}
            incomingFlow.collect { mmcpAndHeader ->
                val message = mmcpAndHeader.message
                if (message is MmcpDelegationMessage) {
                    try {
                        try { println("D: VirtualNodeToSignedJsonFlowAdapter (test ctor) received MmcpDelegationMessage id=${message.messageId}") } catch (_: Throwable) {}
                        val jsonPayload = message.jsonPayload
                        if (jsonPayload != null) {
                            val ok = _incomingSignedJson.tryEmit(jsonPayload)
                            try { println("D: VirtualNodeToSignedJsonFlowAdapter (test ctor) tryEmit returned=$ok for payload=$jsonPayload") } catch (_: Throwable) {}
                        }
                    } catch (_: Exception) { }
                }
            }
        }
        // Wait for the collector coroutine to start before signalling ready to avoid races
        try { kotlinx.coroutines.runBlocking { collectorStarted.await() } } catch (_: Throwable) {}

        // Override send/next functions by shadowing methods in this instance (used only in publish)
        sendMessageOverride = sendMessage
        nextMsgIdOverride = nextMsgId

        // The anonymous VirtualNode passed to the primary constructor may start background
        // networking threads (datagram socket, servers). In tests we don't need those.
        // Close the temporary virtualNode here to ensure no background threads are left
        // running and causing the test runner to hang. Only after we have attempted to
        // shut it down signal that the adapter is ready for tests.
        try {
            virtualNode.close()
        } catch (_: Throwable) { }

        // Indicate collectors have been started and temporary virtual node closed so tests can proceed.
        ready.complete(Unit)
    }

    // Backing overrides for test constructor
    private var sendMessageOverride: ((MmcpMessage) -> Unit)? = null
    private var nextMsgIdOverride: (() -> Int)? = null

    init {
        // Collect incoming MMCP messages from the real virtual node and forward delegation payloads verbatim
        scope.launch {
            incomingSource.collect { mmcpAndHeader ->
                val message = mmcpAndHeader.message
                if (message is MmcpDelegationMessage) {
                    try {
                        try { println("D: VirtualNodeToSignedJsonFlowAdapter (main) received MmcpDelegationMessage id=${message.messageId}") } catch (_: Throwable) {}
                        // Forward the jsonPayload exactly as received (verbatim)
                        val jsonPayload = message.jsonPayload
                        if (jsonPayload != null) {
                            val ok = _incomingSignedJson.tryEmit(jsonPayload)
                            try { println("D: VirtualNodeToSignedJsonFlowAdapter (main) tryEmit returned=$ok for payload=$jsonPayload") } catch (_: Throwable) {}
                        }
                    } catch (_: Exception) {
                        // ignore malformed payloads
                    }
                }
            }
        }
        // Wait for the virtual node networking to be ready (datagram recv loop and servers)
        // before signalling readiness to callers. This avoids races where tests send
        // messages before underlying sockets are bound.
        scope.launch {
            try {
                // Wait for the virtual node networking to be ready. Tests rely on
                // the actual availability of subcomponents; do not shortcut with a
                // timeout here — if components never signal ready we must investigate.
                try {
                    virtualNode.awaitNetworkReady()
                } catch (_: Exception) {
                    // If awaiting network readiness throws, propagate readiness so tests
                    // do not hang indefinitely. (Exceptions are unexpected here.)
                }
            } finally {
                ready.complete(Unit)
            }
        }
    }

    /**
     * Publish a signed JSON string by wrapping it into an MmcpDelegationMessage and
     * sending via the VirtualNode. The provided signedJson should already contain
     * the signerPublicKey/signature fields (if signed by the caller). If you also
     * provide a signerKeyPair to this adapter the factory will re-sign the payload
     * and attach the adapter's public key instead.
     */
    suspend fun publishSignedJson(signedJson: String) {
        try {
            val msgId = nextMsgIdOverride?.invoke() ?: nextMsgIdFn()
            val mmcp: MmcpMessage = MmcpMessageFactory.createDelegationMessage(
                messageId = msgId,
                jsonPayload = signedJson,
                signerKeyPair = signerKeyPair
            )

            val sender = sendMessageOverride ?: sendMessageFn
            sender(mmcp)
        } catch (_: Exception) {
            // swallow errors - publishing should not crash callers
        }
    }
}
