# GATEWAY_ROUTING_DEBUG_PT8
Collection started. 8 files required.

## FILE: /Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt
package com.ustadmobile.meshrabiya.vnet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import com.ustadmobile.meshrabiya.vnet.VirtualPacket




class AndroidVirtualNode(
    appContext: Context,
    port: Int = 0,
    json: Json = Json,
    logger: MNetLogger = MNetLoggerStdout(),
    dataStore: DataStore<Preferences>,
    address: InetAddress = randomApipaInetAddr(),
    config: NodeConfig = NodeConfig.DEFAULT_CONFIG,
) : VirtualNode(
    port = port,
    logger = logger,
    address = address,
    json = json,
    config = config,
    appContext = appContext,
) {
    
    /**
     * Provides context for service initialization (EmergentRoleManager, IntelligentDistributedComputeService).
     */
    override fun getContext(): Context  {
        Log.d("AndroidVirtualNode", "getContext() called, returning: $appContext")
        return appContext
    }

    private val bluetoothManager: BluetoothManager by lazy {
        appContext.getSystemService(BluetoothManager::class.java)
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private val newWifiConnectionListener = MeshrabiyaWifiManagerAndroid.OnNewWifiConnectionListener {
        addNewNeighborConnection(
            address = it.neighborInetAddress,
            port = it.neighborPort,
            neighborNodeVirtualAddr = it.neighborVirtualAddress,
            socket = it.socket,
        )
    }

    override val meshrabiyaWifiManager: MeshrabiyaWifiManagerAndroid = MeshrabiyaWifiManagerAndroid(
        appContext = appContext,
        logger = logger,
        localNodeAddr = addressAsInt,
        router = this,
        chainSocketFactory = chainSocketFactory,
        ioExecutor = connectionExecutor,
        dataStore = dataStore,
        json = json,
        onNewWifiConnectionListener = newWifiConnectionListener,
    )

    private val clearnetGatewayForwarder: ClearnetGatewayForwarder = ClearnetGatewayForwarder(
        logger = logger,
        logPrefix = "ClearnetGateway",
        onResponsePacket = { packet -> route(packet, null, null) },
    )

    private val torGatewayForwarder: TorGatewayForwarder = TorGatewayForwarder(
        logger = logger,
        logPrefix = "TorGateway",
        onResponsePacket = { packet -> route(packet, null, null) },
    )

    override fun onTorGatewayPacket(packet: VirtualPacket): Boolean {
        torGatewayForwarder.forward(packet)
        return true
    }

    init {
        // Start WiFi state monitoring after all properties initialized
        emergentRoleManager.startWifiStateMonitoring()
    }

## FILE: /Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNodeDatagramSocket.kt
package com.ustadmobile.meshrabiya.vnet

import android.net.Network
import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 *
 * VirtualNodeDatagramSocket listens on the real network interface. It uses the executor service
 * to run a thread that will receive all packets, convert them from a DatagramPacket into a
 * VirtualPacket, and then give them to the VirtualRouter.
 *
 * @param socket - the underlying DatagramSocket to use - this can be bound to a network, interface etc if required
 * neighbor connects.
 * @param boundNetwork The Network object that the DatagramSocket is/will be bound to, if any. This
 *                     is needed if/when we want to establish a TCP connection. Because the
 *                     VirtualNodeDatagramSocket reference is kept as part of the originator message,
 *                     and it is created at the time the network object is available, this is the
 *                     most convenient and logical place to keep this reference.
 */
class VirtualNodeDatagramSocket(
    private val socket: DatagramSocket,
    private val localNodeVirtualAddress: Int,
    ioExecutorService: ExecutorService,
    private val router: VirtualNode,
    private val logger: MNetLogger,
    name: String? = null,
    val boundNetwork: Network? = null,
):  Runnable, Closeable {

    private val future: Future<*>

    private val logPrefix: String

    val localPort: Int = socket.localPort

    init {
        logPrefix = buildString {
            append("[VirtualNodeDatagramSocket for ${localNodeVirtualAddress.addressToDotNotation()} ")
            if(name != null)
                append("- $name")
            append("] ")
        }
        future = ioExecutorService.submit(this)
    }

    override fun run() {
        val buffer = ByteArray(VirtualPacket.MAX_PAYLOAD_SIZE)
        logger(Log.DEBUG, "$logPrefix Started on ${socket.localPort} waiting for first packet", null)

        while(!Thread.interrupted() && !socket.isClosed) {
            try {
                val rxPacket = DatagramPacket(buffer, 0, buffer.size)
                socket.receive(rxPacket)

                logger(Log.INFO, "$logPrefix ⬇️ RECEIVED packet from ${rxPacket.address}:${rxPacket.port} size=${rxPacket.length} bytes", null)

                val rxVirtualPacket = VirtualPacket.fromDatagramPacket(rxPacket)
                logger(Log.INFO, "$logPrefix 📦 Packet details: from=${rxVirtualPacket.header.fromAddr.addressToDotNotation()}:${rxVirtualPacket.header.fromPort} to=${rxVirtualPacket.header.toAddr.addressToDotNotation()}:${rxVirtualPacket.header.toPort} hopCount=${rxVirtualPacket.header.hopCount} payloadSize=${rxVirtualPacket.header.payloadSize}", null)
                router.incrementDownloadBytes(rxPacket.length.toLong())
                
                router.route(
                    packet = rxVirtualPacket,
                    datagramPacket = rxPacket,
                    virtualNodeDatagramSocket = this,
                )
            }catch(e: Exception) {
                if(!socket.isClosed)
                    logger(Log.WARN, "$logPrefix : run : exception handling packet", e)
            }
        }
        logger(Log.DEBUG, "$logPrefix : run : finished")
    }


    /**
     *
     */
    fun send(
        nextHopAddress: InetAddress,
        nextHopPort: Int,
        virtualPacket: VirtualPacket
    ) {
        val datagramPacket = virtualPacket.toDatagramPacket()
        datagramPacket.address = nextHopAddress
        datagramPacket.port = nextHopPort
        
        logger(Log.INFO, "$logPrefix ⬆️ SENDING packet to ${nextHopAddress}:${nextHopPort} from=${virtualPacket.header.fromAddr.addressToDotNotation()} to=${virtualPacket.header.toAddr.addressToDotNotation()} size=${datagramPacket.length} bytes", null)
        
        socket.send(datagramPacket)
        
        logger(Log.DEBUG, "$logPrefix ✅ Packet sent successfully to ${nextHopAddress}:${nextHopPort}", null)
        router.incrementUploadBytes(datagramPacket.length.toLong())
    }

    fun close(closeSocket: Boolean) {
        future.cancel(true)
        socket.takeIf { closeSocket }?.close()
    }

    override fun close() {
        close(false)
    }
}

## FILE NOT FOUND: NetworkStateReceiver.kt
IDE search: no results for NetworkStateReceiver.kt
Shell search: no results for NetworkStateReceiver in file names or class name
Conclusion: class does not exist in this codebase

## FILE: /Users/dreadstar/workspace/orbot-android/orbotservice/src/main/java/org/torproject/android/service/OrbotService.java
/* Copyright (c) 2009-2011, Nathan Freitas, Orbot / The Guardian Project - https://guardianproject.info/apps/orbot */
/* See LICENSE for licensing information */

package org.torproject.android.service;

import static org.torproject.android.service.OrbotConstants.*;

## FILE: /Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/model/DtoModels.kt
package com.ustadmobile.meshrabiya.api.model
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.DropFolderItem
import com.ustadmobile.meshrabiya.storage.StoreFileTrigger
import com.ustadmobile.meshrabiya.storage.RecipientType
import com.ustadmobile.meshrabiya.storage.StorageDeviceType
import com.ustadmobile.meshrabiya.model.MeshState
import com.ustadmobile.meshrabiya.model.NetworkInfo
import com.ustadmobile.meshrabiya.model.NodeInfo
import com.ustadmobile.meshrabiya.model.ApiResult
import android.util.Base64
import com.ustadmobile.meshrabiya.vnet.LocalNodeState
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import kotlinx.coroutines.flow.Flow
import com.ustadmobile.meshrabiya.vnet.MeshFile
import kotlinx.serialization.Serializable
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiDirectState
import com.ustadmobile.meshrabiya.vnet.wifi.state.LocalOnlyHotspotState
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid.InternetWifiNetworkState
import com.ustadmobile.meshrabiya.mmcp.MmcpOriginatorMessage
import com.ustadmobile.meshrabiya.vnet.MeshRole
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.VirtualNode.LastOriginatorMessage
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
// DTOs and conversion functions for MeshState, LocalNodeState, NetworkInfo, NodeInfo, GatewayPreference, VirtualPacket, ApiResult, MeshFile
// Recursively includes DTOs for all custom property types and enums

// MeshState DTO
enum class MeshStateDto {
    INITIALIZING, CONNECTING, CONNECTED, DISCONNECTED, ERROR, UNKNOWN;
}

@kotlinx.serialization.Serializable
data class NetworkOverviewMetricsDto(
    val uploadBps: Long,
    val activeNodeCount: Int
)

fun MeshState.toDto() = MeshStateDto.valueOf(this.name)
fun MeshStateDto.toInternal() = MeshState.valueOf(this.name)

// GatewayPreference DTO
// enum class GatewayPreferenceDto {
//     TOR_ONLY, CLEARNET_ONLY, TOR_PREFERRED, CLEARNET_PREFERRED, AUTO;
// }

// fun GatewayPreference.toDto() = GatewayPreferenceDto.valueOf(this.name)
// fun GatewayPreferenceDto.toInternal() = GatewayPreference.valueOf(this.name)

// MeshFile DTO
 data class MeshFileDto(
    val fileId: String,
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val owner: RecipientEntryDto,
    val recipients: List<RecipientEntryDto>,
    val createdAt: Long,
    val relativePath: String
)

fun MeshFile.toDto() = MeshFileDto(
    fileId, fileName, path, sizeBytes, owner.toDto(), recipients.map { it.toDto() }, createdAt, relativePath
)
fun MeshFileDto.toInternal() = MeshFile(
    fileId, fileName, path, sizeBytes, owner.toInternal(), recipients.map { it.toInternal() },
    createdAt, relativePath
)

// NetworkInfo DTO
data class NetworkInfoDto(
    val ssid: String,
    val bssid: String,
    val ipAddress: String,
    val connectedPeers: Int,
    val isConnected: Boolean,
    val nonMeshSsid: String? = null,
    val nonMeshIpAddress: String? = null,
    val nonMeshHasInternet: Boolean? = null,
    val torGateways: Int,
    val clearnetGateways: Int,
    val meshProxyActive: Boolean = false,
)

fun NetworkInfo.toDto(
    nonMeshSsid: String? = null,
    nonMeshIpAddress: String? = null,
    nonMeshHasInternet: Boolean? = null,
    meshProxyActive: Boolean = false,
) = NetworkInfoDto(
    ssid,
    bssid,
    ipAddress,
    connectedPeers,
    isConnected,
    nonMeshSsid,
    nonMeshIpAddress,
    nonMeshHasInternet,
    torGateways,
    clearnetGateways,
    meshProxyActive,
)

fun NetworkInfoDto.toInternal() = NetworkInfo(
    ssid,
    bssid,
    ipAddress,
    connectedPeers,
    isConnected,
    torGateways,
    clearnetGateways
)

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import net.freehaven.tor.control.TorControlCommands;
import net.freehaven.tor.control.TorControlConnection;

import org.torproject.android.service.circumvention.ContentDeliveryNetworkFronts;
import org.torproject.android.service.circumvention.SmartConnect;
import org.torproject.android.service.circumvention.SnowflakeProxyWrapper;
import org.torproject.android.service.circumvention.Transport;
import org.torproject.android.service.db.OnionServiceColumns;
import org.torproject.android.service.db.V3ClientAuthColumns;
import org.torproject.android.service.ui.Notifications;
import org.torproject.android.service.tor.CustomTorResourceInstaller;
import org.torproject.android.service.receivers.PowerConnectionReceiver;
import org.torproject.android.service.util.Prefs;
import org.torproject.android.service.tor.TorConfig;
import org.torproject.android.service.vpn.OrbotVpnManager;
import org.torproject.jni.TorService;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import kotlin.Unit;

/**
 * @noinspection CallToPrintStackTrace
 */
@SuppressWarnings("StringConcatenationInsideStringBufferAppend")
public class OrbotService extends VpnService {

    public final static String BINARY_TOR_VERSION = TorService.VERSION_NAME;
    static final int NOTIFY_ID = 1, ERROR_NOTIFY_ID = 3;
    public final static String NOTIFICATION_CHANNEL_ID = "orbot_channel_1";
    public static int mPortSOCKS = -1, mPortHTTP = -1, mPortDns = -1, mPortTrans = -1;
    public static File appBinHome, appCacheHome;
    protected final ExecutorService mExecutor = Executors.newCachedThreadPool();
    OrbotRawEventListener mOrbotRawEventListener;
    OrbotVpnManager mVpnManager;
    Handler mHandler;
    ActionBroadcastReceiver mActionBroadcastReceiver;
    protected String mCurrentStatus = STATUS_OFF;
    private android.os.PowerManager.WakeLock mTorWakeLock;

    private void acquireTorWakeLock() {
        if (mTorWakeLock == null || !mTorWakeLock.isHeld()) {
            android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(POWER_SERVICE);
            mTorWakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "orbot:TorVpnLock"
            );
            mTorWakeLock.acquire();
        }
    }

    private void releaseTorWakeLock() {
        if (mTorWakeLock != null && mTorWakeLock.isHeld()) {
            mTorWakeLock.release();
        }
        mTorWakeLock = null;
    }
    TorControlConnection conn = null;
    private ServiceConnection torServiceConnection;
    private boolean shouldUnbindTorService;
    private NotificationManager mNotificationManager = null;
    private NotificationCompat.Builder mNotifyBuilder;
    private File mV3OnionBasePath, mV3AuthBasePath;

    private PowerConnectionReceiver mPowerReceiver;

    private boolean mHasPower = false, mHasWifi = false;

    public void debug(String msg) {
        Log.d(TAG, msg);
        if (Prefs.useDebugLogging()) {
            sendCallbackLogMessage(msg);
        }
    }

    private void showConnectedToTorNetworkNotification() {
        mNotifyBuilder.setProgress(0, 0, false);
        showToolbarNotification(getString(R.string.status_activated), NOTIFY_ID, R.drawable.ic_stat_tor);
    }

    private void clearNotifications() {
        if (mNotificationManager != null) mNotificationManager.cancelAll();
        if (mOrbotRawEventListener != null) mOrbotRawEventListener.getNodes().clear();
    }

    @SuppressLint({"NewApi", "RestrictedApi"})
    protected void showToolbarNotification(String notifyMsg, int notifyType, int icon) {
        var intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        var pendIntent = PendingIntent.getActivity(OrbotService.this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        if (mNotifyBuilder == null) {
            mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            mNotifyBuilder = new NotificationCompat
                    .Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setSmallIcon(R.drawable.ic_stat_tor);
        }

        mNotifyBuilder.setOngoing(true);
        mNotifyBuilder.mActions.clear(); // clear out any notification actions, if any

        if (Prefs.isCamoEnabled()) {
            // basically ignore all params and set a simple notification
            Notifications.configureCamoNotification(mNotifyBuilder);
        } else {
            mNotifyBuilder
                    .setSmallIcon(icon)
                    .setContentText(notifyMsg)
                    .setContentIntent(pendIntent)
                    .setContentTitle(Notifications.getNotificationTitleForStatus(this, mCurrentStatus));
            // Tor connection is active
            if (conn != null && mCurrentStatus.equals(STATUS_ON)) { // only add new identity action when there is a connection
                mNotifyBuilder.setProgress(0, 0, false); // removes progress bar
                var pendingIntentNewNym = PendingIntent.getBroadcast(this, 0, new Intent(TorControlCommands.SIGNAL_NEWNYM), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                mNotifyBuilder.addAction(R.drawable.ic_refresh_white_24dp, getString(R.string.menu_new_identity), pendingIntentNewNym);
            } // Tor connection is off
            else if (mCurrentStatus.equals(STATUS_OFF)) {
                var pendingIntentConnect = PendingIntent.getBroadcast(this, 0, new Intent(LOCAL_ACTION_NOTIFICATION_START), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                mNotifyBuilder
                        .addAction(R.drawable.ic_stat_tor, getString(R.string.connect_to_tor), pendingIntentConnect)
                        .setContentText(notifyMsg)
                        .setSubText(null)
                        .setProgress(0, 0, false)
                        .setTicker(notifyType != NOTIFY_ID ? notifyMsg : null);
            }
        }
        ServiceCompat.startForeground(this, NOTIFY_ID, mNotifyBuilder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE |
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent == null) {
                Log.d(TAG, "Got null onStartCommand() intent");
                return Service.START_REDELIVER_INTENT;
            }

            final boolean shouldStartVpnFromSystemIntent = !intent.getBooleanExtra(OrbotConstants.EXTRA_NOT_SYSTEM, false);

            if (mCurrentStatus.equals(STATUS_OFF))
                showToolbarNotification(getString(R.string.open_orbot_to_connect_to_tor), NOTIFY_ID, R.drawable.ic_stat_tor);

            if (shouldStartVpnFromSystemIntent) {
                Log.d(TAG, "Starting VPN from system intent: " + intent);
                showToolbarNotification(getString(R.string.status_starting_up), NOTIFY_ID, R.drawable.ic_stat_tor);
                if (VpnService.prepare(this) == null) {
                    // Power-user mode doesn't matter here. If the system is starting the VPN, i.e.
                    // via always-on VPN, we need to start it regardless.
                    Prefs.putUseVpn(true);
                    mExecutor.execute(new IncomingIntentRouter(new Intent(ACTION_START)));
                    mExecutor.execute(new IncomingIntentRouter(new Intent(ACTION_START_VPN)));
                } else {
                    Log.wtf(TAG, "Could not start VPN from system because it is not prepared, which should be impossible!");
                }
            } else {
                mExecutor.execute(new IncomingIntentRouter(intent));
            }
        } catch (RuntimeException re) {
            //catch this to avoid malicious launches as document Cure53 Audit: ORB-01-009 WP1/2: Orbot DoS via exported activity (High)
            Log.e(TAG, "error with OrbotService", re);
        }

        return Service.START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(mActionBroadcastReceiver);
            unregisterReceiver(mPowerReceiver);
            mSnowflakeProxyWrapper.stopProxy(); // stop snowflake proxy if its somehow running
        } catch (IllegalArgumentException iae) {
            //not registered yet
        }
        super.onDestroy();
    }

    private void stopTorAsync(boolean showNotification) {
        debug("stopTorAsync");

        if (showNotification) sendCallbackLogMessage(getString(R.string.status_shutting_down));

        Prefs.getTransport().stop();

        stopTor();

        //stop the foreground priority and make sure to remove the persistent notification
        stopForeground(!showNotification);
        if (showNotification) sendCallbackLogMessage(getString(R.string.status_disabled));

        mPortDns = -1;
        mPortSOCKS = -1;
        mPortHTTP = -1;
        mPortTrans = -1;

        if (!showNotification) {
            clearNotifications();
            stopSelf();
        }
    }

    private void stopTorOnError(String message) {
        stopTorAsync(false);
        showToolbarNotification(getString(R.string.unable_to_start_tor) + ": " + message, ERROR_NOTIFY_ID, R.drawable.ic_stat_notifyerr);
    }

    private static HashMap<String, String> mFronts;

    public static void loadCdnFronts(Context context) {
        if (mFronts != null) return;
        mFronts = ContentDeliveryNetworkFronts.localFronts(context);
    }

## FILE: /Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/ClearnetGatewayForwarder.kt
package com.ustadmobile.meshrabiya.vnet

import android.net.Network
import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Forwards CLEARNET-routed VirtualPackets to the internet via [Network.bindSocket].
 * Uses the provided [Network] object (the internet WiFi interface) so sockets bypass the
 * active Orbot VPN tunnel. UDP-only in Phase 1 (covers DNS, NTP, QUIC/HTTP3).
 */
class ClearnetGatewayForwarder(
    private val logger: MNetLogger,
    private val logPrefix: String,
    private val onResponsePacket: (VirtualPacket) -> Unit,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    fun forward(packet: VirtualPacket, internetWifiNetwork: Network) {
        scope.launch {
            try {
                val header = packet.header
                val destIpBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(header.toAddr)
                    .array()
                val destInetAddr = InetAddress.getByAddress(destIpBytes)
                val destPort = header.toPort.toInt() and 0xFFFF
                val payloadSize = header.payloadSize
                val payload = packet.data.copyOfRange(
                    packet.payloadOffset,
                    packet.payloadOffset + payloadSize
                )

                logger(Log.DEBUG, "$logPrefix forward: dst=${destInetAddr.hostAddress}:$destPort payloadSize=$payloadSize")

                val socket = DatagramSocket()
                internetWifiNetwork.bindSocket(socket)
                socket.soTimeout = 5_000
                socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(destInetAddr, destPort)))

                val responseBuffer = ByteArray(65_535)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                try {
                    socket.receive(responsePacket)
                    val responseData = responsePacket.data.copyOf(responsePacket.length)
                    val returnHeader = VirtualPacketHeader(
                        toAddr = header.fromAddr,
                        toPort = header.fromPort,
                        fromAddr = header.toAddr,
                        fromPort = destPort,
                        lastHopAddr = 0,
                        hopCount = 0,
                        maxHops = header.maxHops,
                        gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE,
                        payloadSize = responseData.size,
                    )
                    onResponsePacket(VirtualPacket.fromHeaderAndPayloadData(returnHeader, responseData, 0))
                    logger(Log.DEBUG, "$logPrefix response ${responsePacket.length} bytes → ${header.fromAddr}")
                } catch (e: java.net.SocketTimeoutException) {
                    logger(Log.WARN, "$logPrefix response timeout for $destInetAddr:$destPort")
                } finally {
                    socket.close()
                }
            } catch (e: IOException) {
                logger(Log.WARN, "$logPrefix forward error: ${e.message}")
            } catch (e: Exception) {
                logger(Log.ERROR, "$logPrefix unexpected error: ${e.message}", e)
            }
        }
    }

    fun close() {
        job.cancel()
        logger(Log.INFO, "$logPrefix ClearnetGatewayForwarder: closed")
    }

## FILE: /Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/GatewayRouter.kt
package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.log.MNetLogger
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes packets through selected gateways with multiplexing support.
 * 
 * Two main behaviors:
 * 1. CLIENT NODE: Select gateway from topology, route packet to gateway node
 * 2. GATEWAY NODE: Route packet through configured proxy (Tor/etc)
 * 
 * @param gatewaySelector Component for selecting optimal gateways
 * @param virtualNode Reference to the VirtualNode for routing operations
 * @param logger Logging function
 * @param localNodeAddress Virtual address of this node
 */
class GatewayRouter(
    private val gatewaySelector: GatewaySelector,
    private val virtualNode: VirtualNode,
    private val logger: MNetLogger,
    private val localNodeAddress: Int
) {
    
    private val logPrefix = "[GatewayRouter ${localNodeAddress.addressToDotNotation()}]"
    
    // Round-robin counter for multiplexing
    private val roundRobinCounter = AtomicInteger(0)
    
    // Cache of active gateway pools (per gateway type)
    private val gatewayPools: MutableMap<MeshRole, CachedGatewayPool> = mutableMapOf()
    
    /**
     * Cached gateway pool with timestamp
     */
    private data class CachedGatewayPool(
        val gateways: List<GatewayNode>,
        val cachedAt: Long = System.currentTimeMillis()
    ) {
        fun isStale(thresholdMs: Long = 30_000): Boolean {
            return (System.currentTimeMillis() - cachedAt) > thresholdMs
        }
    }
    
    /**
     * Route packet through appropriate gateway based on destination.
     * CLIENT NODE behavior: Select gateway, route to gateway node
     * GATEWAY NODE behavior: Route through proxy
     * 
     * @param packet Virtual packet to route
     * @param gatewayType Type of gateway needed (TOR/CLEARNET/I2P)
     * @return true if routing succeeded
     */
    fun routeToGateway(
        packet: VirtualPacket,
        gatewayType: MeshRole
    ): Boolean {
        // Check if THIS node is a gateway (should route through proxy instead)
        if (virtualNode.isGatewayNode(gatewayType)) {
            return routeThroughProxyAsGateway(packet, gatewayType)
        }
        
        // CLIENT NODE: Select gateways (use cached pool or refresh)
        val gateways = getOrRefreshGatewayPool(gatewayType)
        
        return when {
            gateways.isEmpty() -> {
                logger(
                    Log.WARN,
                    "$logPrefix No gateways available for $gatewayType, falling back to direct routing"
                )
                virtualNode.route(packet)
                true  // route() returns Unit, so return true
            }
            
            gateways.size == 1 -> {
                // Single gateway - simple routing
                routeViaGatewayNode(packet, gateways.first())
            }
            
            else -> {
                // Multiple gateways - use multiplexing
                routeViaMultiplexedGateways(packet, gateways)
            }
        }
    }
    
    /**
     * GATEWAY NODE behavior: Route packet through configured proxy
     * Integrates with existing VirtualNode.routeViaProxy() method
     * 
     * @param packet Virtual packet to route
     * @param gatewayType Gateway type (for logging)
     * @return true if routing succeeded
     */
    private fun routeThroughProxyAsGateway(packet: VirtualPacket, gatewayType: MeshRole): Boolean {
        logger(
            Log.INFO,
            "$logPrefix This node is a $gatewayType, routing through proxy"
        )
        
        // Use existing proxy routing logic from VirtualNode
        return try {
            virtualNode.routeViaProxy(packet)
        } catch (e: Exception) {
            logger(
                Log.ERROR,
                "$logPrefix Failed to route via proxy: ${e.message}"
            )
            false
        }
    }
    
    /**
     * Get gateway pool, refresh if stale
     * @param gatewayType Gateway type to get pool for
     * @return List of available gateways (may be empty)
     */
    private fun getOrRefreshGatewayPool(gatewayType: MeshRole): List<GatewayNode> {
        // Check if pool exists and is fresh (< 30 seconds old)
        val cached = gatewayPools[gatewayType]
        if (cached != null && !cached.isStale()) {
            return cached.gateways
        }
        
        // Refresh gateway pool
        val result = gatewaySelector.selectMultipleGateways(
            gatewayType = gatewayType,
            maxCount = 3,  // Use up to 3 gateways
            strategy = DistributionStrategy.WEIGHTED
        )
        
        val newPool = when (result) {
            is GatewaySelectionResult.MultipleGateways -> result.gateways
            is GatewaySelectionResult.SingleGateway -> 
                listOf(GatewayNode(result.nodeAddress, result.suitability, result.hopCount))
            else -> emptyList()
        }
        
        gatewayPools[gatewayType] = CachedGatewayPool(newPool)
        return newPool
    }
    
    /**
     * CLIENT NODE: Route via single gateway node
     * @param packet Virtual packet to route
     * @param gateway Gateway node to route through
     * @return true if routing succeeded
     */
    private fun routeViaGatewayNode(packet: VirtualPacket, gateway: GatewayNode): Boolean {
        logger(
            Log.DEBUG,
            "$logPrefix CLIENT: Routing via gateway ${gateway.nodeAddress.addressToDotNotation()}"
        )
        
        // Modify packet header to route through gateway node
        val modifiedHeader = VirtualPacketHeader(
            toAddr = gateway.nodeAddress,  // Set gateway as next hop
            toPort = packet.header.toPort,
            fromAddr = packet.header.fromAddr,
            fromPort = packet.header.fromPort,
            lastHopAddr = packet.header.lastHopAddr,
            hopCount = packet.header.hopCount,
            maxHops = packet.header.maxHops,
            gatewayType = packet.header.gatewayType, //V3: Preserve gateway type
            payloadSize = packet.header.payloadSize
        )
        
        val modifiedPacket = VirtualPacket.fromHeaderAndPayloadData(
            header = modifiedHeader,
            data = packet.data,
            payloadOffset = packet.payloadOffset
        )
        
        virtualNode.route(modifiedPacket)
        return true
    }
    
    /**
     * CLIENT NODE: Route via multiple gateways (multiplexing)
     * Uses round-robin to distribute packets across gateways
     * 
     * @param packet Virtual packet to route
     * @param gateways List of available gateways
     * @return true if routing succeeded
     */
    private fun routeViaMultiplexedGateways(packet: VirtualPacket, gateways: List<GatewayNode>): Boolean {
        val index = roundRobinCounter.getAndIncrement() % gateways.size
        val selectedGateway = gateways[index]
        
        logger(
            Log.DEBUG,
            "$logPrefix Multiplexing: selected gateway ${selectedGateway.nodeAddress.addressToDotNotation()} (${index + 1}/${gateways.size})"
        )
        
        return routeViaGatewayNode(packet, selectedGateway)
    }
    
    /**
     * Clear gateway pool cache (call when topology changes significantly)
     */
    fun clearGatewayPools() {
        gatewayPools.clear()
        logger(
            Log.INFO,
            "$logPrefix Cleared gateway pool cache"
        )
    }
}

}

    private val _bluetoothState = MutableStateFlow(MeshrabiyaBluetoothState())


    private fun updateBluetoothState() {
        try {
            val deviceName = bluetoothAdapter?.name
            _bluetoothState.takeIf { it.value.deviceName != deviceName }?.value =
                MeshrabiyaBluetoothState(deviceName = deviceName)
        } catch (e: SecurityException) {
            logger(Log.WARN, "Could not get device name", e)
        }
    }

    private val bluetoothStateBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        updateBluetoothState()
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        _bluetoothState.value = MeshrabiyaBluetoothState(
                            deviceName = null
                        )
                    }
                }
            }
        }
    }

    private val receiverRegistered = AtomicBoolean(false)

    init {
        Log.d("AndroidVirtualNode", "Constructed with appContext: $appContext")
        if (appContext == null) {
            Log.e("AndroidVirtualNode", "appContext is NULL in constructor!")
        }
        appContext.registerReceiver(
            bluetoothStateBroadcastReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )

        receiverRegistered.set(true)

        coroutineScope.launch {
            meshrabiyaWifiManager.state.combine(_bluetoothState) { wifiState, bluetoothState ->
                wifiState to bluetoothState
            }.collect {
                _state.update { prev ->
                    prev.copy(
                        wifiState = it.first,
                        bluetoothState = it.second,
                        connectUri = generateConnectLink(
                            hotspot = it.first.connectConfig,
                            bluetoothConfig = it.second,
                        ).uri
                    )
                }
            }
        }
    }

## FILE: /Users/dreadstar/workspace/orbot-android/Meshrabiya/test-app/src/main/java/com/ustadmobile/meshrabiya/testapp/App.kt
package com.ustadmobile.meshrabiya.testapp

## COLLECTION VERIFICATION
Files written:
- AndroidVirtualNode.kt
- ClearnetGatewayForwarder.kt
- VirtualNodeDatagramSocket.kt
- NetworkStateReceiver.kt (NOT FOUND)
- OrbotService.java
- App.kt (integration glue)
- DtoModels.kt (MeshState DTO)
- GatewayRouter.kt

Total: 7 of 8 required

## COLLECTION COMPLETE
Total files collected: 7

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.asInetAddress
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.randomApipaAddr
import com.ustadmobile.meshrabiya.testapp.server.TestAppServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.acra.ACRA
import org.acra.config.CoreConfigurationBuilder
import org.acra.config.HttpSenderConfigurationBuilder
import org.acra.data.StringFormat
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton
import java.io.File
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date

class App: Application(), DIAware {

    val ADDRESS_PREF_KEY = intPreferencesKey("virtualaddr")

    @SuppressLint("SimpleDateFormat")
    private val diModule = DI.Module("meshrabiya-module") {

        bind<InetAddress>(tag = TAG_VIRTUAL_ADDRESS) with singleton() {
            runBlocking {
                val addr = applicationContext.dataStore.data.map { preferences ->
                    preferences[ADDRESS_PREF_KEY] ?: 0
                }.first()

                if(addr != 0) {
                    addr.asInetAddress()
                }else {
                    randomApipaAddr().also { randomAddress ->
                        applicationContext.dataStore.edit {
                            it[ADDRESS_PREF_KEY] = randomAddress
                        }
                    }.asInetAddress()
                }
            }
        }

        bind<MNetLogger>() with singleton {
            val logFileNameDateComp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            val logDir: File = instance(tag = TAG_LOG_DIR)
            MNetLoggerAndroid(
                deviceInfoStr = meshrabiyaDeviceInfoStr(),
                minLogLevel = Log.DEBUG,
                logFile = File(logDir, "${logFileNameDateComp}_${Build.MANUFACTURER}_${Build.MODEL}.log")
            )
        }

        bind<Json>() with singleton {
            Json {
                encodeDefaults = true
            }
        }

        bind<File>(tag = TAG_LOG_DIR) with singleton {
            File(filesDir, "log")
        }

        bind<File>(tag = TAG_WWW_DIR) with singleton {
            File(filesDir, "www").also {
                if(!it.exists())
                    it.mkdirs()
            }
        }

        bind<File>(tag = TAG_RECEIVE_DIR) with singleton {
            File(filesDir, "receive")
        }

        bind<AndroidVirtualNode>() with singleton {
            AndroidVirtualNode(
                appContext = applicationContext,
                logger = instance(),
                json = instance(),
                address = instance(tag = TAG_VIRTUAL_ADDRESS),
                dataStore = applicationContext.dataStore
            )
        }

        bind<OkHttpClient>() with singleton {
            val node: AndroidVirtualNode = instance()
            //Local connections, even when fast and with high throughput, can have high latency
            OkHttpClient.Builder()
                .socketFactory(node.socketFactory)
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .build()
        }

        bind<TestAppServer>() with singleton {
            val node: AndroidVirtualNode = instance()
            TestAppServer(
                appContext = applicationContext,
                httpClient = instance(),
                mLogger = instance(),
                port = TestAppServer.DEFAULT_PORT,
                name = node.addressAsInt.addressToDotNotation(),
                localVirtualAddr = node.address,
                receiveDir = instance(tag = TAG_RECEIVE_DIR),
                json = instance(),
            )
        }

        onReady {
            instance<TestAppServer>().start()
        }

    }

    override val di: DI by DI.lazy {
        import(diModule)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        ACRA.init(this, CoreConfigurationBuilder()
            .withBuildConfigClass(BuildConfig::class.java)
            .withReportFormat(StringFormat.JSON)
            .withLogcatArguments(listOf("-t", "200", "-v", "time"))
            .withPluginConfigurations(
                HttpSenderConfigurationBuilder()
                    .withUri(BuildConfig.ACRA_HTTP_URI)
                    .withBasicAuthLogin(BuildConfig.ACRA_BASIC_LOGIN)
                    .withBasicAuthPassword(BuildConfig.ACRA_BASIC_PASSWORD)
                    .build()
            )
        )
    }


    companion object {

        const val TAG_VIRTUAL_ADDRESS = "virtual_add"

        const val TAG_WWW_DIR = "www_dir"

        const val TAG_LOG_DIR = "log_dir"

    }
}

    override fun close() {
        super.close()

        if (receiverRegistered.getAndSet(false)) {
            appContext.unregisterReceiver(bluetoothStateBroadcastReceiver)
        }
        // scheduledExecutorService.shutdown()  TODO find out where this goes
    }

    suspend fun connectAsStation(
        config: WifiConnectConfig,
    ) {
        meshrabiyaWifiManager.connectToHotspot(config)
    }

    suspend fun disconnectWifiStation() {
        meshrabiyaWifiManager.disconnectStation()
    }

    override suspend fun setWifiHotspotEnabled(
        enabled: Boolean,
        preferredBand: ConnectBand,
        hotspotType: HotspotType,
        preferredPassphrase: String?,
    ): LocalHotspotResponse? {
        updateBluetoothState()
        
        if (enabled) {
            // On concurrent AP+STA capable devices (API 30+), do NOT disconnect the station.
            // The station WiFi is the internet connection that MESH_ROUTER is designed to keep.
            // On non-concurrent devices (or devices where this hasn't been detected yet),
            // the existing disconnect-before-hotspot behavior is preserved.
            if (!meshrabiyaWifiManager.currentWifiState.concurrentApStationSupported) {
                logger(Log.INFO, "setWifiHotspotEnabled: Disconnecting from station (non-concurrent device)", null)
                meshrabiyaWifiManager.disconnectStation()
                logger(Log.INFO, "setWifiHotspotEnabled: Waiting 2 seconds for WiFi disconnect to stabilize...", null)
                kotlinx.coroutines.delay(2000)
                logger(Log.INFO, "setWifiHotspotEnabled: Proceeding with hotspot creation", null)
            } else {
                logger(Log.INFO, "setWifiHotspotEnabled: AP+STA concurrent device — keeping internet WiFi, proceeding directly", null)
            }
        }
        
        return super.setWifiHotspotEnabled(enabled, preferredBand, hotspotType, preferredPassphrase)
    }

    suspend fun lookupStoredBssid(ssid: String): String? {
        return meshrabiyaWifiManager.lookupStoredBssid(ssid)
    }

    /**
     * Store the BSSID for the given SSID. This ensures that when we make subsequent connection
     * attempts we don't need to use the companiondevicemanager again. The BSSID must be provided
     * when reconnecting on Android 10+ if we want to avoid a confirmation dialog.
     */
    fun storeBssid(ssid: String, bssid: String?) {
        logger(Log.DEBUG, "AndroidVirtualNode: storeBssid: Store BSSID for $ssid : $bssid")
        if (bssid != null) {
            coroutineScope.launch {
                meshrabiyaWifiManager.storeBssidForAddress(ssid, bssid)
            }
        } else {
            logger(Log.WARN, "AndroidVirtualNode: storeBssid: BSSID for $ssid is NULL, can't save to avoid prompts on reconnect")
        }
    }

    override fun notifyHotspotInterference(reconnectionCount: Int) {
        super.notifyHotspotInterference(reconnectionCount)
        logger(Log.WARN, "[HOTSPOT ALERT] WiFi interference detected: $reconnectionCount reconnection attempts suppressed", null)
    }

    override fun notifyHotspotLost(reason: String) {
        super.notifyHotspotLost(reason)
        logger(Log.ERROR, "[HOTSPOT ALERT] Hotspot lost: $reason", null)
    }

    override fun onClearnetGatewayPacket(packet: VirtualPacket): Boolean {
        val internetNetwork = meshrabiyaWifiManager.internetWifiNetwork
        return if (internetNetwork != null) {
            clearnetGatewayForwarder.forward(packet, internetNetwork)
            true
        } else {
            logger(Log.WARN, "$logPrefix CLEARNET gateway: no internet WiFi network bound, dropping packet", null)
            false
        }
    }

    
}
