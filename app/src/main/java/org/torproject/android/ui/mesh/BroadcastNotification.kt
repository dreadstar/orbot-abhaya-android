package org.torproject.android.ui.mesh

/**
 * Data model for a received broadcast notification
 */
data class BroadcastNotification(
    val broadcastId: String,
    val senderNodeId: String,
    val messageText: String,
    val fileName: String,
    val filePath: String,
    val timestamp: Long,
    val hasError: Boolean,
    val errorMessage: String?
)
