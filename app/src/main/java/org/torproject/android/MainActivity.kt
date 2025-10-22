package org.torproject.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.torproject.android.GatewayCapabilitiesManager
import org.torproject.android.mesh.MeshStorageManager
import org.torproject.android.ui.mesh.MeshFragment
import android.widget.Toast
import android.content.Intent
import android.net.Uri
/**
 * Main activity for mesh integration demo. Handles UI and gateway capability toggling.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var gatewayManager: GatewayCapabilitiesManager
    private lateinit var meshStorageManager: MeshStorageManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        gatewayManager = GatewayCapabilitiesManager.getInstance(this)
        // TODO: Setup UI listeners and bind to gatewayManager

        // --- NEW CODE: Initialize MeshStorageManager ---
        meshStorageManager = MeshStorageManager.getInstance(this)

        // --- NEW CODE: Setup MeshFragment UI callback integration ---
        val meshFragment = supportFragmentManager.findFragmentById(R.id.meshFragment) as? MeshFragment
        meshFragment?.let {
            meshStorageManager.setUiCallback(it)
        }

        // --- NEW CODE: Handle drop folder selection intent ---
        handleDropFolderIntent(intent)
    }

    // --- NEW CODE: Handle drop folder selection from UI or external intent ---
    private fun handleDropFolderIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            val folderPath = getFolderPathFromUri(uri)
            if (folderPath != null) {
                meshStorageManager.setDropFolder(folderPath)
                Toast.makeText(this, "Drop folder set: $folderPath", Toast.LENGTH_SHORT).show()
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // --- NEW CODE: Handle new drop folder selection while app is running ---
        handleDropFolderIntent(intent)
    }
}
