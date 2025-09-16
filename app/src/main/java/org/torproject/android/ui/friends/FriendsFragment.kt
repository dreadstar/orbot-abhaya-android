package org.torproject.android.ui.friends

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

// QR Code generation (QRCode-kotlin - lightweight)
import qrcode.QRCode

// ML Kit for scanning (strategic computer vision capabilities)
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

import org.torproject.android.R
import org.torproject.android.ui.friends.model.Friend
import org.torproject.android.ui.friends.adapter.FriendsAdapter
import java.util.regex.Pattern
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Friends management fragment for managing .onion address contacts
 * 
 * Features:
 * - Display device's .onion public key as QR code
 * - Scan QR codes to add friends via camera
 * - Manage friends list with nicknames and .onion addresses
 * - Integration with distributed storage for friend-based file sharing
 * 
 * TODO: Message Functionality Implementation
 * - Message contact button should open a chat interface
 * - Implement secure messaging over Tor hidden services
 * - Message encryption using .onion service keys
 * - Message persistence and history management
 * - Online status detection via service availability
 * - Typing indicators and read receipts
 * - File/media sharing through messaging interface
 * - Group messaging capabilities for multiple friends
 */
class FriendsFragment : Fragment() {

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 1001
        
        // v3 .onion address pattern (56 characters + .onion)
        private val ONION_ADDRESS_PATTERN = Pattern.compile(
            "^[a-z2-7]{56}\\.onion$",
            Pattern.CASE_INSENSITIVE
        )
        
        /**
         * Validates if a string is a valid v3 .onion address
         */
        fun isValidOnionAddress(address: String): Boolean {
            return ONION_ADDRESS_PATTERN.matcher(address.trim().lowercase()).matches()
        }
    }

    private lateinit var friendsAdapter: FriendsAdapter
    private lateinit var friendsList: MutableList<Friend>
    private lateinit var cameraExecutor: ExecutorService
    
    // Camera related for ML Kit scanning
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    
    // Views
    private lateinit var showQRButton: MaterialButton
    private lateinit var scanQRButton: MaterialButton
    private lateinit var friendsRecyclerView: RecyclerView
    private lateinit var friendsCard: MaterialCardView
    private lateinit var emptyStateTextView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_friends, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupFriendsList()
        setupButtonListeners()
        
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun initializeViews(view: View) {
        showQRButton = view.findViewById(R.id.btn_show_qr)
        scanQRButton = view.findViewById(R.id.btn_scan_qr)
        friendsRecyclerView = view.findViewById(R.id.rv_friends)
        friendsCard = view.findViewById(R.id.card_friends)
        emptyStateTextView = view.findViewById(R.id.tv_empty_state)
    }

    private fun setupFriendsList() {
        friendsList = mutableListOf()
        friendsAdapter = FriendsAdapter(
            friends = friendsList,
            onShowInfoClicked = { friend -> showFriendInfoDialog(friend) },
            onMessageClicked = { friend -> handleMessageFriend(friend) },
            onDeleteClicked = { friend -> handleDeleteFriend(friend) }
        )
        
        friendsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = friendsAdapter
        }
        
        updateEmptyStateVisibility()
    }

    private fun setupButtonListeners() {
        showQRButton.setOnClickListener {
            showQRCodeDialog()
        }
        
        scanQRButton.setOnClickListener {
            if (checkCameraPermission()) {
                showQRScannerDialog()
            } else {
                requestCameraPermission()
            }
        }
    }

    /**
     * Show QR code dialog displaying the device's .onion public key
     */
    private fun showQRCodeDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_qr_display, null)
        
        val qrImageView = dialogView.findViewById<ImageView>(R.id.iv_qr_code)
        val addressTextView = dialogView.findViewById<TextView>(R.id.tv_onion_address)
        
        // TODO: Get actual device .onion address from OrbotService
        val deviceOnionAddress = getDeviceOnionAddress()
        
        // Generate QR code
        val qrBitmap = generateQRCode(deviceOnionAddress)
        qrImageView.setImageBitmap(qrBitmap)
        addressTextView.text = deviceOnionAddress
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("My .onion Address")
            .setView(dialogView)
            .setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Show QR scanner dialog with camera preview
     */
    private fun showQRScannerDialog() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(), 
                arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_qr_scanner, null)
        val previewView = dialogView.findViewById<PreviewView>(R.id.camera_preview)
        val switchCameraButton = dialogView.findViewById<MaterialButton>(R.id.btn_switch_camera)
        val statusText = dialogView.findViewById<TextView>(R.id.tv_scan_status)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Scan Friend's QR Code")
            .setView(dialogView)
            .setNegativeButton("Cancel") { dialog, _ -> 
                stopCamera()
                dialog.dismiss() 
            }
            .create()

        // Setup ML Kit camera scanning
        startCamera(previewView) { scannedText ->
            if (isValidOnionAddress(scannedText)) {
                stopCamera()
                dialog.dismiss()
                showAddFriendDialog(scannedText)
            } else {
                statusText.text = "Invalid .onion address detected"
                statusText.setTextColor(Color.RED)
            }
        }

        // Switch camera button
        switchCameraButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            startCamera(previewView) { scannedText ->
                if (isValidOnionAddress(scannedText)) {
                    stopCamera()
                    dialog.dismiss()
                    showAddFriendDialog(scannedText)
                }
            }
        }

        dialog.show()
    }

    /**
     * Show dialog to add a friend with nickname input
     */
    private fun showAddFriendDialog(onionAddress: String) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_friend, null)
        
        val nicknameInputLayout = dialogView.findViewById<TextInputLayout>(R.id.til_nickname)
        val nicknameEditText = dialogView.findViewById<TextInputEditText>(R.id.et_nickname)
        val addressTextView = dialogView.findViewById<TextView>(R.id.tv_scanned_address)
        
        addressTextView.text = "Address: $onionAddress"
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Friend")
            .setView(dialogView)
            .setPositiveButton("Add") { dialog, _ ->
                val nickname = nicknameEditText.text.toString().trim()
                if (nickname.isNotEmpty()) {
                    addFriend(nickname, onionAddress)
                    dialog.dismiss()
                } else {
                    nicknameInputLayout.error = "Nickname is required"
                }
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Add a new friend to the list
     */
    private fun addFriend(nickname: String, onionAddress: String) {
        // Check for duplicates
        if (friendsList.any { it.onionAddress == onionAddress }) {
            Toast.makeText(requireContext(), "Friend already exists", Toast.LENGTH_SHORT).show()
            return
        }
        
        val friend = Friend(
            id = System.currentTimeMillis().toString(),
            nickname = nickname,
            onionAddress = onionAddress,
            isOnline = false // TODO: Implement online status detection
        )
        
        friendsList.add(friend)
        friendsAdapter.notifyItemInserted(friendsList.size - 1)
        
        // Update empty state visibility
        updateEmptyStateVisibility()
        
        // TODO: Persist friend to local database/shared preferences
        saveFriendToStorage(friend)
        
        Toast.makeText(requireContext(), "Friend added successfully", Toast.LENGTH_SHORT).show()
    }

    /**
     * Show friend information dialog
     */
    private fun showFriendInfoDialog(friend: Friend) {
        val message = """
            Nickname: ${friend.nickname}
            .onion Address: ${friend.onionAddress}
            Online Status: ${if (friend.isOnline) "Online" else "Offline"}
            Added: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}
        """.trimIndent()
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Friend Information")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Handle messaging a friend
     * TODO: Implement secure messaging interface
     */
    private fun handleMessageFriend(friend: Friend) {
        Toast.makeText(
            requireContext(), 
            "Messaging feature coming soon!\nWill open chat with ${friend.nickname}", 
            Toast.LENGTH_LONG
        ).show()
        
        // TODO: Open messaging interface
        // - Navigate to chat fragment with friend's .onion address
        // - Establish secure connection via hidden service
        // - Implement message encryption/decryption
        // - Handle message history and persistence
    }

    /**
     * Handle deleting a friend
     */
    private fun handleDeleteFriend(friend: Friend) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Friend")
            .setMessage("Are you sure you want to remove ${friend.nickname} from your friends list?")
            .setPositiveButton("Delete") { dialog, _ ->
                val position = friendsList.indexOf(friend)
                if (position != -1) {
                    friendsList.removeAt(position)
                    friendsAdapter.notifyItemRemoved(position)
                    
                    // Update empty state visibility
                    updateEmptyStateVisibility()
                    
                    // TODO: Remove from persistent storage
                    removeFriendFromStorage(friend)
                    
                    Toast.makeText(requireContext(), "Friend removed", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Update empty state visibility based on friends list
     */
    private fun updateEmptyStateVisibility() {
        if (friendsList.isEmpty()) {
            emptyStateTextView.visibility = View.VISIBLE
            friendsRecyclerView.visibility = View.GONE
        } else {
            emptyStateTextView.visibility = View.GONE
            friendsRecyclerView.visibility = View.VISIBLE
        }
    }

    /**
     * Generate QR code bitmap from text using QRCode-kotlin
     */
    private fun generateQRCode(text: String): Bitmap {
        return try {
            // Use QRCode-kotlin for generation (lightweight) - correct API from v4+
            val qrCodeRenderer = QRCode.ofSquares()
                .withSize(25) // Default size
                .build(text)
            
            // Render to PNG bytes
            val pngBytes = qrCodeRenderer.render().getBytes()
            
            // Convert PNG bytes to Bitmap
            BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to empty bitmap
            Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        }
    }

    /**
     * Start camera for QR code scanning with ML Kit
     */
    private fun startCamera(previewView: PreviewView, onQRCodeDetected: (String) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            
            // Preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            
            // Image analysis for ML Kit QR code detection
            val imageAnalyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(cameraExecutor, MLKitQRCodeAnalyzer(onQRCodeDetected))
            }
            
            // Select camera
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()
            
            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
            }
            
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /**
     * Stop camera
     */
    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    /**
     * Check camera permission
     */
    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if a string is a valid .onion address
     */

    /**
     * Request camera permission
     */
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showQRScannerDialog()
            } else {
                Toast.makeText(requireContext(), "Camera permission is required for QR scanning", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Get device's .onion address
     * TODO: Integrate with OrbotService to get actual device .onion address
     */
    private fun getDeviceOnionAddress(): String {
        // For now return a mock address
        // TODO: Get from OrbotService hidden service configuration
        return "3g2upl4pq6kufc4m.onion"
    }

    /**
     * Save friend to persistent storage
     * TODO: Implement with Room database or SharedPreferences
     */
    private fun saveFriendToStorage(friend: Friend) {
        // TODO: Implement persistent storage
    }

    /**
     * Remove friend from persistent storage
     * TODO: Implement with Room database or SharedPreferences
     */
    private fun removeFriendFromStorage(friend: Friend) {
        // TODO: Implement persistent storage removal
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopCamera()
    }

    /**
     * ML Kit QR Code analyzer for barcode scanning
     */
    private inner class MLKitQRCodeAnalyzer(
        private val onQRCodeDetected: (String) -> Unit
    ) : ImageAnalysis.Analyzer {

        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                
                val scanner = BarcodeScanning.getClient()
                scanner.process(image)
                    .addOnSuccessListener { barcodes: List<Barcode> ->
                        for (barcode in barcodes) {
                            when (barcode.valueType) {
                                Barcode.TYPE_TEXT -> {
                                    barcode.displayValue?.let { value ->
                                        if (isValidOnionAddress(value)) {
                                            onQRCodeDetected(value)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .addOnFailureListener { exception: Exception ->
                        // Handle failure
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

}
