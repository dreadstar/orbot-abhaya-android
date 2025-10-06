package com.ustadmobile.meshrabiya.service.delegation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessagesTest {

    @Test
    fun resourceRequest_roundtrip() {
        val req = ResourceRequest("req-1", "blob-123", 1024L, "onion:AAA", 2)
        val json = req.toJson()
        val parsed = ResourceRequest.fromJson(json)
        assertEquals(req.requestId, parsed.requestId)
        assertEquals(req.blobId, parsed.blobId)
    assertEquals(req.sizeBytes, parsed.sizeBytes)
        assertEquals(req.originatorId, parsed.originatorId)
        assertEquals(req.targetReplicas, parsed.targetReplicas)
    }

    @Test
    fun resourceOffer_roundtrip() {
        val offer = ResourceOffer("req-1", "onion:BBB", 100000L, 5000L)
        val json = offer.toJson()
        val parsed = ResourceOffer.fromJson(json)
        assertEquals(offer.requestId, parsed.requestId)
        assertEquals(offer.offererId, parsed.offererId)
        assertEquals(offer.availableSpace, parsed.availableSpace)
        assertEquals(offer.estimatedBandwidthBytesPerSec, parsed.estimatedBandwidthBytesPerSec)
    }

    @Test
    fun assignment_roundtrip() {
        val asn = Assignment("req-1", "onion:CCC", "blob-123", "token-xyz")
        val json = asn.toJson()
        val parsed = Assignment.fromJson(json)
        assertEquals(asn.requestId, parsed.requestId)
        assertEquals(asn.assigneeId, parsed.assigneeId)
        assertEquals(asn.blobId, parsed.blobId)
        assertEquals(asn.assignmentToken, parsed.assignmentToken)
    }

    @Test
    fun assignmentResult_roundtrip() {
        val res = AssignmentResult("req-1", "onion:CCC", "blob-123", true, null)
        val json = res.toJson()
        val parsed = AssignmentResult.fromJson(json)
        assertEquals(res.requestId, parsed.requestId)
        assertEquals(res.assigneeId, parsed.assigneeId)
        assertEquals(res.blobId, parsed.blobId)
        assertEquals(res.success, parsed.success)
    }
}
