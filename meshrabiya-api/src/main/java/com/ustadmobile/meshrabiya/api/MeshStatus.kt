package com.ustadmobile.meshrabiya.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MeshStatus(
    val meshReachable: Boolean,
    val storageAvailable: Boolean,
    val computeAvailable: Boolean,
    val nodeCount: Int,
    val lastSeenTimestampMs: Long,
    val localNodeReady: Boolean,
) : Parcelable
