    // downloadSharedItem implementation is provided as a member method lower in this file; remove the orphaned duplicate.
package org.torproject.android.service.storage

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.torproject.android.ui.mesh.model.StorageItem
import java.io.File

/**
 * Manages storage drop folder functionality for mesh networking
 */
class StorageDropFolderManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: StorageDropFolderManager? = null
        private const val PREFS_NAME = "mesh_storage_drop_folder"
        private const val KEY_SELECTED_FOLDER_URI = "selected_folder_uri"
        private const val KEY_SELECTED_FOLDER_PATH = "selected_folder_path"
        private const val KEY_SELECTED_FOLDER_NAME = "selected_folder_name"

        fun getInstance(context: Context): StorageDropFolderManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StorageDropFolderManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        // Helper used by compute layer for quick folder listing (returns top-level folder names)
        fun getSubfolders(path: String): List<String> {
            // Minimal safe implementation: return a few defaults if no selected folder
            return listOf("/DropFolder", "/DropFolder/SharedWithMe")
        }
    }

    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get the currently selected folder URI
     */
    fun getSelectedFolderUri(): String? {
        return sharedPrefs.getString(KEY_SELECTED_FOLDER_URI, null)
    }

    /**
     * Get the currently selected folder path
     */
    fun getSelectedFolderPath(): String? {
        return sharedPrefs.getString(KEY_SELECTED_FOLDER_PATH, null)
    }

    /**
     * Get the currently selected folder display name
     */
    fun getSelectedFolderName(): String? {
        return sharedPrefs.getString(KEY_SELECTED_FOLDER_NAME, null)
    }

    /**
     * Set the selected folder information
     */
    fun setSelectedFolder(uri: String?, path: String?, displayName: String?) {
        sharedPrefs.edit()
            .putString(KEY_SELECTED_FOLDER_URI, uri)
            .putString(KEY_SELECTED_FOLDER_PATH, path)
            .putString(KEY_SELECTED_FOLDER_NAME, displayName)
            .apply()
    }

    /**
     * Get the contents of the currently selected folder
     */
    fun getFolderContents(): List<StorageItem> {
        val folderUri = getSelectedFolderUri()
        val folderPath = getSelectedFolderPath()
        
        return when {
            // Try URI-based access first (preferred for SAF)
            folderUri != null -> getFolderContentsFromUri(folderUri)
            // Fallback to file path access
            folderPath != null -> getFolderContentsFromPath(folderPath)
            else -> emptyList()
        }
    }
    
    /**
     * Get folder contents using Storage Access Framework URI
     */
    private fun getFolderContentsFromUri(uriString: String): List<StorageItem> {
        try {
            val uri = Uri.parse(uriString)
            val folder = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
            
            if (!folder.exists() || !folder.isDirectory) {
                return emptyList()
            }
            
            return folder.listFiles().mapNotNull { documentFile ->
                try {
                    StorageItem(
                        name = documentFile.name ?: "Unknown",
                        path = documentFile.uri.toString(), // Use URI as path
                        isDirectory = documentFile.isDirectory,
                        size = if (documentFile.isFile) documentFile.length() else 0L,
                        lastModified = documentFile.lastModified(),
                        isShared = false, // TODO: Check actual sharing status
                        sharedWith = emptySet() // TODO: Get actual sharing info
                    )
                } catch (e: Exception) {
                    null // Skip files that can't be accessed
                }
            }.sortedWith(compareBy<StorageItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
            
        } catch (e: Exception) {
            return emptyList()
        }
    }
    
    /**
     * Get folder contents using traditional file path access
     */
    private fun getFolderContentsFromPath(folderPath: String): List<StorageItem> {
        try {
            val folder = File(folderPath)
            if (!folder.exists() || !folder.isDirectory) {
                return emptyList()
            }

            return folder.listFiles()?.map { file ->
                StorageItem(
                    name = file.name,
                    path = file.absolutePath,
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0L,
                    lastModified = file.lastModified(),
                    isShared = false, // TODO: Check actual sharing status
                    sharedWith = emptySet() // TODO: Get actual sharing info
                )
            }?.sortedWith(compareBy<StorageItem> { !it.isDirectory }.thenBy { it.name.lowercase() }) 
                ?: emptyList()
        } catch (e: Exception) {
            // Handle permission or other errors
            return emptyList()
        }
    }

    /**
     * Create a new folder in the selected directory
     */
    fun createFolder(folderName: String): Boolean {
        val folderUri = getSelectedFolderUri()
        val folderPath = getSelectedFolderPath()
        
        return when {
            // Try URI-based creation first (preferred for SAF)
            folderUri != null -> createFolderWithUri(folderUri, folderName)
            // Fallback to file path creation
            folderPath != null -> createFolderWithPath(folderPath, folderName)
            else -> false
        }
    }
    
    /**
     * Create folder using Storage Access Framework URI
     */
    private fun createFolderWithUri(uriString: String, folderName: String): Boolean {
        try {
            val uri = Uri.parse(uriString)
            val parentFolder = DocumentFile.fromTreeUri(context, uri) ?: return false
            
            val newFolder = parentFolder.createDirectory(folderName)
            return newFolder != null
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * Create folder using traditional file path
     */
    private fun createFolderWithPath(parentPath: String, folderName: String): Boolean {
        try {
            val newFolder = File(parentPath, folderName)
            return newFolder.mkdirs()
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Get sharing status for a specific item
     */
    fun getItemSharingStatus(item: StorageItem): Pair<Boolean, Set<String>> {
        // TODO: Implement actual sharing status retrieval
        // This would connect to the mesh service to get sharing information
        return Pair(false, emptySet())
    }

    /**
     * Download a shared item to the local SharedWithMe subfolder in the Drop Folder
     */
    fun downloadSharedItem(item: StorageItem): Boolean {
        return try {
            val sharedWithMeFolderName = "SharedWithMe"
            val folderUri = getSelectedFolderUri()
            val folderPath = getSelectedFolderPath()

            // Determine destination (SAF or file path)
            if (folderUri != null) {
                val dropFolder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
                val sharedWithMeFolder = dropFolder?.findFile(sharedWithMeFolderName)
                    ?: dropFolder?.createDirectory(sharedWithMeFolderName)
                if (sharedWithMeFolder != null) {
                    // Copy file using SAF
                    val sourceFile = DocumentFile.fromSingleUri(context, Uri.parse(item.path))
                    if (sourceFile != null && sourceFile.isFile) {
                        val inputStream = context.contentResolver.openInputStream(sourceFile.uri)
                        val destFile = sharedWithMeFolder.createFile("application/octet-stream", item.name)
                        val outputStream = destFile?.let { context.contentResolver.openOutputStream(it.uri) }
                        if (inputStream != null && outputStream != null) {
                            inputStream.copyTo(outputStream)
                            inputStream.close()
                            outputStream.close()
                            return true
                        }
                    }
                }
            } else if (folderPath != null) {
                val sharedWithMeDir = File(folderPath, sharedWithMeFolderName)
                if (!sharedWithMeDir.exists()) sharedWithMeDir.mkdirs()
                val sourceFile = File(item.path)
                val destFile = File(sharedWithMeDir, item.name)
                if (sourceFile.exists() && sourceFile.isFile) {
                    sourceFile.inputStream().use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Share an item with specific users/devices
     */
    fun shareItem(item: StorageItem, shareWith: Set<String>): Boolean {
        // TODO: Implement actual sharing functionality
        // This would connect to the mesh service to share the item
        return true
    }

    /**
     * Stop sharing an item
     */
    fun stopSharingItem(item: StorageItem): Boolean {
        // TODO: Implement actual stop sharing functionality
        return true
    }

    /**
     * Check if a folder is valid and accessible
     */
    fun isValidFolder(path: String): Boolean {
        try {
            val folder = File(path)
            return folder.exists() && folder.isDirectory && folder.canRead()
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Get friendly display name for a folder path
     */
    fun getFriendlyFolderName(path: String): String {
        return try {
            val file = File(path)
            when {
                path.contains("/Download") -> "Downloads/${file.name}"
                path.contains("/Documents") -> "Documents/${file.name}"
                path.contains("/Pictures") -> "Pictures/${file.name}"
                path.contains("/DCIM") -> "Camera/${file.name}"
                path.contains("/Music") -> "Music/${file.name}"
                path.contains("/Movies") -> "Movies/${file.name}"
                else -> file.name
            }
        } catch (e: Exception) {
            File(path).name
        }
    }
}
