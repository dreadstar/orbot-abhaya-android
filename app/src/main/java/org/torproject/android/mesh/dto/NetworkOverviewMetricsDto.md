package org.torproject.android.mesh.dto

data class NetworkOverviewMetricsDto(
    val uploadRateBytesPerSec: Long,
    val downloadRateBytesPerSec: Long,
    val activeNodeCount: Int
)
