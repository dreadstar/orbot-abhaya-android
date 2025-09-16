package org.torproject.android.ui.mesh.model

import java.util.*

/**
 * Represents a file or folder in the storage drop folder
 */
data class StorageItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val isShared: Boolean = false,
    val sharedWith: Set<String> = emptySet()
) {
    
    /**
     * Get formatted file size string
     */
    fun getFormattedSize(): String {
        if (isDirectory) return "Folder"
        
        val bytes = size
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
    
    /**
     * Get formatted last modified date
     */
    fun getFormattedDate(): String {
        return if (lastModified > 0) {
            val date = Date(lastModified)
            java.text.SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(date)
        } else {
            "Unknown"
        }
    }
    
    /**
     * Get file extension for icon determination
     */
    fun getFileExtension(): String {
        return if (isDirectory) {
            "folder"
        } else {
            name.substringAfterLast('.', "").lowercase()
        }
    }
}
