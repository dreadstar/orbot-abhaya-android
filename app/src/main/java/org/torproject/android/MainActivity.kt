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

        // Drop folder selection moved to EnhancedMeshFragment using URI-based approach
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Drop folder selection moved to EnhancedMeshFragment using URI-based approach
    }
}
