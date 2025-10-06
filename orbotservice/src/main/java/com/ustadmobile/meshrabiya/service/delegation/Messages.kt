package com.ustadmobile.meshrabiya.service.delegation

import org.json.JSONObject

/**
 * Simple message models for decentralized delegation flows.
 * These are intentionally minimal and JSON-serializable to be used in gossip messages.
 */
data class ResourceRequest(
    val requestId: String,
    val blobId: String,
    val sizeBytes: Long,
    val originatorId: String,
    val targetReplicas: Int = 3
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("blobId", blobId)
        put("size", sizeBytes)
        put("originatorId", originatorId)
        put("targetReplicas", targetReplicas)
    }

    companion object {
        fun fromJson(json: JSONObject): ResourceRequest = ResourceRequest(
            json.getString("requestId"),
            json.getString("blobId"),
            json.getLong("size"),
            json.optString("originatorId", ""),
            json.optInt("targetReplicas", 3)
        )
    }
}

data class ResourceOffer(
    val requestId: String,
    val offererId: String,
    val availableSpace: Long,
    val estimatedBandwidthBytesPerSec: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("offererId", offererId)
        put("availableSpace", availableSpace)
        put("estimatedBandwidthBytesPerSec", estimatedBandwidthBytesPerSec)
    }

    companion object {
        fun fromJson(json: JSONObject): ResourceOffer = ResourceOffer(
            json.getString("requestId"),
            json.optString("offererId", ""),
            json.optLong("availableSpace", 0L),
            json.optLong("estimatedBandwidthBytesPerSec", 0L)
        )
    }
}

data class Assignment(
    val requestId: String,
    val assigneeId: String,
    val blobId: String,
    val assignmentToken: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("assigneeId", assigneeId)
        put("blobId", blobId)
        put("token", assignmentToken)
    }

    companion object {
        fun fromJson(json: JSONObject): Assignment = Assignment(
            json.getString("requestId"),
            json.optString("assigneeId", ""),
            json.getString("blobId"),
            json.optString("token", "")
        )
    }
}

data class AssignmentResult(
    val requestId: String,
    val assigneeId: String,
    val blobId: String,
    val success: Boolean,
    val message: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("assigneeId", assigneeId)
        put("blobId", blobId)
        put("success", success)
        if (message != null) put("message", message)
    }

    companion object {
        fun fromJson(json: JSONObject): AssignmentResult = AssignmentResult(
            json.getString("requestId"),
            json.optString("assigneeId", ""),
            json.getString("blobId"),
            json.optBoolean("success", false),
            json.optString("message", null)
        )
    }
}
// End of file
