package org.torproject.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.torproject.android.ui.mesh.EnhancedMeshFragment
import com.ustadmobile.meshrabiya.api.MeshrabiyaApi
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import android.widget.Toast
import android.content.Intent
import android.net.Uri
/**
 * Main activity for mesh integration demo. Handles UI and gateway capability toggling.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var meshrabiyaApi: MeshrabiyaApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // --- Use MeshrabiyaApi singleton ---
    meshrabiyaApi = MeshrabiyaApiImpl.getInstance()

        // --- Setup EnhancedMeshFragment UI callback integration ---
        val meshFragment = supportFragmentManager.findFragmentById(R.id.meshFragment) as? EnhancedMeshFragment
        meshFragment?.let {
            // Example: Register file retrieved callback to update UI
            meshrabiyaApi.setOnFileRetrieved { fileId, file ->
                // it.onFileRetrieved(fileId, file) // Implement this method in EnhancedMeshFragment if needed
            }
        }

        // --- Handle drop folder selection intent ---
        handleDropFolderIntent(intent)
    }

    // --- NEW CODE: Handle drop folder selection from UI or external intent ---
    private fun handleDropFolderIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            val folderPath = getFolderPathFromUri(uri)
            if (folderPath != null) {
                meshrabiyaApi.selectDropFolder(folderPath) { result ->
                    if (result.isSuccess) {
                        Toast.makeText(this, "Drop folder set: $folderPath", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Failed to set drop folder: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // --- NEW CODE: Utility to convert Uri to file system path ---
    private fun getFolderPathFromUri(uri: Uri): String? {
        // Implementation depends on Android version and storage access framework
        // For SAF, you may need to use DocumentFile or ContentResolver
        // This is a stub for demonstration; replace with actual logic
        return uri.path
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // --- NEW CODE: Handle new drop folder selection while app is running ---
        handleDropFolderIntent(intent)
    }
}
