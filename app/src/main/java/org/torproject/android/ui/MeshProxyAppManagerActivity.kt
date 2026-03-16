package org.torproject.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.GridView
import android.widget.ImageView
import android.widget.ListAdapter
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.torproject.android.BuildConfig
import org.torproject.android.R
import org.torproject.android.service.OrbotConstants
import org.torproject.android.service.vpn.TorifiedApp
import org.torproject.android.ui.core.BaseActivity

/**
 * "Mesh Proxy Apps" activity — a replica of [AppManagerActivity] that persists app selection
 * via [MeshrabiyaApiImpl] (DataStore) rather than SharedPreferences, using a storage key
 * entirely separate from the "Choose Apps" Tor selection.
 *
 * Apps checked here will have their traffic routed through the mesh to a CLEARNET_GATEWAY
 * when the local device does not have direct internet access.
 */
class MeshProxyAppManagerActivity : BaseActivity(), View.OnClickListener {

    inner class AppWrapper(
        var header: String? = null,
        var app: TorifiedApp? = null,
        var isMeshProxied: Boolean = false,
    )

    private var pMgr: PackageManager? = null
    private var listAppsAll: GridView? = null
    private var adapterAppsAll: ListAdapter? = null
    private var progressBar: ProgressBar? = null

    private val meshrabiyaApi by lazy { MeshrabiyaApiImpl.getInstance() }

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private var selectedPackages: Set<String> = emptySet()
    var uiList: MutableList<AppWrapper> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pMgr = packageManager
        setContentView(R.layout.activity_app_manager)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_mesh_proxy_apps)
        listAppsAll = findViewById(R.id.applistview)
        progressBar = findViewById(R.id.progressBar)
        lockActivityOrientation()
    }

    override fun onResume() {
        super.onResume()
        scope.launch {
            selectedPackages = withContext(Dispatchers.IO) { meshrabiyaApi.getMeshProxyApps() }
            reloadApps()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.app_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_save_apps -> { saveAppSettings(); finish(); true }
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun reloadApps() {
        scope.launch {
            progressBar?.visibility = View.VISIBLE
            withContext(Dispatchers.IO) { loadApps() }
            listAppsAll?.adapter = adapterAppsAll
            progressBar?.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private fun loadApps() {
        val allApps = loadMeshProxyApps(this@MeshProxyAppManagerActivity, selectedPackages)
        val inflater = layoutInflater
        uiList.clear()
        uiList.addAll(allApps.map { AppWrapper(app = it, isMeshProxied = it.isTorified) })

        adapterAppsAll = object : ArrayAdapter<AppWrapper?>(
            this,
            R.layout.layout_apps_item,
            R.id.itemtext,
            uiList as List<AppWrapper?>
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                var cv = convertView
                var entry: ListEntry? = null
                if (cv == null) {
                    cv = inflater.inflate(R.layout.layout_apps_item, parent, false)
                } else {
                    entry = cv.tag as? ListEntry
                }
                if (entry == null) {
                    entry = ListEntry()
                    entry.container = cv?.findViewById(R.id.appContainer)
                    entry.icon = cv?.findViewById(R.id.itemicon)
                    entry.box = cv?.findViewById(R.id.itemcheck)
                    entry.text = cv?.findViewById(R.id.itemtext)
                    entry.header = cv?.findViewById(R.id.tvHeader)
                    cv?.tag = entry
                }
                val aw = uiList[position]
                if (aw.header != null) {
                    entry.header?.text = aw.header
                    entry.header?.visibility = View.VISIBLE
                    entry.container?.visibility = View.GONE
                } else {
                    val app = aw.app
                    entry.header?.visibility = View.GONE
                    entry.container?.visibility = View.VISIBLE
                    val packageName = app?.packageName
                    if (entry.icon != null && packageName != null) {
                        try {
                            entry.icon?.setImageDrawable(pMgr?.getApplicationIcon(packageName))
                            entry.icon?.tag = entry.box
                            entry.icon?.setOnClickListener(this@MeshProxyAppManagerActivity)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    entry.text?.text = app?.name
                    entry.text?.tag = entry.box
                    entry.text?.setOnClickListener(this@MeshProxyAppManagerActivity)
                    entry.box?.isChecked = aw.isMeshProxied
                    entry.box?.tag = aw
                    entry.box?.setOnClickListener(this@MeshProxyAppManagerActivity)
                }
                cv?.onFocusChangeListener = OnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundColor(
                        ContextCompat.getColor(
                            context,
                            if (hasFocus) R.color.dark_purple else android.R.color.transparent
                        )
                    )
                }
                return cv ?: View(context)
            }
        }
    }

    private fun saveAppSettings() {
        val proxied = uiList
            .filter { it.isMeshProxied && it.app != null }
            .mapNotNull { it.app?.packageName }
            .toSet()
        scope.launch {
            withContext(Dispatchers.IO) { meshrabiyaApi.setMeshProxyApps(proxied) }
        }
    }

    override fun onClick(v: View) {
        var cbox: CheckBox? = null
        if (v is CheckBox) cbox = v
        else if (v.tag is CheckBox) cbox = v.tag as CheckBox
        if (cbox != null) {
            val aw = cbox.tag as? AppWrapper ?: return
            aw.isMeshProxied = !aw.isMeshProxied
            cbox.isChecked = aw.isMeshProxied
        }
    }

    private class ListEntry {
        var box: CheckBox? = null
        var text: TextView? = null
        var icon: ImageView? = null
        var container: View? = null
        var header: TextView? = null
    }

    companion object {
        private fun includeAppInUi(applicationInfo: ApplicationInfo): Boolean {
            if (!applicationInfo.enabled) return false
            return if (OrbotConstants.BYPASS_VPN_PACKAGES.contains(applicationInfo.packageName)) false
            else BuildConfig.APPLICATION_ID != applicationInfo.packageName
        }

        fun loadMeshProxyApps(context: Context, selectedPackages: Set<String>): List<TorifiedApp> {
            val pMgr = context.packageManager
            val lAppInfo = pMgr.getInstalledApplications(0)
            val apps = ArrayList<TorifiedApp>()
            for (aInfo in lAppInfo) {
                if (!includeAppInUi(aInfo)) continue
                val app = TorifiedApp()
                try {
                    val pInfo = pMgr.getPackageInfo(aInfo.packageName, PackageManager.GET_PERMISSIONS)
                    for (permInfo in pInfo.requestedPermissions ?: emptyArray()) {
                        if (permInfo == Manifest.permission.INTERNET) { app.usesInternet = true }
                    }
                } catch (_: Exception) {}
                if (!app.usesInternet) continue
                try {
                    app.name = pMgr.getApplicationLabel(aInfo).toString()
                } catch (_: Exception) { continue }
                app.isEnabled = aInfo.enabled
                app.uid = aInfo.uid
                app.username = pMgr.getNameForUid(app.uid)
                app.procname = aInfo.processName
                app.packageName = aInfo.packageName
                app.isTorified = selectedPackages.contains(app.packageName)
                apps.add(app)
            }
            apps.sort()
            return apps
        }
    }
}
