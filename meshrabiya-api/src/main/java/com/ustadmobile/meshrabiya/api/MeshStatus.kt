package com.ustadmobile.meshrabiya.api


data class MeshStatus(
    val meshReachable: Boolean,
    val storageAvailable: Boolean,
    val computeAvailable: Boolean,
    val nodeCount: Int,
    val lastSeenTimestampMs: Long,
    val localNodeReady: Boolean,
)
