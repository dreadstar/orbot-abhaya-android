package org.torproject.android.ui.mesh.model

import java.util.Date

/**
 * Represents a notification item for the mesh UI dropdown (broadcast, error, storage, etc.)
 */
data class NotificationItem(
    val id: String,
    val type: NotificationType,
    val title: String,
    val message: String? = null,
    val filePath: String? = null,
    val folderPath: String? = null,
    val createdAt: Long = Date().time,
    val errorMessage: String? = null
)

/**
 * Enum for notification types
 */
enum class NotificationType {
    BROADCAST,
    ERROR,
    STORAGE
}
