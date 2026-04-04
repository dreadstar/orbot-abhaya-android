package org.torproject.android

import org.torproject.android.R
import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.addCallback

import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController

import com.google.android.material.bottomnavigation.BottomNavigationView
import com.scottyab.rootbeer.RootBeer

import org.torproject.android.service.util.sendIntentToService
import org.torproject.android.ui.core.BaseActivity
import org.torproject.android.service.OrbotConstants
import org.torproject.android.service.util.Prefs
import org.torproject.android.service.util.showToast
import org.torproject.android.ui.more.LogBottomSheet
import org.torproject.android.ui.connect.ConnectViewModel
import org.torproject.android.ui.core.DeviceAuthenticationPrompt
import java.util.Locale
import org.torproject.android.ui.mesh.EnhancedMeshFragment
import org.torproject.android.ui.mesh.NotificationsAdapter

class OrbotActivity : BaseActivity() {

    private lateinit var logBottomSheet: LogBottomSheet

    var portSocks: Int = -1
    var portHttp: Int = -1

    var previousReceivedTorStatus: String? = null

    private var lastSelectedItemId: Int = R.id.connectFragment

    // used to hide UI while password isn't obtained
    private var rootLayout: View? = null
    
    // Track if broadcast receiver is registered to prevent unregistration errors
    private var isReceiverRegistered: Boolean = false

    private val connectViewModel: ConnectViewModel by viewModels()

    // Notification badge for broadcast notifications
    private var notificationBadge: android.widget.TextView? = null

    // Popup window attached to the notification icon; displays current feed
    private var notificationsPopup: android.widget.PopupWindow? = null
    private lateinit var notificationsAdapter: NotificationsAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.d("OrbotActivity", "onCreate() - START")
        
        try {
            super.onCreate(savedInstanceState)
            android.util.Log.d("OrbotActivity", "onCreate() - super.onCreate() completed")
            
            enableEdgeToEdge()
            android.util.Log.d("OrbotActivity", "onCreate() - enableEdgeToEdge() completed")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.apply {
                    setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                }
                android.util.Log.d("OrbotActivity", "onCreate() - Window insets controller setup completed")
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                android.util.Log.d("OrbotActivity", "onCreate() - Legacy window setup completed")
            }

            lastSelectedItemId = savedInstanceState?.getInt(KEY_SELECTED_TAB) ?: lastSelectedItemId
            previousReceivedTorStatus = savedInstanceState?.getString(KEY_TOR_STATUS)
            android.util.Log.d("OrbotActivity", "onCreate() - State restoration completed")

            // programmatically set title to "Orbot" since camo mode will overwrite it here from manifest
            title = getString(R.string.app_name)
            android.util.Log.d("OrbotActivity", "onCreate() - Title set completed")

            try {
                android.util.Log.d("OrbotActivity", "onCreate() - About to call createOrbot()")
                createOrbot()
                android.util.Log.d("OrbotActivity", "onCreate() - createOrbot() completed successfully")

            } catch (re: RuntimeException) {
                android.util.Log.e("OrbotActivity", "onCreate() - RuntimeException in createOrbot()", re)
                //catch this to avoid malicious launches as document Cure53 Audit: ORB-01-009 WP1/2: Orbot DoS via exported activity (High)

                //clear malicious intent
                intent = null
                finish()
            } catch (e: Exception) {
                android.util.Log.e("OrbotActivity", "onCreate() - Exception in createOrbot()", e)
                // Log the error but don't crash
                finish()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("OrbotActivity", "onCreate() - Exception in onCreate()", e)
            finish()
        }
        
        android.util.Log.d("OrbotActivity", "onCreate() - END")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_SELECTED_TAB, lastSelectedItemId)
        outState.putString(KEY_TOR_STATUS, previousReceivedTorStatus)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        lastSelectedItemId = savedInstanceState.getInt(KEY_SELECTED_TAB, R.id.connectFragment)
        previousReceivedTorStatus = savedInstanceState.getString(KEY_TOR_STATUS)

        val navController = findNavController(R.id.nav_fragment)
        val currentDest = navController.currentDestination?.id

        if (currentDest != lastSelectedItemId) {
            navController.navigate(lastSelectedItemId)
        }

        findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId =
            lastSelectedItemId
    }

    private fun createOrbot() {
        android.util.Log.d("OrbotActivity", "createOrbot() - START")
        
        try {
            android.util.Log.d("OrbotActivity", "createOrbot() - Setting content view")
            setContentView(R.layout.activity_orbot)
            android.util.Log.d("OrbotActivity", "createOrbot() - Content view set successfully")
            
            android.util.Log.d("OrbotActivity", "createOrbot() - Finding rootLayout")
            rootLayout = findViewById(R.id.rootLayout)
            android.util.Log.d("OrbotActivity", "createOrbot() - rootLayout found: ${rootLayout != null}")
            
            android.util.Log.d("OrbotActivity", "createOrbot() - Setting up window insets listener")
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nav_fragment)) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
            android.util.Log.d("OrbotActivity", "createOrbot() - Window insets listener set")

            android.util.Log.d("OrbotActivity", "createOrbot() - Creating LogBottomSheet")
            logBottomSheet = LogBottomSheet()
            android.util.Log.d("OrbotActivity", "createOrbot() - LogBottomSheet created")

            android.util.Log.d("OrbotActivity", "createOrbot() - Finding nav controller")
            val navController: NavController = findNavController(R.id.nav_fragment)
            android.util.Log.d("OrbotActivity", "createOrbot() - Nav controller found: ${navController != null}")
            
            android.util.Log.d("OrbotActivity", "createOrbot() - Finding bottom navigation view")
            val bottomNavigationView: BottomNavigationView = findViewById(R.id.bottom_navigation)
            android.util.Log.d("OrbotActivity", "createOrbot() - Bottom navigation found: ${bottomNavigationView != null}")
            
            android.util.Log.d("OrbotActivity", "createOrbot() - Setting up nav controller with bottom nav")
            bottomNavigationView.setupWithNavController(navController)
            android.util.Log.d("OrbotActivity", "createOrbot() - Nav controller setup completed")

            bottomNavigationView.selectedItemId = lastSelectedItemId
            android.util.Log.d("OrbotActivity", "createOrbot() - Bottom navigation item selected: $lastSelectedItemId")

        val navOptionsLeftToRight = NavOptions.Builder().setEnterAnim(R.anim.slide_in_right)
            .setExitAnim(R.anim.slide_out_left).setPopEnterAnim(R.anim.slide_in_right)
            .setPopExitAnim(R.anim.slide_out_left).build()

        val navOptionsRightToLeft = NavOptions.Builder().setEnterAnim(R.anim.slide_in_left)
            .setExitAnim(R.anim.slide_out_right).setPopEnterAnim(R.anim.slide_in_left)
            .setPopExitAnim(R.anim.slide_out_right).build()

        bottomNavigationView.setOnItemSelectedListener { item ->
            if (item.itemId == lastSelectedItemId) {
                return@setOnItemSelectedListener true
            }

            val navOptions = if (item.itemId > lastSelectedItemId) {
                navOptionsLeftToRight
            } else {
                navOptionsRightToLeft
            }

            when (item.itemId) {
                R.id.connectFragment -> navController.navigate(
                    R.id.connectFragment, null, navOptions
                )

                R.id.kindnessFragment -> navController.navigate(
                    R.id.kindnessFragment, null, navOptions
                )

                R.id.friendsFragment -> navController.navigate(
                    R.id.friendsFragment, null, navOptions
                )

                R.id.meshFragment -> navController.navigate(
                    R.id.meshFragment, null, navOptions // nav_graph.xml already points to EnhancedMeshFragment
                )

                    R.id.taskManagerFragment -> navController.navigate(
                        R.id.taskManagerFragment, null, navOptions
                    )
                    R.id.moreFragment -> navController.navigate(R.id.moreFragment, null, navOptions)
            }

            lastSelectedItemId = item.itemId
            true
        }

            // Use helper to centralize LocalBroadcastManager deprecation suppression
            android.util.Log.d("OrbotActivity", "createOrbot() - Registering broadcast receivers")
            try {
                org.torproject.android.util.LocalBroadcast.registerReceiver(
                    this, orbotServiceBroadcastReceiver, IntentFilter(OrbotConstants.LOCAL_ACTION_STATUS)
                )
                org.torproject.android.util.LocalBroadcast.registerReceiver(
                    this, orbotServiceBroadcastReceiver, IntentFilter(OrbotConstants.LOCAL_ACTION_LOG)
                )
                org.torproject.android.util.LocalBroadcast.registerReceiver(
                    this, orbotServiceBroadcastReceiver, IntentFilter(OrbotConstants.LOCAL_ACTION_PORTS)
                )
                isReceiverRegistered = true
                android.util.Log.d("OrbotActivity", "createOrbot() - Broadcast receivers registered successfully")
            } catch (e: Exception) {
                android.util.Log.e("OrbotActivity", "createOrbot() - Exception registering broadcast receivers", e)
                // Handle registration failure gracefully
                isReceiverRegistered = false
            }

            android.util.Log.d("OrbotActivity", "createOrbot() - Requesting notification permission")
            requestNotificationPermission()
            android.util.Log.d("OrbotActivity", "createOrbot() - Notification permission requested")

            android.util.Log.d("OrbotActivity", "createOrbot() - Initializing weekly worker")
            Prefs.initWeeklyWorker()
            android.util.Log.d("OrbotActivity", "createOrbot() - Weekly worker initialized")

            android.util.Log.d("OrbotActivity", "createOrbot() - Checking for root detection")
            if (!rootDetectionShown && Prefs.detectRoot() && RootBeer(this).isRooted) {
                //we found indication of root
                android.util.Log.d("OrbotActivity", "createOrbot() - Root detected, showing warning")
                applicationContext.showToast(getString(R.string.root_warning))
                rootDetectionShown = true
            }

            android.util.Log.d("OrbotActivity", "createOrbot() - Setting up back press callback")
            onBackPressedDispatcher.addCallback(this ) {
                if (lastSelectedItemId != R.id.connectFragment) {
                    bottomNavigationView.selectedItemId = R.id.connectFragment
                }
                else finish()
            }
            android.util.Log.d("OrbotActivity", "createOrbot() - Back press callback set")
            
        } catch (e: Exception) {
            android.util.Log.e("OrbotActivity", "createOrbot() - Exception in createOrbot method", e)
            throw e
        }
        
        android.util.Log.d("OrbotActivity", "createOrbot() - END")
    }

    private fun requestNotificationPermission() {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) -> {
                // You can use the API that requires the permission.
            }

            else -> {
                // You can directly ask for the permission.
                // The registered ActivityResultCallback gets the result of this request.
                requestPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
    }

    // Register the permissions callback, which handles the user's response to the
// system permissions dialog. Save the return value, an instance of
// ActivityResultLauncher. You can use either a val, as shown in this snippet,
// or a lateinit var in your onAttach() or onCreate() method.
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission is granted. Continue the action or workflow in your
            // app.
        } else {
            // Explain to the user that the feature is unavailable because the
            // feature requires a permission that the user has denied. At the
            // same time, respect the user's decision. Don't link to system
            // settings in an effort to convince the user to change their
            // decision.
        }
    }

    override fun onStart() {
        super.onStart()
        promptDeviceAuthenticationIfRequired()
    }

    override fun onResume() {
        super.onResume()
        sendIntentToService(OrbotConstants.CMD_ACTIVE)
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Only unregister if we successfully registered the receiver
        if (isReceiverRegistered) {
            try {
                org.torproject.android.util.LocalBroadcast.unregisterReceiver(this, orbotServiceBroadcastReceiver)
                isReceiverRegistered = false
            } catch (e: Exception) {
                // Handle unregistration failure gracefully
                // This can happen if the receiver was already unregistered
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_VPN && resultCode == RESULT_OK) {
            connectViewModel.triggerStartTorAndVpn()
        } else if (requestCode == REQUEST_CODE_SETTINGS && resultCode == RESULT_OK) {
            Prefs.defaultLocale = data?.getStringExtra("locale") ?: Locale.getDefault().language
            sendIntentToService(OrbotConstants.ACTION_LOCAL_LOCALE_SET)
            (application as OrbotApp).setLocale()
            finish()
            startActivity(Intent(this, OrbotActivity::class.java))
        } else if (requestCode == REQUEST_VPN_APP_SELECT && resultCode == RESULT_OK) {
            sendIntentToService(OrbotConstants.ACTION_RESTART_VPN) // is this enough todo?
            connectViewModel.triggerRefreshMenuList()
        }
    }

    private val orbotServiceBroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(OrbotConstants.EXTRA_STATUS)
            when (intent?.action) {
                OrbotConstants.LOCAL_ACTION_STATUS -> {
                    if (status != previousReceivedTorStatus) {
                        connectViewModel.updateState(this@OrbotActivity, status)
                        previousReceivedTorStatus = status
                    }
                }

                OrbotConstants.LOCAL_ACTION_LOG -> {
                    intent.getStringExtra(OrbotConstants.LOCAL_EXTRA_BOOTSTRAP_PERCENT)?.let {
                        connectViewModel.updateBootstrapPercent(it.toIntOrNull() ?: 0)
                    }
                    intent.getStringExtra(OrbotConstants.LOCAL_EXTRA_LOG)?.let {
                        logBottomSheet.appendLog(it)
                    }
                }

                OrbotConstants.LOCAL_ACTION_PORTS -> {
                    val socks = intent.getIntExtra(OrbotConstants.EXTRA_SOCKS_PROXY_PORT, -1)
                    val http = intent.getIntExtra(OrbotConstants.EXTRA_HTTP_PROXY_PORT, -1)
                    if (http > 0 && socks > 0) {
                        portSocks = socks
                        portHttp = http
                    }
                }

                else -> {}
            }
        }
    }

    private fun promptDeviceAuthenticationIfRequired() {
        if (!Prefs.requireDeviceAuthentication)
            return

        if (!OrbotApp.shouldRequestAuthentication)
            return

        // if app was closed, we should re-request password upon
        // re-open, even if we've gotten it already
        OrbotApp.shouldRequestAuthentication = false

        if (OrbotApp.isAuthenticationPromptOpenLegacyFlag)
            return

        OrbotApp.isAuthenticationPromptOpenLegacyFlag = true

        rootLayout?.visibility = View.INVISIBLE
        DeviceAuthenticationPrompt.openPrompt(this, object :
            BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errorMsg: CharSequence) {
                OrbotApp.isAuthenticationPromptOpenLegacyFlag = false
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                    OrbotApp.resetLockFlags()
                    finish() // user presses back, just close
                } else if (errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE) {
                    // we set this flag when Orbot *can't* authenticate, ie no password or unsupported device
                    showToast(errorMsg) // String set in RequirePasswordPrompt.kt
                    rootLayout?.visibility = View.VISIBLE
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                OrbotApp.shouldRequestAuthentication = false
                OrbotApp.isAuthenticationPromptOpenLegacyFlag = false
                rootLayout?.visibility = View.VISIBLE
            }

            override fun onAuthenticationFailed() {
                OrbotApp.resetLockFlags()
                finish()
            }
        })
    }

    companion object {
        private const val KEY_SELECTED_TAB = "selected_tab_id"
        private const val KEY_TOR_STATUS = "key_tor_status"
        const val REQUEST_CODE_VPN = 1234
        const val REQUEST_CODE_SETTINGS = 2345
        const val REQUEST_VPN_APP_SELECT = 2432

        // Make sure this is only shown once per app-start, not on every device rotation.
        private var rootDetectionShown = false
    }

    fun navigateToTaskManager() {
        val navController = findNavController(R.id.nav_fragment)
        val navOptions = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setPopUpTo(R.id.connectFragment, false)
            .build()
        navController.navigate(R.id.taskManagerFragment, null, navOptions)
    }

    fun showLog() {
        if (!logBottomSheet.isAdded) {
            logBottomSheet.show(supportFragmentManager, OrbotActivity::class.java.simpleName)
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_toolbar, menu)
        // Initialize notification badge reference from custom action view
        val actionView = menu?.findItem(R.id.action_notifications)?.actionView
        notificationBadge = actionView?.findViewById(R.id.notification_badge)

        // start with an empty adapter; the fragment will populate its own instance
        notificationsAdapter = NotificationsAdapter(emptyList()) { entry ->
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_fragment) as? androidx.navigation.fragment.NavHostFragment
            val fragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull() as? EnhancedMeshFragment
            fragment?.removeNotification(entry)
        }
        android.util.Log.d("OrbotActivity", "[DROPDOWN] initial adapter created, size=${notificationsAdapter.itemCount}")

        // Prepare dropdown layout and popup
        val dropdownView = layoutInflater.inflate(R.layout.toolbar_notification_dropdown, null)
        val recyclerView = dropdownView.findViewById<androidx.recyclerview.widget.RecyclerView>(
            R.id.notificationsDropdownRecyclerView
        )
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        recyclerView.adapter = notificationsAdapter

        notificationsPopup = android.widget.PopupWindow(
            dropdownView,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 8f
            setOnDismissListener {
                android.util.Log.d("OrbotActivity", "[DROPDOWN] popup dismissed")
            }
        }
        android.util.Log.d("OrbotActivity", "[DROPDOWN] popup created")

        // Toggle popup on icon click; adapter already kept up-to-date via activity callback
        actionView?.setOnClickListener {
            android.util.Log.d("OrbotActivity", "[DROPDOWN] icon clicked, popup showing=${notificationsPopup?.isShowing}")
            android.util.Log.d("OrbotActivity", "[DROPDOWN] current adapter size=${notificationsAdapter.itemCount}")

            if (notificationsPopup?.isShowing == true) {
                notificationsPopup?.dismiss()
            } else {
                android.util.Log.d("OrbotActivity", "[DROPDOWN] anchor size=${actionView?.width}x${actionView?.height}")
                notificationsPopup?.showAsDropDown(actionView)
                android.util.Log.d("OrbotActivity", "[DROPDOWN] popup shown")
            }
        }
        return true
    }

    /**
     * Show dialog with list of received broadcast notifications
     */
    private fun showNotificationsDialog() {
        val fragment = supportFragmentManager.findFragmentByTag("MESH_FRAGMENT") as? EnhancedMeshFragment
        val notifications = fragment?.getNotificationFeed()?.value ?: emptyList()

        if (notifications.isEmpty()) {
            showToast("No notifications yet")
            return
        }

        // Create dialog with RecyclerView showing notifications
        val dialogView = layoutInflater.inflate(R.layout.dialog_notifications, null)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.notificationsRecyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        recyclerView.adapter = NotificationsAdapter(notifications) { entry ->
            fragment?.removeNotification(entry)
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Broadcast Notifications (${notifications.size})")
            .setView(dialogView)
            .setPositiveButton("Clear All") { _, _ ->
                fragment?.clearNotifications()
                updateNotificationBadge(0)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Update notification badge count in toolbar
     * Shows red badge with count when notifications > 0
     */
    fun updateNotificationBadge(count: Int) {
        notificationBadge?.apply {
            if (count > 0) {
                text = if (count > 99) "99+" else count.toString()
                visibility = View.VISIBLE
            } else {
                text = ""
                visibility = View.GONE
            }
        }
    }

    /**
     * Called by fragment when the notification feed changes.  Keeps the popup
     * adapter in sync even if the fragment is not currently attached.
     */
    fun onNotificationFeedChanged(feed: List<org.torproject.android.ui.mesh.model.NotificationFeedEntry>) {
        android.util.Log.d("OrbotActivity", "[DROPDOWN] activity received feed update, size=${feed.size}")
        if (::notificationsAdapter.isInitialized) {
            notificationsAdapter.submitList(feed)
        }
    }
}
