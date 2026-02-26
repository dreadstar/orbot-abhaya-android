package org.torproject.android.ui.mesh.model

import java.util.Date

/**
 * Represents a notification item for the mesh UI dropdown (broadcast, error, storage, etc.)
 */
interface NotificationSource {
    val id: String
    val title: String
    val createdAt: Long
}

data class BroadcastNotification(
    override val id: String,
    override val title: String,
    override val createdAt: Long,
    val message: String,
    val filePath: String?,
    val senderNodeId: String
) : NotificationSource

data class StatusNotification(
    override val id: String,
    override val title: String,
    override val createdAt: Long,
    val statusMessage: String
) : NotificationSource

data class StorageNotification(
    override val id: String,
    override val title: String,
    override val createdAt: Long,
    val folderPath: String
) : NotificationSource

/**
 * Enum for notification types
 */
enum class NotificationType {
    BROADCAST, // Received Broadcasts (Message and/or File )
    STATUS, // Error and Completion messages from Broadcast, Compute, Storage, Contacts
    STORAGE, // Notification of Files shared via Distributed Store
    COMPUTE, // Notification of Compute Task completion and results delivery
    CONTACTS // Notification of Contacts Messages  and Contact added/removed
}

data class NotificationFeedEntry(
    val type: NotificationType,
    val id: String,
    val title: String,
    val createdAt: Long,
    val message: String? = null,
    val filePath: String? = null,
    val senderNodeId: String? = null,

    val folderPath: String? = null
)

fun BroadcastNotification.toFeedEntry() = NotificationFeedEntry(
    type = NotificationType.BROADCAST,
    id = id,
    title = title,
    createdAt = createdAt,
    message = message,
    filePath = filePath,
    senderNodeId = senderNodeId
)

fun StatusNotification.toFeedEntry() = NotificationFeedEntry(
    type = NotificationType.STATUS,
    id = id,
    title = title,
    createdAt = createdAt,
    message = statusMessage
)

fun StorageNotification.toFeedEntry() = NotificationFeedEntry(
    type = NotificationType.STORAGE,
    id = id,
    title = title,
    createdAt = createdAt,
    folderPath = folderPath
)
