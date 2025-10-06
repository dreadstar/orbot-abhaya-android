package com.ustadmobile.meshrabiya.service.delegation

import com.ustadmobile.meshrabiya.mmcp.MmcpDelegationMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpMessageAndPacketHeader
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.util.Base64

class VirtualNodeToSignedJsonFlowAdapterTest {

    @Test
    fun incomingSignedJson_isForwardedVerbatim() = runBlocking {
        val incoming = MutableSharedFlow<com.ustadmobile.meshrabiya.mmcp.MmcpMessageAndPacketHeader>(replay = 8)
        var capturedSent: MmcpMessage? = null

        val adapter = VirtualNodeToSignedJsonFlowAdapter(
            incomingFlow = incoming,
            sendMessage = { msg -> capturedSent = msg },
            nextMsgId = { 123 },
            signerKeyPair = null
        )

    // wait until the adapter signals it's ready (collectors started)
    adapter.awaitReady()

        val payload = "{\"__delegation_type\":\"ResourceOffer\",\"payload\":{\"id\":\"abc\"}}"
        val mmcp = MmcpDelegationMessage(
            messageId = 1,
            jsonPayload = payload,
            signerPublicKey = null,
            signature = null
        )

        incoming.emit(MmcpMessageAndPacketHeader(mmcp, VirtualPacketHeader(
            toAddr = 0, toPort = 0, fromAddr = 0, fromPort = 0, lastHopAddr = 0, hopCount = 0, maxHops = 0, payloadSize = 0
        )))

        val received = adapter.incomingSignedJsonFlow.first()
        assertEquals(payload, received)
    }

    @Test
    fun publishSignedJson_sendsMmcpWithSamePayload() = runBlocking {
        val incoming = MutableSharedFlow<com.ustadmobile.meshrabiya.mmcp.MmcpMessageAndPacketHeader>(replay = 8)
        var capturedSent: MmcpMessage? = null

        // Generate an Ed25519 keypair for the factory
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()

        val adapter = VirtualNodeToSignedJsonFlowAdapter(
            incomingFlow = incoming,
            sendMessage = { msg -> capturedSent = msg },
            nextMsgId = { 123 },
            signerKeyPair = kp
        )

    // wait until the adapter signals it's ready (collectors started)
    adapter.awaitReady()

        val payloadJson = "{\"__delegation_type\":\"ResourceOffer\",\"payload\":{\"id\":\"xyz\"}}"

        adapter.publishSignedJson(payloadJson)

        val sent = capturedSent
        assertNotNull(sent)
        assertTrue(sent is MmcpDelegationMessage)
        val sentMmcp = sent as MmcpDelegationMessage
        assertEquals(payloadJson, sentMmcp.jsonPayload)
        assertNotNull(sentMmcp.signature)
        assertNotNull(sentMmcp.signerPublicKey)
    }
}
