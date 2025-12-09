package org.torproject.android.ui.mesh.model

import java.util.*
import java.io.File
import com.ustadmobile.meshrabiya.vnet.MeshFile

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

    companion object {
        /**
         * Convert a MeshFile to a StorageItem for UI display.
         */
        fun fromMeshFile(meshFile: MeshFile): StorageItem {
            return StorageItem(
                name = meshFile.fileName,
                path = meshFile.fileId,
                isDirectory = false,
                size = meshFile.fileSize,
                lastModified = meshFile.storedAt,
                isShared = false,
                sharedWith = emptySet()
            )
        }
    }
    

    

}

/**
 * Extension function to convert File to StorageItem for UI display
 */

/**
 * Universal conversion helper: Converts any supported mesh file type to StorageItem.
 */
fun Any.toStorageItem(): StorageItem = when (this) {
    is com.ustadmobile.meshrabiya.vnet.MeshFile -> StorageItem.fromMeshFile(this)
    is java.io.File -> StorageItem(
        name = this.name,
        path = this.absolutePath,
        isDirectory = this.isDirectory,
        size = if (this.isFile) this.length() else 0L,
        lastModified = this.lastModified(),
        isShared = false,
        sharedWith = emptySet()
    )
    else -> throw IllegalArgumentException("Unsupported type for conversion to StorageItem: ${this::class.java}")
}

/**
 * Extension function to convert MeshFile to StorageItem
 */
fun MeshFile.toStorageItem(): StorageItem = StorageItem.fromMeshFile(this)
/**
    * Get formatted file size string
    */
fun StorageItem.getFormattedSize(): String {
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
fun StorageItem.getFormattedDate(): String {
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
fun StorageItem.getFileExtension(): String {
    return if (this.isDirectory) {
        "folder"
    } else {
        this.name.substringAfterLast('.', "").lowercase()
    }
}

