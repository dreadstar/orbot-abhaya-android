# MESH_GATEWAY_ROUTING_DEBUG_PT2


## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshLocalSocksProxy.kt
package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory

/**
 * Local SOCKS5 proxy server (client-side mesh proxy).
 *
 * Listens on a loopback TCP port (dynamically assigned). When OrbotVpnManager routes
 * mesh-proxy-app traffic here via go-tun2socks, this proxy:
 *  1. Completes the SOCKS5 handshake with the app's TCP connection.
 *  2. Resolves the best available CLEARNET_GATEWAY virtual address from the mesh topology.
 *  3. Opens a ChainSocket TCP connection to that gateway's MeshInternetRelayServer
 *     at [MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT] using [meshSocketFactory].
 *  4. Sends a 6-byte relay header [4-byte IPv4 dest addr][2-byte dest port big-endian]
 *     and then relays bytes bidirectionally.
 *
 * If no CLEARNET_GATEWAY is reachable, replies with SOCKS5 "host unreachable" (0x04).
 *
 * @param logger             Mesh logger.
 * @param logPrefix          Log tag prefix.
 * @param meshSocketFactory  [VirtualNode.socketFactory] — routes TCP through mesh via ChainSocket.
 * @param getGatewayAddress  Lambda returning the virtual [InetAddress] of the best
 *                           CLEARNET_GATEWAY node, or null if none is available.
 */
class MeshLocalSocksProxy(
    private val logger: MNetLogger,
    private val logPrefix: String,
    private val meshSocketFactory: SocketFactory,
    private val getGatewayAddress: () -> InetAddress?,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    @Volatile private var serverSocket: ServerSocket? = null

    /** The loopback TCP port this proxy is listening on. 0 if not started. */
    val localPort: Int get() = serverSocket?.localPort ?: 0

    /** Start accepting connections. Idempotent — safe to call multiple times. */
    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        serverSocket = ss
        logger(Log.INFO, "$logPrefix MeshLocalSocksProxy started on port ${ss.localPort}")
        scope.launch {
            try {
                while (true) {
                    val client = ss.accept()
                    launch { handleSocks5(client) }
                }
            } catch (e: IOException) {
                logger(Log.INFO, "$logPrefix MeshLocalSocksProxy stopped: ${e.message}")
            }
        }
    }

    /** Stop accepting new connections. Idempotent. */
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleSocks5(client: Socket) {
        try {
            val input: InputStream = client.getInputStream()
            val output: OutputStream = client.getOutputStream()

            // --- SOCKS5 greeting ---
            val verBuf = ByteArray(2)
            input.readFully(verBuf)
            if (verBuf[0] != 0x05.toByte()) { client.close(); return }
            val nMethods = verBuf[1].toInt() and 0xFF
            if (nMethods > 0) input.readFully(ByteArray(nMethods))
            output.write(byteArrayOf(0x05, 0x00)) // server selects NO_AUTH
            output.flush()

            // --- SOCKS5 CONNECT request ---
            val reqBase = ByteArray(4)
            input.readFully(reqBase)
            if (reqBase[0] != 0x05.toByte() || reqBase[1] != 0x01.toByte()) {
                // Only CONNECT (0x01) supported
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                client.close(); return
            }
            val atyp = reqBase[3].toInt() and 0xFF
            val destAddr: InetAddress = when (atyp) {
                0x01 -> { // IPv4
                    val ip = ByteArray(4); input.readFully(ip); InetAddress.getByAddress(ip)
                }
                0x03 -> { // Domain name
                    val len = input.read()
                    val domain = ByteArray(len); input.readFully(domain)
                    InetAddress.getByName(String(domain))
                }
                0x04 -> { // IPv6
                    val ip = ByteArray(16); input.readFully(ip); InetAddress.getByAddress(ip)
                }
                else -> { client.close(); return }
            }
            val portBytes = ByteArray(2); input.readFully(portBytes)
            val destPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            // --- Resolve gateway ---
            val gatewayAddr = getGatewayAddress()
            if (gatewayAddr == null) {
                logger(Log.WARN, "$logPrefix No CLEARNET_GATEWAY available for ${destAddr.hostAddress}:$destPort")
                output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // host unreachable
                client.close(); return
            }

            logger(Log.DEBUG, "$logPrefix → ${destAddr.hostAddress}:$destPort via gateway ${gatewayAddr.hostAddress}")

            // --- Open ChainSocket to gateway's relay server ---
            val meshSocket: Socket = meshSocketFactory.createSocket(
                gatewayAddr, MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT
            )

            // Send 6-byte relay header: [4-byte dest IPv4][2-byte dest port]
            val destIpBytes = destAddr.address // 4 bytes for IPv4 (resolved above)
            val relayHeader = byteArrayOf(
                destIpBytes[0], destIpBytes[1], destIpBytes[2], destIpBytes[3],
                ((destPort shr 8) and 0xFF).toByte(),
                (destPort and 0xFF).toByte()
            )
            meshSocket.getOutputStream().write(relayHeader)
            meshSocket.getOutputStream().flush()

            // Read 1-byte ACK from relay server (0x00 = success)
            val ack = meshSocket.getInputStream().read()
            if (ack != 0x00) {
                logger(Log.WARN, "$logPrefix Relay server rejected ${destAddr.hostAddress}:$destPort ack=$ack")
                output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                meshSocket.close(); client.close(); return
            }

            // --- Send SOCKS5 success reply to client ---
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            // --- Bidirectional relay ---
            val upThread = Thread {
                try { input.copyTo(meshSocket.outputStream) } catch (_: Exception) {}
                finally { try { meshSocket.close() } catch (_: Exception) {} }
            }
            upThread.isDaemon = true
            upThread.start()
            try { meshSocket.inputStream.copyTo(output) } catch (_: Exception) {}
            finally {
                try { client.close() } catch (_: Exception) {}
                try { meshSocket.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logger(Log.WARN, "$logPrefix SOCKS5 handler error: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun close() {
        stop()
        job.cancel()
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = read(buf, offset, buf.size - offset)
            if (n < 0) throw IOException("Unexpected EOF (read ${offset}/${buf.size} bytes)")
            offset += n
        }
    }
}


## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt
package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.prefixMatches
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.mmcp.*
import com.ustadmobile.meshrabiya.portforward.ForwardBindPoint
import com.ustadmobile.meshrabiya.portforward.UdpForwardRule
import com.ustadmobile.meshrabiya.util.findFreePort
import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.datagram.VirtualDatagramSocket2
import com.ustadmobile.meshrabiya.vnet.datagram.VirtualDatagramSocketImpl
import com.ustadmobile.meshrabiya.vnet.socket.*
import com.ustadmobile.meshrabiya.vnet.wifi.*
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.random.Random
import com.ustadmobile.meshrabiya.service.MeshEcosystemListener
import com.ustadmobile.meshrabiya.service.MeshGossipService
import com.ustadmobile.meshrabiya.vnet.CoreGossipBroadcastService
import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
// Removed: import com.ustadmobile.meshrabiya.service.compute.IntelligentDistributedComputeService (deprecated - replaced by DistributedComputeClient/Server)
import com.ustadmobile.meshrabiya.service.compute.TaskManager
import com.ustadmobile.meshrabiya.service.compute.DistributedComputeClient
import com.ustadmobile.meshrabiya.service.compute.DistributedComputeServer
// Removed: import com.ustadmobile.meshrabiya.role.EmergentRoleManager (old package)
import com.ustadmobile.meshrabiya.vnet.OriginatingMessageManager
// NEW: Import hardware capability classes for getCurrentNodeCapabilities()
import com.ustadmobile.meshrabiya.vnet.hardware.ResourceCapabilities
import com.ustadmobile.meshrabiya.vnet.hardware.BatteryInfo
import com.ustadmobile.meshrabiya.vnet.hardware.BatteryHealth
import com.ustadmobile.meshrabiya.vnet.hardware.PowerState
import com.ustadmobile.meshrabiya.vnet.hardware.ThermalState  // Use hardware package version

import com.ustadmobile.meshrabiya.service.MeshEcosystemMessage
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import android.content.Context
// import com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastMessageHandler
import com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastPacketSerializer

//Generate a random Automatic Private IP Address
fun randomApipaAddr(): Int {
    //169.254
    val fixedSection = (169 shl 24).or(254 shl 16)
    val randomSection = Random.nextInt(Short.MAX_VALUE.toInt())
    return fixedSection.or(randomSection)
}

fun randomApipaInetAddr() = InetAddress.getByAddress(randomApipaAddr().addressToByteArray())



/**
 * Mashrabiya Node
 *
 * Connection refers to the underlying "real" connection to some other device. There may be multiple
 * connections to the same remote node (e.g. Bluetooth, Sockets running over WiFi, etc)
 *
 * Addresses are 32 bit integers in the APIPA range
 */
interface HasNodeState {
    val currentNodeState: LocalNodeState
}

abstract class VirtualNode(
    val port: Int = 0,
    val json: Json = Json,
    val logger: MNetLogger = MNetLoggerStdout(),
    final override val address: InetAddress = randomApipaInetAddr(),
    override val networkPrefixLength: Int = 16,
    val config: NodeConfig = NodeConfig.DEFAULT_CONFIG,
    val appContext: Context
): VirtualRouter, Closeable, HasNodeState {

    /**
     * Data class to hold real-time bit rate metrics.
     */
    data class BitRateMetrics(
        val uploadBitRateBps: Long = 0L,
        val downloadBitRateBps: Long = 0L
    )

    // StateFlow to expose real-time bit rate metrics
    private val _bitRateMetrics = MutableStateFlow(BitRateMetrics())
    val bitRateMetrics: StateFlow<BitRateMetrics> = _bitRateMetrics.asStateFlow()

    val addressAsInt: Int = address.requireAddressAsInt()
    fun getInetAddressFor(addr: Int) = InetAddress.getByAddress(addr.addressToByteArray())
    /**
     * Provides context for service initialization.
     * Must be implemented by platform-specific subclasses (e.g., AndroidVirtualNode).
     */
    protected abstract fun getContext(): android.content.Context?

    // --- Proxy connection info ---
    @Volatile
    private var proxyHost: String? = null
    @Volatile
    private var proxyPort: Int? = null
    @Volatile
    private var proxyActive: Boolean = false

    // --- Set proxy connection info ---
    fun setProxy(host: String, port: Int) {
        proxyHost = host
        proxyPort = port
        logger(Log.INFO, "$logPrefix Proxy set to $host:$port", null)
    }

    // --- Set proxy active/inactive ---
    fun setProxyActive(active: Boolean) {
        proxyActive = active
        logger(Log.INFO, "$logPrefix Proxy active set to $active", null)
    }


    //This executor is used for direct I/O activities
    internal val connectionExecutor: ExecutorService = Executors.newCachedThreadPool()

    //This executor is used to schedule maintenance e.g. pings etc.
    protected val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    protected val coroutineScope = CoroutineScope(Dispatchers.Default + Job())

    /**
     * Public method to increment uploadBytes in LocalNodeState in a thread-safe way.
     */
    fun incrementUploadBytes(amount: Long) {
        updateNodeState { prev ->
            prev.copy(uploadBytes = prev.uploadBytes + amount)
        }
    }

    /**
     * Public method to increment downloadBytes in LocalNodeState in a thread-safe way.
     */
    fun incrementDownloadBytes(amount: Long) {
        updateNodeState { prev ->
            prev.copy(downloadBytes = prev.downloadBytes + amount)
        }
    }

    private val messageCounter = AtomicInteger(0)

    protected open val _state = MutableStateFlow(LocalNodeState())

    val state: Flow<LocalNodeState> = _state.asStateFlow()

    override val currentNodeState: LocalNodeState
        get() = _state.value

    protected fun updateNodeState(update: (LocalNodeState) -> LocalNodeState) {
        _state.update(update)
    }

    abstract val meshrabiyaWifiManager: MeshrabiyaWifiManager

    private val pongListeners = CopyOnWriteArrayList<PongListener>()

    protected val logPrefix: String = "[VirtualNode ${addressAsInt.addressToDotNotation()}]"

    protected val iDatagramSocketFactory = VirtualNodeReturnPathSocketFactory(this)

    private val forwardingRules: MutableMap<ForwardBindPoint, UdpForwardRule> = ConcurrentHashMap()

    // MeshConnectionPool: instantiate and initialize singleton
    protected val meshConnectionPool: MeshConnectionPool = MeshConnectionPool(this)
    init {
        MeshConnectionPool.init(this)
        // Coroutine to calculate and update real-time bit rates
        coroutineScope.launch {
            var lastUploadBytes = currentNodeState.uploadBytes
            var lastDownloadBytes = currentNodeState.downloadBytes
            var lastTimestamp = System.currentTimeMillis()
            while (true) {
                delay(1000L) // 1 second interval
                val now = System.currentTimeMillis()
                val elapsedMs = now - lastTimestamp
                val elapsedSec = if (elapsedMs > 0) elapsedMs / 1000.0 else 1.0
                val currentUpload = currentNodeState.uploadBytes
                val currentDownload = currentNodeState.downloadBytes
                val uploadDelta = currentUpload - lastUploadBytes
                val downloadDelta = currentDownload - lastDownloadBytes
                val uploadBps = (uploadDelta / elapsedSec).toLong()
                val downloadBps = (downloadDelta / elapsedSec).toLong()
                _bitRateMetrics.value = BitRateMetrics(
                    uploadBitRateBps = if (uploadBps >= 0) uploadBps else 0L,
                    downloadBitRateBps = if (downloadBps >= 0) downloadBps else 0L
                )
                lastUploadBytes = currentUpload
                lastDownloadBytes = currentDownload
                lastTimestamp = now
            }
        }
    }

    data class LastOriginatorMessage(
        val originatorMessage: MmcpOriginatorMessage,  // Correct type
        val timeReceived: Long,
        val lastHopAddr: Int,
        val hopCount: Byte,
        val lastHopRealInetAddr: InetAddress,
        val receivedFromSocket: VirtualNodeDatagramSocket,
        val lastHopRealPort: Int,
        val neighborAddr: InetAddress,
    )

    @Suppress("unused")
    enum class Zone {
        VNET, REAL
    }

    /**
     * Get current node capabilities. Default implementation returns a basic snapshot.
     * Can be overridden by subclasses to provide real hardware metrics.
     */
    protected open fun getCurrentNodeCapabilities(): NodeCapabilitySnapshot {
        return NodeCapabilitySnapshot(
            nodeId = addressAsInt.toString(),
            resources = ResourceCapabilities(
                availableCPU = 0.5f,
                availableRAM = Runtime.getRuntime().freeMemory(),
                availableBandwidth = 10_000_000L,
                storageOffered = 0L,
                batteryLevel = 50,
                thermalThrottling = false,
                powerState = PowerState.BATTERY_MEDIUM,

            
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 50,
                isCharging = false,
                estimatedTimeRemaining = null,
                temperatureCelsius = 25,
                health = BatteryHealth.GOOD,
                chargingSource = null
            ),
            thermalState = ThermalState.COOL,
            networkQuality = 0.5f,
            stability = 0.8f
        )
    }

    // === STEP 1: Create EmergentRoleManager with topology callback ===
    open val emergentRoleManager: EmergentRoleManager = EmergentRoleManager(
        virtualNode = this,
        context = appContext,
        getTopologyMap = { originatingMessageManager.getTopologyMapInfo() },
        getCurrentNodeCapabilities = { getCurrentNodeCapabilities() }
    )

    // === STEP 2: Create OriginatingMessageManager with EmergentRoleManager callbacks ===
    open val originatingMessageManager = OriginatingMessageManager(
        localNodeInetAddr = address,
        logger = logger,
        scheduledExecutor = scheduledExecutor,
        nextMmcpMessageId = { nextMmcpMessageId() },
        getWifiState = { currentNodeState.wifiState },
        
        // === NEW: Callbacks to EmergentRoleManager ===
        getCentralityScore = { emergentRoleManager.calculateCentralityScore() },
        getMeshRoles = { emergentRoleManager.currentMeshRoles.value },
        getFitnessScore = { 
            emergentRoleManager.calculateNormalizedFitness(getCurrentNodeCapabilities()) 
        },
        getInternetSignalInfo = {
            (meshrabiyaWifiManager as? MeshrabiyaWifiManagerAndroid)
                ?.getInternetWifiSignalInfo()
                ?.let { Pair(it.rssiDbm, it.linkSpeedMbps) }
                ?: Pair(0, 0)
        },

        // === EXISTING PARAMS ===
        pingTimeout = 15_000,
        originatingMessageNodeLostThreshold = 10_000,
        lostNodeCheckInterval = 1_000
    )

    // === Gateway Selector and Router (Phase 4) ===
    protected val gatewaySelector: GatewaySelector by lazy {
        GatewaySelector(
            originatingMessageManager = originatingMessageManager,
            emergentRoleManager = emergentRoleManager,
            logger = logger,
            localNodeAddress = addressAsInt
        )
    }

    protected val gatewayRouter: GatewayRouter by lazy {
        GatewayRouter(
            gatewaySelector = gatewaySelector,
            virtualNode = this,
            logger = logger,
            localNodeAddress = addressAsInt
        )
    }

    private val localPort = findFreePort(0)

    val datagramSocket = VirtualNodeDatagramSocket(
        socket = DatagramSocket(localPort),
        ioExecutorService = connectionExecutor,
        router = this,
        localNodeVirtualAddress = addressAsInt,
        logger = logger,
        // parentNode = this
    )

    protected val chainSocketFactory: ChainSocketFactory = ChainSocketFactoryImpl(
        virtualRouter = this,
        logger = logger,
    )

    val socketFactory: SocketFactory
        get() = chainSocketFactory

    private val chainSocketServer = ChainSocketServer(
        serverSocket = ServerSocket(localPort),
        executorService = connectionExecutor,
        chainSocketFactory = chainSocketFactory,
        name = addressAsInt.addressToDotNotation(),
        logger = logger
    )

    private val _incomingMmcpMessages = MutableSharedFlow<MmcpMessageAndPacketHeader>(
        replay = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val incomingMmcpMessages: Flow<MmcpMessageAndPacketHeader> = _incomingMmcpMessages.asSharedFlow()

    private val activeSockets: MutableMap<Int, VirtualDatagramSocketImpl> = ConcurrentHashMap()

    // === New Service Instantiations ===
    
    // Core mesh services instantiated with proper dependency injection
    protected val meshGossipService: MeshGossipService = MeshGossipService.initialize(this)
    
    open val coreGossipBroadcastService: CoreGossipBroadcastService = 
        CoreGossipBroadcastService.getInstance()
    
    
    // MeshEcosystemListener depends on emergentRoleManager and meshGossipService
    protected val meshEcosystemListener: MeshEcosystemListener by lazy {
        val listener = MeshEcosystemListener(this)
        // Register compute services when they're initialized
        listener.registerComputeClient(distributedComputeClient)
        listener.registerComputeServer(distributedComputeServer)
        listener
    }
    
    // DEPRECATED: IntelligentDistributedComputeService - replaced by DistributedComputeClient/Server in CANONICAL_WORKFLOW_v2
    // Will be removed after Part 2 implementation completes
    // protected val intelligentDistributedComputeService: IntelligentDistributedComputeService by lazy {
    //     IntelligentDistributedComputeService(
    //         virtualNode = this,
    //         emergentRoleManager = emergentRoleManager,
    //         betaLogger = BetaTestLogger.getInstance(
    //             getContext() ?: throw IllegalStateException("Context required")
    //         )
    //     )
    // }
    
    // Storage service requires additional dependencies (Context, etc.)
    // Will be initialized later via initialize() method when dependencies are available
    open var distributedStorageManager: DistributedStorageManager? = null
    
    /**
     * Handler for broadcast message+file operations
     * Set by MeshrabiyaApiImpl during initialization
     * Added: 2026-02-01 for NETWORK_BROADCAST_v2 implementation
     */
    var broadcastMessageHandler: com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastMessageHandler? = null
    
    // TaskManager: Orchestrates compute task lifecycle on compute node
    protected val taskManager: TaskManager by lazy {
        TaskManager(
            context = appContext ?: throw IllegalStateException("Context required for TaskManager"),
            virtualNode = this,
            distributedStorageClient = distributedStorageManager?.getDistributedStorageClient()
                ?: throw IllegalStateException("DistributedStorageClient required for TaskManager"),
            betaLogger = BetaTestLogger.getInstance(
                appContext ?: throw IllegalStateException("Context required")
            )
        )
    }
    
    // DistributedComputeClient: Client-side distributed compute service
    protected val distributedComputeClient: DistributedComputeClient by lazy {
        DistributedComputeClient(
            context = appContext ?: throw IllegalStateException("Context required for DistributedComputeClient"),
            virtualNode = this,
            betaLogger = BetaTestLogger.getInstance(
                appContext ?: throw IllegalStateException("Context required")
            )
        )
    }
    
    /**
     * Public accessor for DistributedComputeClient (for MeshrabiyaApi)
     * Added 2025-12-06 for API-level task submission
     */
    fun obtainDistributedComputeClient(): DistributedComputeClient = distributedComputeClient
    
    /**
     * Public accessor for MeshEcosystemListener (for MeshrabiyaApi)
     * Added 2025-12-06 for distributed storage enable/disable
     */
    fun obtainMeshEcosystemListener(): MeshEcosystemListener = meshEcosystemListener
    
    // DistributedComputeServer: Server-side distributed compute service
    protected val distributedComputeServer: DistributedComputeServer by lazy {
        DistributedComputeServer(
            context = appContext ?: throw IllegalStateException("Context required for DistributedComputeServer"),
            virtualNode = this,
            emergentRoleManager = emergentRoleManager,
            taskManager = taskManager,
            distributedStorageClient = distributedStorageManager?.getDistributedStorageClient()
                ?: throw IllegalStateException("DistributedStorageClient required for DistributedComputeServer"),
            betaLogger = BetaTestLogger.getInstance(
                appContext ?: throw IllegalStateException("Context required")
            )
        )
    }
    
    // Deprecated: PythonExecutor and LiteRTEngine stubs removed. Canonical compute logic is implemented in IntelligentDistributedComputeService and PythonExecutor domain files.
    

    init {
        _state.update { prev ->
            prev.copy(
                address = addressAsInt,
                connectUri = generateConnectLink(hotspot = null).uri
            )
        }

        coroutineScope.launch {
            try {
                originatingMessageManager.state.collect { state ->
                    _state.update { prev ->
                        prev.copy(
                            originatorMessages = originatingMessageManager.getOriginatorMessages()
                        )
                    }
                }
            } catch (e: Exception) {
                safeLog(
                    LogLevel.ERROR,
                    "VirtualNode",
                    "Error in originatingMessageManager state collection",
                    mapOf("address" to address.hostAddress),
                    e
                )
            }
        }
    }

    override fun nextMmcpMessageId(): Int {
        return messageCounter.incrementAndGet()
    }

    // Abstract methods removed - now using callbacks through OriginatingMessageManager and EmergentRoleManager

    override fun allocateUdpPortOrThrow(
        virtualDatagramSocketImpl: VirtualDatagramSocketImpl,
        portNum: Int
    ): Int {
        if(portNum > 0) {
            if(activeSockets.containsKey(portNum))
                throw IllegalStateException("VirtualNode: port $portNum already allocated!")
            activeSockets[portNum] = virtualDatagramSocketImpl
            return portNum
        }

        var attemptCount = 0
        do {
            val randomPort = Random.nextInt(0, Short.MAX_VALUE.toInt())
            if(!activeSockets.containsKey(randomPort)) {
                activeSockets[randomPort] = virtualDatagramSocketImpl
                return randomPort
            }
            attemptCount++
        }while(attemptCount < 100)

        throw IllegalStateException("Could not allocate random free port")
    }

    override fun deallocatePort(protocol: Protocol, portNum: Int) {
        activeSockets.remove(portNum)
    }

    override fun notifyHotspotInterference(reconnectionCount: Int) {
        logger(Log.WARN, "Hotspot interference: WiFi reconnected $reconnectionCount times", null)
        // Subclasses can override to show UI notification
    }

    override fun notifyHotspotLost(reason: String) {
        logger(Log.ERROR, "Hotspot lost: $reason", null)
        // Subclasses can override to show UI notification
    }

    fun createDatagramSocket(): DatagramSocket {
        return VirtualDatagramSocket2(this, addressAsInt, logger, this, appContext)
    }

    fun createBoundDatagramSocket(port: Int): DatagramSocket {
        return createDatagramSocket().also {
            it.bind(InetSocketAddress(address, port))
        }
    }

    fun forward(
        bindAddress: InetAddress,
        bindPort: Int,
        destAddress: InetAddress,
        destPort: Int,
    ) : Int {
        val listenSocket = if(
            bindAddress.prefixMatches(networkPrefixLength, address)
        ) {
            createBoundDatagramSocket(bindPort)
        }else {
            DatagramSocket(bindPort, bindAddress)
        }

        val forwardRule = createForwardRule(listenSocket, destAddress, destPort)
        val boundPort = listenSocket.localPort
        forwardingRules[ForwardBindPoint(bindAddress, null, boundPort)] = forwardRule

        return boundPort
    }

    fun forward(
        bindZone: Zone,
        bindPort: Int,
        destAddress: InetAddress,
        destPort: Int
    ): Int {
        val listenSocket = if(bindZone == Zone.VNET) {
            createBoundDatagramSocket(bindPort)
        }else {
            DatagramSocket(bindPort)
        }
        val forwardRule = createForwardRule(listenSocket, destAddress, destPort)
        val boundPort = listenSocket.localPort
        forwardingRules[ForwardBindPoint(null, bindZone, boundPort)] = forwardRule
        return boundPort
    }

    fun stopForward(
        bindZone: Zone,
        bindPort: Int
    ) {

    }

    fun stopForward(
        bindAddr: InetAddress,
        bindPort: Int,
    ) {

    }

    private fun createForwardRule(
        listenSocket: DatagramSocket,
        destAddress: InetAddress,
        destPort: Int,
    ) : UdpForwardRule {
        return UdpForwardRule(
            boundSocket = listenSocket,
            ioExecutor = this.connectionExecutor,
            destAddress = destAddress,
            destPort = destPort,
            logger = logger,
            returnPathSocketFactory = iDatagramSocketFactory,
        )
    }

    override val localDatagramPort: Int
        get() = datagramSocket.localPort

    protected fun generateConnectLink(
        hotspot: WifiConnectConfig?,
        bluetoothConfig: MeshrabiyaBluetoothState? = null,
    ) : MeshrabiyaConnectLink {
        return MeshrabiyaConnectLink.fromComponents(
            nodeAddr = addressAsInt,
            port = localDatagramPort,
            hotspotConfig = hotspot,
            bluetoothConfig = bluetoothConfig,
            json = json,
        )
    }

    private fun onIncomingMmcpMessage(
        virtualPacket: VirtualPacket,
        datagramPacket: DatagramPacket?,
        datagramSocket: VirtualNodeDatagramSocket?,
    ) : Boolean {
        // CRITICAL FIX: Check if this is a broadcast packet BEFORE attempting MMCP parsing
        // Root cause: MMCP parser was intercepting broadcast packets and rejecting them as
        // "Invalid what: 0" because broadcast packet type byte (0x01) is not a valid MMCP type
        // See: BROADCAST_TRANSFER_ROOT_CAUSE_ANALYSIS_02112026.md
        val payload = virtualPacket.data
        val payloadSize = virtualPacket.header.payloadSize
        val offset = virtualPacket.payloadOffset
        
        // COMPREHENSIVE DEBUG: Log ALL packet structure details
        logger(Log.DEBUG, "$logPrefix: [PKT_CHECK] dataSize=${payload.size}, payloadOffset=$offset, payloadSize=$payloadSize, toPort=${virtualPacket.header.toPort}, fromAddr=${virtualPacket.header.fromAddr.addressToDotNotation()}")
        logger(Log.DEBUG, "$logPrefix: [PKT_CHECK] isEmpty=${payload.isEmpty()}, offsetValid=${offset < payload.size}, boundsCheck=${offset + payloadSize <= payload.size}")
        
        // Enhanced bounds checking for broadcast packet detection
        // FIXED: Check offset+4 is in bounds since packet type byte is at offset+4 per BroadcastPacketSerializer format
        if (payloadSize > 0 && offset >= 0 && offset + 4 < payload.size && offset + payloadSize <= payload.size) {
            // FIXED: Read packet type byte at offset+4, not offset
            // Per BroadcastPacketSerializer.serialize():
            //   [0-3]: Version (Int32BE)
            //   [4]: Packet Type Byte (0x01 = BROADCAST_CHUNK, 0x02 = NACK)
            val versionByte = payload[offset]  // First byte of version Int32BE (for debugging)
            val packetTypeByte = payload[offset + 4]  // Actual packet type at offset+4
            val versionByteHex = "0x${String.format("%02x", versionByte)}"
            val packetTypeHex = "0x${String.format("%02x", packetTypeByte)}"
            logger(Log.DEBUG, "$logPrefix: [PKT_CHECK] ✓ Bounds valid - versionByte=$versionByteHex, packetTypeByte=$packetTypeHex, BROADCAST_CHUNK=0x${String.format("%02x", BroadcastPacketSerializer.TYPE_BROADCAST_CHUNK.toByte())}, NACK=0x${String.format("%02x", BroadcastPacketSerializer.TYPE_NACK_REQUEST.toByte())}")
            
            // Route broadcast packets directly to handler WITHOUT MMCP parsing
            if (packetTypeByte == BroadcastPacketSerializer.TYPE_BROADCAST_CHUNK.toByte() ||
                packetTypeByte == BroadcastPacketSerializer.TYPE_NACK_REQUEST.toByte()) {
                logger(Log.INFO, "$logPrefix: [PKT_CHECK] ✅ BROADCAST PACKET DETECTED (type=$packetTypeHex) - routing to BroadcastMessageHandler")
                broadcastMessageHandler?.onReceiveBroadcastPacket(virtualPacket)
                return false  // Don't route broadcast packets through MMCP routing
            } else {
                logger(Log.DEBUG, "$logPrefix: [PKT_CHECK] Not broadcast type ($packetTypeHex) - attempting MMCP parsing")
            }
        } else {
            logger(Log.WARN, "$logPrefix: [PKT_CHECK] ❌ BOUNDS CHECK FAILED - payloadSize=$payloadSize, offset=$offset, dataSize=${payload.size}, boundsOK=${if (offset >= 0 && offset + 4 < payload.size) offset + payloadSize <= payload.size else false}")
        }

        // Handle GATEWAY_DOWN before full MMCP parsing — what=18 is not a registered MMCP class
        // and would throw IllegalArgumentException in fromVirtualPacket().
        val payloadOff = virtualPacket.payloadOffset
        if (payload.size > payloadOff && payload[payloadOff] == MmcpMessage.WHAT_GATEWAY_DOWN) {
            val senderAddr = virtualPacket.header.fromAddr
            logger(Log.INFO, "$logPrefix GATEWAY_DOWN from ${senderAddr.addressToDotNotation()}: clearing gateway roles")
            originatingMessageManager.markNodeGatewayDown(senderAddr)
            emergentRoleManager.updateRoles()
            return false
        }
        
        try {
            val mmcpMessage = MmcpMessage.fromVirtualPacket(virtualPacket)
            val from = virtualPacket.header.fromAddr
            logger(Log.VERBOSE,
                message = {
                    "$logPrefix received MMCP message (${mmcpMessage::class.simpleName}) " +
                    "from ${from.addressToDotNotation()}"
                }
            )

            val isToThisNode = virtualPacket.header.toAddr == addressAsInt

            var shouldRoute = true

            when {
                mmcpMessage is MmcpPing && isToThisNode -> {
                    logger(Log.VERBOSE,
                        message = {
                            "$logPrefix Received ping(id=${mmcpMessage.messageId}) from ${from.addressToDotNotation()}"
                        }
                    )
                    val pongMessage = MmcpPong(
                        messageId = nextMmcpMessageId(),
                        replyToMessageId = mmcpMessage.messageId
                    )

                    val replyPacket = pongMessage.toVirtualPacket(
                        toAddr = from,
                        fromAddr = addressAsInt
                    )

                    logger(Log.VERBOSE, { "$logPrefix Sending pong to ${from.addressToDotNotation()}" })
                    route(replyPacket)
                }

                mmcpMessage is MmcpPong && isToThisNode -> {
                    logger(Log.VERBOSE, { "$logPrefix Received pong(id=${mmcpMessage.messageId})}" })
                    originatingMessageManager.onPongReceived(from, mmcpMessage)
                    pongListeners.forEach {
                        it.onPongReceived(from, mmcpMessage)
                    }
                }

                mmcpMessage is MmcpHotspotRequest && isToThisNode -> {
                    logger(Log.INFO, "$logPrefix Received hotspotrequest (id=${mmcpMessage.messageId})", null)
                    coroutineScope.launch {
                        val hotspotResult = meshrabiyaWifiManager.requestHotspot(
                            mmcpMessage.messageId, mmcpMessage.hotspotRequest
                        )

                        if(from != addressAsInt) {
                            val replyPacket = MmcpHotspotResponse(
                                messageId = mmcpMessage.messageId,
                                result = hotspotResult
                            ).toVirtualPacket(
                                toAddr = from,
                                fromAddr = addressAsInt
                            )
                            logger(Log.INFO, "$logPrefix sending hotspotresponse to ${from.addressToDotNotation()}", null)
                            route(replyPacket)
                        }
                    }
                }

                // Phase 3: Changed from MmcpNodeAnnouncement to MmcpOriginatorMessage
                mmcpMessage is MmcpOriginatorMessage -> {
                    shouldRoute = originatingMessageManager.onReceiveOriginatingMessage(
                        mmcpMessage = mmcpMessage,
                        datagramPacket = datagramPacket ?: return false,
                        datagramSocket = datagramSocket ?: return false,
                        virtualPacket = virtualPacket,
                    )
                }

                // DEPRECATED: MmcpGatewayAnnouncement class moved to .md (commented out to fix compilation)
                // mmcpMessage is MmcpGatewayAnnouncement -> {
                //     logger(Log.INFO, "$logPrefix received gateway announcement from ${from.addressToDotNotation()}: ${mmcpMessage.gatewayType}", null)
                //     onGatewayAnnouncementReceived(mmcpMessage, from)
                //     shouldRoute = true
                // }

                else -> {
                    // do nothing
                }
            }

            _incomingMmcpMessages.tryEmit(MmcpMessageAndPacketHeader(mmcpMessage, virtualPacket.header))

            return shouldRoute
        }catch(e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // Deduplication cache for broadcast packets
    private val seenBroadcasts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val broadcastTtlMs: Long = 60_000L
    
    private fun computeBroadcastId(packet: VirtualPacket): String {
        return "${packet.header.fromAddr}-${packet.header.fromPort}-${packet.header.payloadSize}"
    }

    override fun route(
        packet: VirtualPacket,
        datagramPacket: DatagramPacket?,
        virtualNodeDatagramSocket: VirtualNodeDatagramSocket?
    ) {
        // Copy packet data immediately to prevent buffer corruption
        // The original datagramPacket buffer is reused by network socket
        val packetDataCopy = packet.data.copyOfRange(0, packet.data.size)
        val packetCopy = VirtualPacket.fromHeaderAndPayloadData(
            header = packet.header,
            data = packetDataCopy,
            payloadOffset = packet.payloadOffset,
            headerAlreadyInData = true
        )

        // Offload ALL processing to connection pool to free IO thread immediately
        connectionExecutor.execute {
            var connection: MeshConnectionPool.Connection? = null
            val startTime = System.currentTimeMillis()
            
            try {
                // Acquire connection from pool with timeout
                connection = meshConnectionPool.acquireConnection(
                    timeoutMs = MeshrabiyaConstants.ROUTE_CONNECTION_ACQUIRE_TIMEOUT_MS
                )
                
                if (connection == null && MeshrabiyaConstants.ROUTE_DROP_ON_POOL_EXHAUSTION) {
                    logger(Log.WARN, 
                        "$logPrefix Dropped packet from ${packetCopy.header.fromAddr.addressToDotNotation()}: " +
                        "connection pool exhausted after ${MeshrabiyaConstants.ROUTE_CONNECTION_ACQUIRE_TIMEOUT_MS}ms",
                        null
                    )
                    return@execute
                }
                
                // Process packet using extracted method
                processRoutePacket(packetCopy, datagramPacket, virtualNodeDatagramSocket)
                
                // Check processing time and log if slow
                val processingTime = System.currentTimeMillis() - startTime
                if (processingTime > MeshrabiyaConstants.ROUTE_PROCESSING_TIMEOUT_MS) {
                    logger(Log.WARN,
                        "$logPrefix Slow packet processing: ${processingTime}ms for packet from " +
                        "${packetCopy.header.fromAddr.addressToDotNotation()}",
                        null
                    )
                }
                
            } catch (e: Exception) {
                logger(Log.ERROR, 
                    "$logPrefix : route : exception routing packet from ${packetCopy.header.fromAddr.addressToDotNotation()}", 
                    e
                )
            } finally {
                // Always release connection back to pool
                connection?.let { meshConnectionPool.releaseConnection(it) }
            }
        }
    }

     /**
     * Internal method containing the actual routing logic
     * Separated from route() to enable connection pooling wrapper
     * 
     * @param packet VirtualPacket to process (with data already copied)
     * @param datagramPacket Original DatagramPacket (may be null)
     * @param virtualNodeDatagramSocket Socket that received packet (may be null)
     */
    private fun processRoutePacket(
        packet: VirtualPacket,
        datagramPacket: DatagramPacket?,
        virtualNodeDatagramSocket: VirtualNodeDatagramSocket?
    ) {
        val fromLastHop = packet.header.lastHopAddr

        if(packet.header.hopCount >= config.maxHops) {
            logger(Log.DEBUG,
                "Drop packet from ${packet.header.fromAddr.addressToDotNotation()} - " +
                        "${packet.header.hopCount} exceeds ${config.maxHops}",
                null)
            return
        }

        // MMCP message handling (unchanged)
        if(packet.header.toPort == 0 && packet.header.fromAddr != addressAsInt){
            logger(Log.DEBUG, "$logPrefix route: Processing MMCP message from ${packet.header.fromAddr.addressToDotNotation()} toPort=${packet.header.toPort}", null)
            if(!onIncomingMmcpMessage(packet, datagramPacket, virtualNodeDatagramSocket)){
                logger(Log.DEBUG, "Drop mmcp packet from ${packet.header.fromAddr}", null)
            }
        }else if(packet.header.toPort == 0){
            logger(Log.DEBUG, "$logPrefix route: Skipping MMCP from self (fromAddr=${packet.header.fromAddr.addressToDotNotation()} myAddr=${addressAsInt.addressToDotNotation()})", null)
        }

        // Ecosystem message handling (UDP broadcast or direct)
        // Route ALL Distributed Storage & Compute messages to MeshEcosystemListener
        val ecosystemPort = MeshrabiyaConstants.getEcosystemGossipPort()
        if(packet.header.toPort == ecosystemPort) {
            val bytes = packet.data.copyOfRange(packet.payloadOffset, packet.payloadOffset + packet.header.payloadSize)
            try {
                val message = MeshEcosystemMessage.fromBytes(bytes)
                val senderId = packet.header.fromAddr
                
                // MeshEcosystemListener is the global listener for all ecosystem messages
                meshEcosystemListener.routeMessage(senderId, message)
            } catch (e: Exception) {
                logger(Log.WARN, "$logPrefix: Failed to deserialize or route MeshEcosystemMessage: ${e.message}", e)
            }
            return
        }

        // --- CONDITIONAL PROXY ROUTING ---
        val currentRoles = emergentRoleManager.getCurrentMeshRoles()
        if (proxyActive && currentRoles.contains(MeshRole.TOR_GATEWAY)) {
            // Route internet traffic via proxy (Tor)
            if (shouldRouteViaProxy(packet)) {
                routeViaProxy(packet)
                logger(Log.INFO, "$logPrefix Routed packet via proxy $proxyHost:$proxyPort", null)
                return
            }
        }

        // --- CLEARNET GATEWAY DISPATCH ---
        if (currentRoles.contains(MeshRole.CLEARNET_GATEWAY) && shouldRouteViaProxy(packet)) {
            if (onClearnetGatewayPacket(packet)) return
        }

        // --- TOR GATEWAY DISPATCH ---
        if (currentRoles.contains(MeshRole.TOR_GATEWAY) && packet.header.gatewayType == VirtualPacketHeader.GATEWAY_TYPE_TOR) {
            if (onTorGatewayPacket(packet)) return
        }

        if(packet.header.toAddr == addressAsInt) {
            val listeningSocket = activeSockets[packet.header.toPort]
            if(listeningSocket != null) {
                listeningSocket.onIncomingPacket(packet)
            }else {
                logger(Log.DEBUG, "$logPrefix Incoming packet received, but no socket listening on: ${packet.header.toPort}")
            }
        }else {
            val toAddr = packet.header.toAddr
            packet.updateLastHopAddrAndIncrementHopCountInData(addressAsInt)
            // Deduplication for broadcast packets moved to MeshEcosystemListener
            if(toAddr == ADDR_BROADCAST) {
                val broadcastId = computeBroadcastId(packet)
                val now = System.currentTimeMillis()
                val prev = seenBroadcasts.putIfAbsent(broadcastId, now)
                if (prev == null) {
                    // PT8: Check TTL before forwarding (prevent infinite loops)
                    if (packet.header.maxHops > 0) {
                        val meshRoles = emergentRoleManager.getCurrentMeshRoles()
                        // UPDATED: Allow MESH_HUB nodes to forward broadcasts
                        if (meshRoles.contains(MeshRole.MESH_ROUTER) || meshRoles.contains(MeshRole.MESH_HUB)) {
                            val roleType = when {
                                meshRoles.contains(MeshRole.MESH_ROUTER) -> "MESH_ROUTER"
                                else -> "MESH_HUB"
                            }
                            logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, forwarding to neighbors (role=$roleType, hops remaining: ${packet.header.maxHops})")
                            originatingMessageManager.neighbors().filter {
                                it.first != fromLastHop && it.first != packet.header.fromAddr
                            }.forEach {
                                logger(Log.VERBOSE, "$logPrefix: Forwarding broadcast to neighbor ${it.first}")
                                it.second.receivedFromSocket.send(
                                    nextHopAddress = it.second.lastHopRealInetAddr,
                                    nextHopPort = it.second.lastHopRealPort,
                                    virtualPacket = packet,
                                )
                            }
                        } else {
                            logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, but node is not MESH_ROUTER or MESH_HUB, not forwarding")
                        }
                    } else {
                        logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId TTL exhausted (maxHops=0), not forwarding")
                    }
                    
                    // MOVED INSIDE DEDUP CHECK: Check if this is a broadcast message packet (MMCP port 0, version 1)
                    // Added: 2026-02-01 for NETWORK_BROADCAST_v2 implementation
                    // Fixed: 2026-02-20 for Issue #1 - prevent sender loopback notification
                    if (packet.header.toPort == 0 && packet.header.payloadSize >= 4) {
                        try {
                            // Peek at payload to check version field
                            val payloadBuffer = java.nio.ByteBuffer.wrap(
                                packet.data,
                                packet.payloadOffset,
                                packet.header.payloadSize
                            )
                            val version = payloadBuffer.getInt()
                            
                            
                            
                        } catch (e: Exception) {
                            logger(Log.WARN, "$logPrefix: Failed to check broadcast message packet version", e)
                        }
                    }
                } else {
                    logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId already seen, ignoring (last seen ${now - prev}ms ago)")
                }
            }else {
                val originatorMessage = originatingMessageManager
                    .findOriginatingMessageFor(packet.header.toAddr)
                if(originatorMessage != null) {
                    originatorMessage.receivedFromSocket.send(
                        nextHopAddress = originatorMessage.lastHopRealInetAddr,
                        nextHopPort = originatorMessage.lastHopRealPort,
                        virtualPacket = packet
                    )
                }else {
                    // Phase 3A: Check if packet requires gateway routing
                    if (packet.header.gatewayType != VirtualPacketHeader.GATEWAY_TYPE_NONE) {
                        logger(Log.DEBUG,
                            "$logPrefix Destination ${packet.header.toAddr.addressToDotNotation()} not on mesh, " +
                            "attempting gateway routing (type=${packet.header.gatewayType})",
                            null
                        )
                        routeViaGateway(packet, null)
                    } else {
                        logger(Log.WARN, "$logPrefix route: Cannot route packet to " +
                                "${packet.header.toAddr.addressToDotNotation()} : no known nexthop")
                    }
                }
            }
        }
    }

    /**
     * Routes packet to internet via mesh gateway.
     * Phase 3A: Gateway Routing Core
     * 
     * Uses gateway type from packet header to select appropriate gateway.
     * Implements failover if primary gateway type unavailable.
     *
     * @param packet Virtual packet with internet-bound destination
     * @param datagramPacket Original datagram (for metadata)
     */
    private fun routeViaGateway(
        packet: VirtualPacket,
        datagramPacket: DatagramPacket?
    ) {
        val gatewayType = packet.header.gatewayType
        
        logger(Log.DEBUG,
            "$logPrefix Routing internet-bound packet via gateway (type=$gatewayType)",
            null
        )
        
        // Get available gateways of requested type
        val gateways = when (gatewayType) {
            VirtualPacketHeader.GATEWAY_TYPE_TOR -> {
                getAvailableTorGateways()
            }
            VirtualPacketHeader.GATEWAY_TYPE_CLEARNET -> {
                getAvailableClearnetGateways()
            }
            else -> {
                logger(Log.ERROR, "$logPrefix Invalid gateway type: $gatewayType", null)
                return
            }
        }
        
        if (gateways.isEmpty()) {
            handleNoGatewayAvailable(packet, gatewayType)
            return
        }
        
        // Select best gateway (closest, lowest load, etc.)
        val selectedGateway = selectBestGateway(gateways, packet)
        
        if (selectedGateway == null) {
            logger(Log.WARN, "$logPrefix No suitable gateway found for type=$gatewayType", null)
            return
        }
        
        // Phase 3C: Track gateway message for return path routing
        originatingMessageManager.trackGatewayMessage(
            fromAddr = packet.header.fromAddr,
            fromPort = packet.header.fromPort,
            toAddr = packet.header.toAddr,
            toPort = packet.header.toPort,
            gatewayType = packet.header.gatewayType,
            gatewayAddr = selectedGateway.nodeAddress
        )
        
        // Forward packet to gateway
        forwardToGateway(packet, selectedGateway)
    }

    /**
     * Gets list of available Tor gateways from mesh topology.
     * 
     * @return List of NodeTopologyInfo for nodes advertising TOR_GATEWAY role
     */
    private fun getAvailableTorGateways(): List<NodeTopologyInfo> {
        return originatingMessageManager.getNodesWithRole(MeshRole.TOR_GATEWAY)
            .filter { !it.isStale(GATEWAY_STALE_TIMEOUT_MS) }
    }

    /**
     * Gets list of available clearnet gateways.
     * 
     * @return List of NodeTopologyInfo for nodes advertising CLEARNET_GATEWAY role
     */
    private fun getAvailableClearnetGateways(): List<NodeTopologyInfo> {
        return originatingMessageManager.getNodesWithRole(MeshRole.CLEARNET_GATEWAY)
            .filter { !it.isStale(GATEWAY_STALE_TIMEOUT_MS) }
    }

    /**
     * Returns integer virtual addresses of all known CLEARNET_GATEWAY peers.
     * Used by MeshLocalSocksProxy to resolve the best gateway to route traffic through.
     */
    fun getAvailableClearnetGatewayAddresses(): List<Int> =
        getAvailableClearnetGateways().map { it.nodeAddress }

    /**
     * Returns integer virtual addresses of all known gateway peers regardless of type.
     * Includes both CLEARNET_GATEWAY and TOR_GATEWAY nodes, deduplicated.
     * Used by MeshLocalSocksProxy when either gateway type is acceptable for routing.
     */
    fun getAvailableGatewayAddresses(): List<Int> =
        (getAvailableClearnetGateways() + getAvailableTorGateways())
            .distinctBy { it.nodeAddress }
            .map { it.nodeAddress }

    /**
     * Selects best gateway from available list.
     * 
     * Selection criteria:
     * 1. Filter out stale gateways
     * 2. Use gateway suitability score (centrality, fitness, latency)
     * 3. Select highest scoring gateway
     *
     * @param gateways List of available gateway nodes
     * @param packet Packet being routed
     * @return Selected gateway NodeTopologyInfo, or null if none suitable
     */
    private fun selectBestGateway(
        gateways: List<NodeTopologyInfo>,
        packet: VirtualPacket
    ): NodeTopologyInfo? {
        if (gateways.isEmpty()) return null
        
        // Determine gateway role from packet header
        val gatewayRole = when (packet.header.gatewayType) {
            VirtualPacketHeader.GATEWAY_TYPE_TOR -> MeshRole.TOR_GATEWAY
            VirtualPacketHeader.GATEWAY_TYPE_CLEARNET -> MeshRole.CLEARNET_GATEWAY
            else -> return null
        }
        
        // Calculate suitability scores and select best
        return gateways
            .map { gateway -> 
                Pair(gateway, gateway.calculateGatewaySuitability(gatewayRole)) 
            }
            .filter { it.second > 0f }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Internal test accessor for selectBestGateway.
     * Allows testing of gateway selection algorithm without VirtualPacket dependency.
     * 
     * @param gateways List of available gateway nodes
     * @param role Gateway role to filter by
     * @return Selected gateway NodeTopologyInfo, or null if none suitable
     */
    internal fun testSelectBestGateway(
        gateways: List<NodeTopologyInfo>,
        role: MeshRole
    ): NodeTopologyInfo? {
        return gateways
            .map { gateway -> 
                Pair(gateway, gateway.calculateGatewaySuitability(role)) 
            }
            .filter { it.second > 0f }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Forwards packet to selected gateway.
     * 
     * Updates packet header (toAddr, hopCount, lastHopAddr) and sends to gateway.
     *
     * @param packet Packet to forward
     * @param gateway Target gateway node info
     */
    private fun forwardToGateway(
        packet: VirtualPacket,
        gateway: NodeTopologyInfo
    ) {
        logger(Log.DEBUG,
            "$logPrefix Forwarding packet to gateway ${gateway.nodeAddress.addressToDotNotation()} " +
                "(hop ${packet.header.hopCount + 1})",
            null
        )
        
        // Create new packet with updated header to route to gateway
        val modifiedHeader = VirtualPacketHeader(
            toAddr = gateway.nodeAddress,  // Route to gateway
            toPort = packet.header.toPort,
            fromAddr = packet.header.fromAddr,
            fromPort = packet.header.fromPort,
            lastHopAddr = addressAsInt,
            hopCount = (packet.header.hopCount + 1).toByte(),
            maxHops = packet.header.maxHops,
            gatewayType = packet.header.gatewayType,  // Preserve gateway type
            payloadSize = packet.header.payloadSize
        )
        
        val forwardedPacket = VirtualPacket.fromHeaderAndPayloadData(
            header = modifiedHeader,
            data = packet.data,
            payloadOffset = packet.payloadOffset
        )
        
        // Find next hop to reach gateway
        val originatorMessage = originatingMessageManager
            .findOriginatingMessageFor(gateway.nodeAddress)
        
        if (originatorMessage != null) {
            originatorMessage.receivedFromSocket.send(
                nextHopAddress = originatorMessage.lastHopRealInetAddr,
                nextHopPort = originatorMessage.lastHopRealPort,
                virtualPacket = forwardedPacket
            )
        } else {
            logger(Log.ERROR,
                "$logPrefix Cannot forward to gateway ${gateway.nodeAddress.addressToDotNotation()}: no route",
                null
            )
        }
    }

    /**
     * Handles case where no gateway is available.
     * 
     * Behavior based on gateway preference (from GatewayPreference enum):
     * - TOR_ONLY: Drop packet (no fallback)
     * - CLEARNET_ONLY: Drop packet (no fallback)
     * - EITHER: Try alternate gateway type
     *
     * @param packet Packet that couldn't be routed
     * @param requestedType Gateway type that was requested
     */
    private fun handleNoGatewayAvailable(
        packet: VirtualPacket,
        requestedType: Byte
    ) {
        logger(Log.WARN, "$logPrefix No gateway available for type=$requestedType", null)
        
        // For EITHER preference, try alternate gateway type
        // Note: Gateway preference is managed by GatewayTypeResolver at packet creation time
        // Here we just attempt fallback for EITHER case
        
        val alternateType = if (requestedType == VirtualPacketHeader.GATEWAY_TYPE_TOR) {
            VirtualPacketHeader.GATEWAY_TYPE_CLEARNET
        } else {
            VirtualPacketHeader.GATEWAY_TYPE_TOR
        }
        
        val alternateGateways = when (alternateType) {
            VirtualPacketHeader.GATEWAY_TYPE_TOR -> getAvailableTorGateways()
            VirtualPacketHeader.GATEWAY_TYPE_CLEARNET -> getAvailableClearnetGateways()
            else -> emptyList()
        }
        
        if (alternateGateways.isNotEmpty()) {
            logger(Log.INFO, "$logPrefix Attempting fallback to gateway type=$alternateType", null)
            
            // Create new packet with alternate gateway type
            val fallbackHeader = VirtualPacketHeader(
                toAddr = packet.header.toAddr,
                toPort = packet.header.toPort,
                fromAddr = packet.header.fromAddr,
                fromPort = packet.header.fromPort,
                lastHopAddr = packet.header.lastHopAddr,
                hopCount = packet.header.hopCount,
                maxHops = packet.header.maxHops,
                gatewayType = alternateType,  // Updated to alternate type
                payloadSize = packet.header.payloadSize
            )
            
            val fallbackPacket = VirtualPacket.fromHeaderAndPayloadData(
                header = fallbackHeader,
                data = packet.data,
                payloadOffset = packet.payloadOffset
            )
            
            routeViaGateway(fallbackPacket, null)
        } else {
            // No fallback available - drop packet
            logger(Log.WARN,
                "$logPrefix Dropping packet: no gateway available (requested=$requestedType)",
                null
            )
        }
    }

    override fun lookupNextHopForChainSocket(address: InetAddress, port: Int): ChainSocketNextHop {
        return originatingMessageManager.lookupNextHopForChainSocket(address, port)
    }

    fun addNewNeighborConnection(
        address: InetAddress,
        port: Int,
        neighborNodeVirtualAddr: Int,
        socket: VirtualNodeDatagramSocket,
    ) {
        logger(Log.INFO,
            "$logPrefix 🆕 addNewNeighborConnection - Starting connection setup for " +
                    "virtualAddr=${neighborNodeVirtualAddr.addressToDotNotation()} " +
                    "realAddr=$address:$port socket.localPort=${socket.localPort}",
            null
        )

        coroutineScope.launch {
            try {
                logger(Log.DEBUG, "$logPrefix 🚀 addNewNeighborConnection - Launching addNeighbor coroutine", null)
                
                originatingMessageManager.addNeighbor(
                    neighborRealInetAddr = address,
                    neighborRealPort = port,
                    socket =  socket,
                )
                
                logger(Log.INFO, "$logPrefix ✅ addNewNeighborConnection - Successfully established neighbor connection to ${neighborNodeVirtualAddr.addressToDotNotation()}", null)
            } catch (e: Exception) {
                logger(Log.ERROR, "$logPrefix ❌ addNewNeighborConnection - FAILED to establish neighbor connection to ${neighborNodeVirtualAddr.addressToDotNotation()}", e)
            }
        }
    }

    fun addPongListener(listener: PongListener) {
        pongListeners += listener
    }

    fun removePongListener(listener: PongListener) {
        pongListeners -= listener
    }

    open suspend fun setWifiHotspotEnabled(
        enabled: Boolean,
        preferredBand: ConnectBand = ConnectBand.BAND_2GHZ,
        hotspotType: HotspotType = HotspotType.AUTO,
        preferredPassphrase: String? = null,
    ): LocalHotspotResponse? {
        return if(enabled){
             meshrabiyaWifiManager.requestHotspot(
                requestMessageId = nextMmcpMessageId(),
                request = LocalHotspotRequest(
                    preferredBand = preferredBand,
                    preferredType = hotspotType,
                    preferredPassphrase = preferredPassphrase,
                )
            )
        }else {
            meshrabiyaWifiManager.deactivateHotspot()
            LocalHotspotResponse(
                responseToMessageId = 0,
                config = null,
                errorCode = 0,
                redirectAddr = 0,
            )
        }
    }

    fun sendMessage(message: MmcpMessage) {
        originatingMessageManager.sendMessage(message)
    }

    /**
     * Send a direct ecosystem message to a specific node.
     * Constructs VirtualPacket with ecosystem port and routes it.
     * 
     * @param targetAddress Destination node address
     * @param messageBytes Serialized message bytes
     * @param toPort Destination port (defaults to ecosystem gossip port)
     */
    fun sendEcosystemMessage(
        targetAddress: Int,
        messageBytes: ByteArray,
        toPort: Int = MeshrabiyaConstants.getEcosystemGossipPort()
    ) {
        val packetData = ByteArray(VirtualPacketHeader.HEADER_SIZE + messageBytes.size)
        val header = VirtualPacketHeader(
            toAddr = targetAddress,
            toPort = toPort,
            fromAddr = addressAsInt,
            fromPort = toPort,
            lastHopAddr = addressAsInt,
            hopCount = 0,
            maxHops = 10,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE, //V3: Mesh-local message
            payloadSize = messageBytes.size
        )
        System.arraycopy(messageBytes, 0, packetData, VirtualPacketHeader.HEADER_SIZE, messageBytes.size)
        val packet = VirtualPacket.fromHeaderAndPayloadData(
            header = header,
            data = packetData,
            payloadOffset = VirtualPacketHeader.HEADER_SIZE
        )
        route(packet, null, null)
    }

    // DEPRECATED: MmcpGatewayAnnouncement class moved to .md (commented out to fix compilation)
    // protected open fun onGatewayAnnouncementReceived(announcement: MmcpGatewayAnnouncement, fromNodeAddr: Int) {
    //     logger(Log.INFO, "$logPrefix Gateway ${announcement.gatewayType} available from ${fromNodeAddr.addressToDotNotation()}")
    //     try {
    //         if (announcement.isActive && announcement.capacity.downloadMbps > 0) {
    //             logger(Log.DEBUG, "$logPrefix Valid gateway: capacity=${announcement.capacity.downloadMbps}Mbps, latency=${announcement.latency.averageMs}ms")
    //         }
    //     } catch (e: Exception) {
    //         logger(Log.WARN, "$logPrefix Error processing gateway announcement: ${e.message}")
    //     }
    // }

    fun getCurrentState(): LocalNodeState {
        return currentNodeState
    }

    fun neighbors() = originatingMessageManager.neighbors()

    override fun close() {
        datagramSocket.close(closeSocket = true)
        chainSocketServer.close(closeSocket = true)
        coroutineScope.cancel(message = "VirtualNode closed")
        connectionExecutor.shutdown()
        scheduledExecutor.shutdown()
    }

    protected fun safeLog(
        level: LogLevel,
        category: String,
        message: String,
        metadata: Map<String, String?> = emptyMap(),
        throwable: Throwable? = null
    ) {
        try {
            val betaLogger = (logger as? BetaTestLogger)
            if (betaLogger != null) {
                val nonNullMetadata: Map<String, String> = if (metadata.isEmpty()) {
                    emptyMap()
                } else {
                    metadata.mapValues { it.value ?: "" }
                }
                betaLogger.log(level, category, message, nonNullMetadata, throwable)
            } else {
                val formattedMessage = if (metadata.isNotEmpty()) {
                    "$message [${metadata.map { "${it.key}=${it.value}" }.joinToString(", ")}]"
                } else {
                    message
                }
                when (level) {
                    LogLevel.ERROR -> logger(Log.ERROR, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.WARN -> logger(Log.WARN, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.INFO -> logger(Log.INFO, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.DEBUG -> logger(Log.DEBUG, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.DETAILED -> logger(Log.DEBUG, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.FULL -> logger(Log.VERBOSE, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.BASIC -> logger(Log.INFO, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.DISABLED -> { }
                }
            }
        } catch (e: Exception) {
            Log.e("VirtualNode", "Logging failed: ${e.message}, original message: $message", e)
        }
    }

    // --- Helper: Should route via proxy ---
    protected open fun shouldRouteViaProxy(packet: VirtualPacket): Boolean {
        val destInetAddress = getInetAddressFor(packet.header.toAddr)
        return !destInetAddress.prefixMatches(networkPrefixLength, address)
    }

    /**
     * Called when this node is a CLEARNET_GATEWAY and a non-mesh packet arrives.
     * Override in AndroidVirtualNode to forward via the internet WiFi network.
     * @return true if the packet was handled (caller should return), false to fall through.
     */
    protected open fun onClearnetGatewayPacket(packet: VirtualPacket): Boolean = false

    protected open fun onTorGatewayPacket(packet: VirtualPacket): Boolean = false

    fun broadcastGatewayDown() {
        val mmcpPayload = byteArrayOf(MmcpMessage.WHAT_GATEWAY_DOWN)
        val header = VirtualPacketHeader(
            toAddr = ADDR_BROADCAST,
            toPort = 0,
            fromAddr = addressAsInt,
            fromPort = 0,
            lastHopAddr = addressAsInt,
            hopCount = 0,
            maxHops = 3,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE,
            payloadSize = mmcpPayload.size,
        )
        val pkt = VirtualPacket.fromHeaderAndPayloadData(header, mmcpPayload, 0)
        originatingMessageManager.neighbors().forEach { (_, neighbor) ->
            neighbor.receivedFromSocket.send(
                nextHopAddress = neighbor.lastHopRealInetAddr,
                nextHopPort = neighbor.lastHopRealPort,
                virtualPacket = pkt,
            )
        }
        logger(Log.INFO, "$logPrefix broadcastGatewayDown: sent to ${originatingMessageManager.neighbors().size} neighbors")
    }

    // === Gateway Routing Methods (Phase 4) ===
    
    /**
     * Check if this node is acting as a gateway of given type
     */
    fun isGatewayNode(gatewayType: MeshRole): Boolean {
        return emergentRoleManager.currentMeshRoles.value.contains(gatewayType)
    }

    /**
     * Route packet through gateway based on destination analysis
     * CLIENT NODE: Select gateway from topology, route to gateway
     * GATEWAY NODE: Route through proxy
     */
    fun routeThroughGateway(packet: VirtualPacket): Boolean {
        // Determine gateway type needed based on destination
        val gatewayType = determineGatewayType(packet)
        
        return if (gatewayType != null) {
            gatewayRouter.routeToGateway(packet, gatewayType)
        } else {
            // No gateway needed, route directly (route() returns Unit, so wrap in true)
            route(packet)
            true
        }
    }

    /**
     * Determine which gateway type is needed for this packet
     * @return Gateway type (TOR/CLEARNET/I2P) or null for direct routing
     */
    private fun determineGatewayType(packet: VirtualPacket): MeshRole? {
        // TODO: Implement packet inspection logic
        // Phase 1: Explicit tagging (application layer specifies gateway)
        // Phase 2: Destination-based (.onion → TOR, .i2p → I2P, else CLEARNET)
        // Phase 3: Port-based (443 → CLEARNET, 9150 → TOR, 7657 → I2P)
        
        // For now, return null (no gateway routing until classification implemented)
        return null
    }

    /**
     * Route packet through configured proxy (Tor/etc)
     * GATEWAY NODE behavior - called by GatewayRouter
     * Enhanced to return Boolean for success/failure
     */
    fun routeViaProxy(packet: VirtualPacket): Boolean {
        val host = proxyHost ?: return false
        val port = proxyPort ?: return false
        
        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
            val socket = Socket(proxy)
            socket.getOutputStream().write(packet.data)
            socket.close()
            return true
        } catch (e: Exception) {
            logger(Log.ERROR, "$logPrefix Failed to route via proxy: ${e.message}", e)
            return false
        }
    }

    companion object {
        /**
         * Timeout threshold for gateway staleness check.
         * Gateways not seen within this period are considered stale.
         * Phase 3A: Gateway Routing Core
         */
        const val GATEWAY_STALE_TIMEOUT_MS = 30_000L  // 30 seconds
    }

    // Removed explicit getter functions - Kotlin auto-generates them from protected val properties
    // This eliminates "Platform declaration clash" errors from duplicate JVM signatures
}

## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/ClearnetGatewayForwarder.kt
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
}


## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/OriginatingMessageManager.kt
package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.ext.addressToByteArray

import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpOriginatorMessage  // NEW: Import official message type
// TODO: Uncomment when MmcpNodeAnnouncement is available
// import com.ustadmobile.meshrabiya.mmcp.MmcpNodeAnnouncement (DEPRECATED)
// TODO: Uncomment when MmcpMessageFactory is available
// import com.ustadmobile.meshrabiya.mmcp.MmcpMessageFactory (DEPRECATED)
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketNextHop
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotStatus
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import java.util.concurrent.atomic.AtomicInteger
import com.ustadmobile.meshrabiya.vnet.HasNodeState
import com.ustadmobile.meshrabiya.vnet.VirtualNode

class OriginatingMessageManager(
    private val localNodeInetAddr: InetAddress,
    private val logger: MNetLogger,
    private val scheduledExecutor: ScheduledExecutorService,
    private val nextMmcpMessageId: () -> Int,
    private val getWifiState: () -> MeshrabiyaWifiState,
    
    // === NEW: Callbacks to break circular dependency ===
    private val getCentralityScore: (() -> Float)? = null,
    private val getMeshRoles: (() -> Set<MeshRole>)? = null,
    private val getFitnessScore: (() -> Float)? = null,  // Changed from () -> Int
    private val getInternetSignalInfo: (() -> Pair<Int, Int>)? = null,

    // === EXISTING PARAMS ===
    private val pingTimeout: Int = 15_000,
    private val originatingMessageNodeLostThreshold: Int = 10000,
    lostNodeCheckInterval: Int = 1_000,
    private val betaLogger: BetaTestLogger? = null
) {

    private val logPrefix ="[OriginatingMessageManager for ${localNodeInetAddr}] "

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val localNodeAddress = localNodeInetAddr.requireAddressAsInt()

    /**
     * The currently known latest originator messages that can be used to route traffic.
     */
    private val originatorMessages: MutableMap<Int, VirtualNode.LastOriginatorMessage> = ConcurrentHashMap()

    private val _state = MutableStateFlow(OriginatingMessageState())
    val state: StateFlow<OriginatingMessageState> = _state

    private val receivedMessages: Flow<VirtualNode.LastOriginatorMessage> = MutableSharedFlow(
        replay = 1 , extraBufferCapacity = 0, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    data class PendingPing(
        val ping: MmcpPing,
        val toVirtualAddr: Int,
        val timesent: Long
    )

    data class PingTime(
        val nodeVirtualAddr: Int,
        val pingTime: Short,
        val timeReceived: Long,
    )

    private val pendingPings = CopyOnWriteArrayList<PendingPing>()

    private val neighborPingTimes: MutableMap<Int, PingTime> = ConcurrentHashMap()

    // Add a map to store neighbor fitness and role info
    private val neighborFitnessInfo: MutableMap<Int, Pair<Int, Byte>> = ConcurrentHashMap()

    // Track multi-hop neighbor info
    private val neighborCentralityInfo: MutableMap<Int, Float> = ConcurrentHashMap()

    private val messageCounter = AtomicInteger(0)

    // === PHASE 3C: GATEWAY MESSAGE TRACKING ===
    /**
     * Tracks messages sent via gateways for return path routing and statistics.
     * Key: "fromAddr:fromPort", Value: GatewayMessage
     */
    private val gatewayMessages: MutableMap<String, GatewayMessage> = ConcurrentHashMap()

    // === TOPOLOGY MAP WITH FULL NODE INFO ===
    // Enhanced to store complete NodeTopologyInfo (roles, metrics) instead of just neighbors
    private val _topologyMapInfo: MutableMap<Int, NodeTopologyInfo> = mutableMapOf()
    
    // Expose as Flow for observers (e.g., GatewaySelector)
    private val _topologyMapFlow = MutableStateFlow<Map<Int, NodeTopologyInfo>>(emptyMap())
    val topologyMapFlow: StateFlow<Map<Int, NodeTopologyInfo>> = _topologyMapFlow.asStateFlow()
    
    /**
     * Get full topology map with NodeTopologyInfo (roles, metrics, neighbors)
     * Used by GatewaySelector for intelligent gateway selection
     */
    fun getTopologyMapInfo(): Map<Int, NodeTopologyInfo> = _topologyMapInfo
    
    /**
     * Get nodes with specific role (e.g., TOR_GATEWAY, CLEARNET_GATEWAY)
     * @param role MeshRole to filter by
     * @return List of NodeTopologyInfo for nodes with the specified role
     */
    fun getNodesWithRole(role: MeshRole): List<NodeTopologyInfo> {
        return _topologyMapInfo.filter { it.value.hasRole(role) }.values.toList()
    }
    
    /**
     * Get all gateway nodes (TOR, CLEARNET, I2P)
     * @return List of NodeTopologyInfo for gateway nodes
     */
    fun getGatewayNodes(): List<NodeTopologyInfo> {
        return _topologyMapInfo.filter { it.value.isGatewayNode() }.values.toList()
    }
    
    /**
     * Backward compatibility: Convert NodeTopologyInfo to old Map<Int, Set<Int>> format
     * Used by EmergentRoleManager for centrality calculations
     */
    @Deprecated("Use getTopologyMapInfo() for full node information")
    fun getTopologyMap(): Map<Int, Set<Int>> {
        return _topologyMapInfo.mapValues { it.value.neighbors }
    }

    private fun logBeta(level: LogLevel, message: String, throwable: Throwable? = null) {
        betaLogger?.log(level, message, throwable)
    }

    private val sendOriginatingMessageRunnable = Runnable {
        try {
            val originatingMessage = makeOriginatingMessage()  // Now includes callbacks
            
            logBeta(LogLevel.DEBUG, "Sending originating message: " +
                "messageId=${originatingMessage.messageId}, " +
                "neighbors=${originatingMessage.neighbors.size}, " +
                "centrality=${originatingMessage.centralityScore}")

            logger(
                priority = Log.VERBOSE,
                message = { "$logPrefix sending originating message messageId=${originatingMessage.messageId} " +
                    "sentTime=${originatingMessage.sentTime} neighbors=${originatingMessage.neighbors.size}" }
            )

            val packet = originatingMessage.toVirtualPacket(
                toAddr = ADDR_BROADCAST,
                fromAddr = localNodeAddress,
                lastHopAddr = localNodeAddress,
                hopCount = 1,
            )

            val neighbors = originatorMessages.filter {
                it.value.hopCount == 1.toByte()
            }

            logger(Log.INFO, "$logPrefix 📡 Broadcasting originating message to ${neighbors.size} direct neighbors", null)

            neighbors.forEach {
                val lastOriginatorMessage = it.value
                try {
                    logger(Log.INFO, "$logPrefix   → Sending to neighbor ${it.key.addressToDotNotation()} at ${lastOriginatorMessage.lastHopRealInetAddr}:${lastOriginatorMessage.lastHopRealPort}", null)
                    
                    lastOriginatorMessage.receivedFromSocket.send(
                        nextHopAddress = lastOriginatorMessage.lastHopRealInetAddr,
                        nextHopPort = lastOriginatorMessage.lastHopRealPort,
                        virtualPacket = packet,
                    )
                    
                    logger(Log.DEBUG, "$logPrefix   ✅ Sent successfully to ${it.key.addressToDotNotation()}", null)
                }catch(e: Exception) {
                    logger(Log.WARN, "$logPrefix : sendOriginatingMessagesRunnable: exception sending to " +
                            "${it.key.addressToDotNotation()} through ${it.value.lastHopRealInetAddr}:${it.value.lastHopRealPort}",
                        e)
                }
            }

            //check if we have an active station connection but have lost the originating message from
            // the hotspot node e.g. node slowed down for a while, app restart, etc.
            //Send it an originating message even if we haven't receive one from it lately
            //This could help restore a connection that died temporarily.
            val stationState = getWifiState().wifiStationState
            val stationNeighborInetAddr = stationState.config?.linkLocalAddr
            val stationDatagramPort = stationState.config?.port
            if(stationNeighborInetAddr != null &&
                !neighbors.any { it.value.lastHopRealInetAddr == stationNeighborInetAddr }
                && stationDatagramPort != null
                && stationState.stationBoundDatagramSocket != null
            ) {
                logger(Log.WARN, "$logPrefix : sendOriginatingMessagesRunnable: have not received " +
                        " originating message from hotspot we are connected to as station. Retrying")
                try {
                    stationState.stationBoundDatagramSocket.send(
                        nextHopAddress = stationNeighborInetAddr,
                        nextHopPort = stationDatagramPort,
                        virtualPacket = packet,
                    )
                }catch(e: Exception) {
                    logger(Log.ERROR, "$logPrefix : sendOriginatingMessagesRunnable: could not " +
                            "send originating message to group owner", e)
                }
            }else if(stationNeighborInetAddr != null && stationState.stationBoundDatagramSocket == null) {
                logger(Log.WARN, "$logPrefix : sendOriginatingMessagesRunnable : could not send " +
                        "originating message to group owner socket not set on state")
            }
            
            // Note: Removed separate hotspot socket broadcasting - the main VirtualNodeDatagramSocket
            // already receives packets on all interfaces including the hotspot interface (ap0).
            // Normal broadcast mechanism handles hotspot scenarios automatically.
        } catch (e: Exception) {
            logBeta(LogLevel.ERROR, "Error sending originating message", e)
            logger(Log.ERROR, { "$logPrefix : sendOriginatingMessageRunnable : exception sending originating message" }, e)
        }
    }

    private val pingNeighborsRunnable = Runnable {
        try {
            val neighbors = neighbors()
            logBeta(LogLevel.DEBUG, "Pinging neighbors: ${neighbors.map { it.first.addressToDotNotation() }.joinToString()}")
            neighbors.forEach {
                val neighborVirtualAddr = it.first
                val lastOrigininatorMessage = it.second
                val pingMessage = MmcpPing(messageId = nextMmcpMessageId())
                pendingPings.add(PendingPing(pingMessage, neighborVirtualAddr, System.currentTimeMillis()))
                logger(
                    priority = Log.VERBOSE,
                    message = { "$logPrefix pingNeighborsRunnable: send ping to ${neighborVirtualAddr.addressToDotNotation()}" }
                )

                it.second.receivedFromSocket.send(
                    nextHopAddress = lastOrigininatorMessage.lastHopRealInetAddr,
                    nextHopPort = lastOrigininatorMessage.lastHopRealPort,
                    virtualPacket = pingMessage.toVirtualPacket(
                        toAddr = neighborVirtualAddr,
                        fromAddr = localNodeAddress,
                        lastHopAddr = localNodeAddress,
                        hopCount = 1,
                    )
                )
            }

            //Remove expired pings
            val pingTimeoutThreshold = System.currentTimeMillis() - pingTimeout
            pendingPings.removeIf { it.timesent < pingTimeoutThreshold }

            logBeta(LogLevel.DEBUG, "Pinging neighbors: ${neighborFitnessInfo.keys.joinToString { it.addressToDotNotation() }}")
        } catch (e: Exception) {
            logBeta(LogLevel.ERROR, "Error pinging neighbors", e)
            logger(Log.ERROR, { "$logPrefix : pingNeighborsRunnable : exception pinging neighbors" }, e)
        }
    }

    private val checkLostNodesRunnable = Runnable {
        try {
            val timeNow = System.currentTimeMillis()
            val nodesLost = originatorMessages.entries.filter {
                (timeNow - it.value.timeReceived) > originatingMessageNodeLostThreshold
            }
            logBeta(LogLevel.DEBUG, "Checking lost nodes: ${nodesLost.map { it.key.addressToDotNotation() }.joinToString()}")
            nodesLost.forEach {
                logBeta(LogLevel.INFO, "Lost node: ${it.key.addressToDotNotation()} - no contact for ${timeNow - it.value.timeReceived}ms")
                logger(Log.DEBUG, {"$logPrefix : checkLostNodesRunnable: " +
                        "Lost ${it.key.addressToDotNotation()} - no contact for ${timeNow - it.value.timeReceived}ms"})
                originatorMessages.remove(it.key)
            }

            val peerCountAfter = originatorMessages.size

            // Grace period: when all peers disappear simultaneously (e.g. OS screenshot suppression),
            // do NOT immediately emit 0-peer state. Wait lostNodeGracePeriodMs before downgrading.
            if (nodesLost.isNotEmpty() && peerCountAfter == 0 && allPeersLostAtMs == 0L) {
                allPeersLostAtMs = timeNow
                logger(Log.DEBUG, { "$logPrefix : checkLostNodesRunnable: all peers lost – grace period started" })
            }

            val gracePeriodExpired = allPeersLostAtMs == 0L ||
                    (timeNow - allPeersLostAtMs) >= lostNodeGracePeriodMs

            if (peerCountAfter > 0) {
                allPeersLostAtMs = 0L   // reset when peers come back
                _state.value = OriginatingMessageState(
                    pendingMessages = originatorMessages.mapValues { it.value.originatorMessage }
                )
            } else if (gracePeriodExpired) {
                _state.value = OriginatingMessageState(
                    pendingMessages = originatorMessages.mapValues { it.value.originatorMessage }
                )
            }
            // else: peers gone but grace period active — suppress state emission
        } catch (e: Exception) {
            logBeta(LogLevel.ERROR, "Error checking lost nodes", e)
            logger(Log.ERROR, { "$logPrefix : checkLostNodesRunnable : exception checking lost nodes" }, e)
        }
    }

    private val sendOriginatorMessagesFuture = scheduledExecutor.scheduleWithFixedDelay(
        sendOriginatingMessageRunnable, 1000, 3000, TimeUnit.MILLISECONDS
    )

    private val pingNeighborsFuture = scheduledExecutor.scheduleWithFixedDelay(
        pingNeighborsRunnable, 1000, 10000, TimeUnit.MILLISECONDS
    )

    private val checkLostNodesFuture = scheduledExecutor.scheduleWithFixedDelay(
        checkLostNodesRunnable, lostNodeCheckInterval.toLong(), lostNodeCheckInterval.toLong(), TimeUnit.MILLISECONDS
    )

    @Volatile
    private var allPeersLostAtMs = 0L
    private val lostNodeGracePeriodMs = 15_000L      // 15 s — covers OS screenshot suppression

    @Volatile
    private var closed = false


    private fun makeOriginatingMessage(): MmcpOriginatorMessage {
        // Get current direct neighbor addresses for topology building
        val neighborAddrs = originatorMessages
            .filter { it.value.hopCount == 1.toByte() }
            .keys
            .toList()
        
        // Use callbacks instead of direct EmergentRoleManager access
        val centralityScore = getCentralityScore?.invoke() ?: 0f
        val meshRoles = getMeshRoles?.invoke() ?: setOf(MeshRole.MESH_PARTICIPANT)
        val fitnessScore = getFitnessScore?.invoke() ?: 0f
        
        return MmcpOriginatorMessage(
            messageId = nextMmcpMessageId(),
            sentTime = System.currentTimeMillis(),
            pingTimeSum = 0,  // Will be incremented as message propagates
            connectConfig = getWifiState().connectConfig,
            neighbors = neighborAddrs,  // NEW: For topology building
            centralityScore = centralityScore,  // NEW: From callback
            fitnessScore = fitnessScore,  // NEW: From callback
            meshRoles = meshRoles,  // NEW: From callback
            internetSignalStrengthDbm = getInternetSignalInfo?.invoke()?.first ?: 0,
            internetLinkSpeedMbps = getInternetSignalInfo?.invoke()?.second ?: 0,
        )
    }


    private fun assertNotClosed() {
        if(closed)
            throw IllegalStateException("$logPrefix is closed!")
    }


    fun onReceiveOriginatingMessage(
        mmcpMessage: MmcpOriginatorMessage,  // Changed type
        datagramPacket: DatagramPacket,
        datagramSocket: VirtualNodeDatagramSocket,
        virtualPacket: VirtualPacket,
    ): Boolean {
        assertNotClosed()
        
        logBeta(LogLevel.DEBUG, "Received originating message from " +
            "${virtualPacket.header.fromAddr.addressToDotNotation()}: " +
            "neighbors=${mmcpMessage.neighbors.size}, " +
            "centrality=${mmcpMessage.centralityScore}")

        //Dont keep originator messages in our own table for this node
        logger(
            Log.VERBOSE,
            message= {
                "$logPrefix received originating message from " +
                        "${virtualPacket.header.fromAddr.addressToDotNotation()} via " +
                        virtualPacket.header.lastHopAddr.addressToDotNotation()
            }
        )

        val connectionPingTime = neighborPingTimes[virtualPacket.header.lastHopAddr]?.pingTime ?: 0.toLong()

        val currentOriginatorMessage = originatorMessages[virtualPacket.header.fromAddr]

        // === OFFICIAL FRESHNESS CHECK (preserved from canonical design) ===
        val currentlyKnownSentTime = (currentOriginatorMessage?.originatorMessage?.sentTime ?: 0)
        val currentlyKnownHopCount = (currentOriginatorMessage?.hopCount ?: Byte.MAX_VALUE)
        val receivedFromRealInetAddr = datagramPacket.address
        val receivedFromSocket = datagramSocket
        val isMoreRecentOrBetter = mmcpMessage.sentTime > currentlyKnownSentTime
                || mmcpMessage.sentTime == currentlyKnownSentTime && virtualPacket.header.hopCount < currentlyKnownHopCount
        val isNewNeighbor = virtualPacket.header.hopCount == 1.toByte() &&
                !originatorMessages.containsKey(virtualPacket.header.fromAddr)

        logger(
            Log.VERBOSE,
            message = {
                "$logPrefix received originating message from " +
                        "${virtualPacket.header.fromAddr.addressToDotNotation()} via ${virtualPacket.header.lastHopAddr.addressToDotNotation()}" +
                        " messageId=${mmcpMessage.messageId} " +
                        " hopCount=${virtualPacket.header.hopCount} sentTime=${mmcpMessage.sentTime} " +
                        " Currently known: sentTime=$currentlyKnownSentTime  hop count = $currentlyKnownHopCount " +
                        "isMoreRecentOrBetter=$isMoreRecentOrBetter "
            }
        )

        // === UPDATE ROUTING TABLE (official logic) ===
        if(currentOriginatorMessage == null || isMoreRecentOrBetter) {
            originatorMessages[virtualPacket.header.fromAddr] = VirtualNode.LastOriginatorMessage(
                originatorMessage = mmcpMessage.copyWithPingTimeIncrement(connectionPingTime.toLong()),
                timeReceived = System.currentTimeMillis(),
                lastHopAddr = virtualPacket.header.lastHopAddr,
                hopCount = virtualPacket.header.hopCount,
                lastHopRealInetAddr = receivedFromRealInetAddr,
                receivedFromSocket = receivedFromSocket,
                lastHopRealPort = datagramPacket.port,
                neighborAddr =  InetAddress.getByAddress(virtualPacket.header.fromAddr.addressToByteArray())
            )
            
            logger(Log.INFO, "$logPrefix 📥 RECEIVED originating message from ${virtualPacket.header.fromAddr.addressToDotNotation()} via ${datagramPacket.address}:${datagramPacket.port} hopCount=${virtualPacket.header.hopCount}", null)
            
            if (virtualPacket.header.hopCount == 1.toByte()) {
                logger(Log.INFO, "$logPrefix 🤝 DIRECT NEIGHBOR detected: ${virtualPacket.header.fromAddr.addressToDotNotation()} (isNew=$isNewNeighbor)", null)
            } else {
                logger(Log.DEBUG, "$logPrefix 🔀 Multi-hop node: ${virtualPacket.header.fromAddr.addressToDotNotation()} (${virtualPacket.header.hopCount} hops away)", null)
            }
            
            // === ENHANCED: BUILD TOPOLOGY MAP WITH ROLES ===
            val nodeInfo = NodeTopologyInfo(
                nodeAddress = virtualPacket.header.fromAddr,
                neighbors = mmcpMessage.neighbors.toSet(),
                meshRoles = mmcpMessage.meshRoles,  // Store ALL roles (gateway + intelligence)
                centralityScore = mmcpMessage.centralityScore,
                fitnessScore = mmcpMessage.fitnessScore,
                lastSeen = System.currentTimeMillis(),
                pingTime = mmcpMessage.pingTimeSum,
                internetSignalStrengthDbm = mmcpMessage.internetSignalStrengthDbm,
                internetLinkSpeedMbps = mmcpMessage.internetLinkSpeedMbps,
            )
            
            _topologyMapInfo[virtualPacket.header.fromAddr] = nodeInfo
            _topologyMapFlow.value = _topologyMapInfo.toMap()  // Emit update for observers
            
            // Log gateway role changes (TOR/CLEARNET/I2P only)
            val gatewayRoles = nodeInfo.meshRoles.filter { 
                it in setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
            }
            if (gatewayRoles.isNotEmpty()) {
                logger(
                    Log.INFO, 
                    message = { "$logPrefix Node ${virtualPacket.header.fromAddr.addressToDotNotation()} offers gateways: $gatewayRoles " +
                        "(fitness=${nodeInfo.fitnessScore}, centrality=${nodeInfo.centralityScore})" }
                )
            }
            
            // Log intelligence roles (STORAGE/COMPUTE) at DEBUG level
            val intelligenceRoles = nodeInfo.meshRoles.filter {
                it in setOf(MeshRole.STORAGE_NODE, MeshRole.COMPUTE_NODE)
            }
            if (intelligenceRoles.isNotEmpty()) {
                logger(
                    Log.DEBUG,
                    message = { "$logPrefix Node ${virtualPacket.header.fromAddr.addressToDotNotation()} offers intelligence: $intelligenceRoles " +
                        "(fitness=${nodeInfo.fitnessScore}, centrality=${nodeInfo.centralityScore})" }
                )
            }
            
            logger(
                Log.VERBOSE,
                message = { "$logPrefix updated topology: node ${virtualPacket.header.fromAddr.addressToDotNotation()} " +
                    "has ${mmcpMessage.neighbors.size} neighbors, ${nodeInfo.meshRoles.size} roles" }
            )
            
            // === NEW: STORE NEIGHBOR METADATA ===
            if (virtualPacket.header.hopCount == 1.toByte()) {
                neighborFitnessInfo[virtualPacket.header.fromAddr] = Pair(
                    (mmcpMessage.fitnessScore * 100).toInt(),
                    0  // Reserved
                )
                neighborCentralityInfo[virtualPacket.header.fromAddr] = mmcpMessage.centralityScore
            }
            
            logger(
                Log.VERBOSE,
                message = {
                    "$logPrefix update originator messages: " +
                            "currently known nodes = ${originatorMessages.keys.joinToString { it.addressToDotNotation() }}; " +
                            "neighbor fitness/role: ${neighborFitnessInfo.map { (k, v) -> k.addressToDotNotation() + ":" + v.first + ",role=" + v.second }.joinToString()}" +
                            ", neighbor count: ${neighborFitnessInfo.size}" +
                            ", multi-hop neighbor centrality: ${neighborCentralityInfo}"
                }
            )

            // === EMIT STATE UPDATE ===
            _state.value = OriginatingMessageState(
                pendingMessages = originatorMessages.mapValues { it.value.originatorMessage }
            )
            
            logBeta(LogLevel.INFO, "Updated originator messages: known nodes = ${originatorMessages.keys.joinToString { it.addressToDotNotation() }}, neighbor fitness/role: ${neighborFitnessInfo.map { (k, v) -> k.addressToDotNotation() + ":" + v.first + ",role=" + v.second }.joinToString()}, neighbor count: ${neighborFitnessInfo.size}, multi-hop neighbor centrality: ${neighborCentralityInfo}")
        }

        // === TRIGGER IMMEDIATE REPLY FOR NEW NEIGHBORS (official behavior) ===
        if(isNewNeighbor) {
            scheduledExecutor.submit(sendOriginatingMessageRunnable)
        }

        return isMoreRecentOrBetter
    }

    fun onPongReceived(
        fromVirtualAddr: Int,
        pong: MmcpPong,
    ) {
        val pendingPingPredicate : (PendingPing) -> Boolean = {
            it.ping.messageId == pong.replyToMessageId && it.toVirtualAddr == fromVirtualAddr
        }

        val pendingPing = pendingPings.firstOrNull(pendingPingPredicate)

        if(pendingPing == null){
            logBeta(LogLevel.WARN, "Pong from ${fromVirtualAddr.addressToDotNotation()} does not match any known sent ping")
            return
        }

        val timeNow = System.currentTimeMillis()

        //Sometimes unit tests will run very quickly, and test may fail if ping time is 0
        val pingTime = maxOf((timeNow - pendingPing.timesent).toLong(), 1)
        logBeta(LogLevel.DEBUG, "Received ping from ${fromVirtualAddr.addressToDotNotation()} pingTime=$pingTime")

        neighborPingTimes[fromVirtualAddr] = PingTime(
            nodeVirtualAddr = fromVirtualAddr,
            pingTime = pingTime.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort(),
            timeReceived = timeNow,
        )

        pendingPings.removeIf(pendingPingPredicate)
    }

    fun findOriginatingMessageFor(addr: Int): VirtualNode.LastOriginatorMessage? {
        return originatorMessages[addr]
    }


    fun lookupNextHopForChainSocket(address: InetAddress, port: Int): ChainSocketNextHop {
        val addressInt = address.requireAddressAsInt()

        val originatorMessage = originatorMessages[addressInt]

        return when {
            //Destination address is this node
            addressInt == localNodeAddress -> {
                ChainSocketNextHop(InetAddress.getLoopbackAddress(), port, true, null)
            }

            //Destination is a direct neighbor (final destination) - connect to the actual socket itself
            originatorMessage != null && originatorMessage.hopCount == 1.toByte() -> {
                ChainSocketNextHop(originatorMessage.lastHopRealInetAddr, port, true,
                        originatorMessage.receivedFromSocket.boundNetwork)
            }

            //Destination is not a direct neighbor, but we have a route there
            originatorMessage != null -> {
                ChainSocketNextHop(originatorMessage.lastHopRealInetAddr,
                    originatorMessage.lastHopRealPort, false,
                    originatorMessage.receivedFromSocket.boundNetwork)
            }

            //No route available to reach the given address
            else -> {
                logger(Log.ERROR, "$logPrefix : No route to virtual host: $address")
                throw NoRouteToHostException("No route to virtual host $address")
            }
        }
    }


    /**
     * Run the process to add a new neighbor (e.g. after a Wifi station connection is established).
     *
     * This will send originating messages to the neighbor node and wait until we receive an
     * originating message reply (up until a timeout)
     *
     * @param neighborRealInetAddr the InetAddress of the neighbor (e.g. real IP address)
     * @param neighborRealPort The port on which the neighbor is running VirtualNodeDatagramSocket
     * @param socket our VirtualNodeDatagramSocket through which we will attempt to communicate with
     *        the new neighbor - this is often the socket bound to a Network object after a new
     *        wifi connection is established
     * @param timeout the timeout (in ms) for the new connection to be established. If the timeout
     *        is exceeded an exception will be thrown
     * @param sendInterval the interval period for sending out originating messages to the new neighbor
     */
    suspend fun addNeighbor(
        neighborRealInetAddr: InetAddress,
        neighborRealPort: Int,
        socket: VirtualNodeDatagramSocket,
        timeout: Int = 15_000,
        sendInterval: Int = 1_000,
    ) {
        logBeta(LogLevel.INFO, "Adding neighbor: $neighborRealInetAddr:$neighborRealPort")
        logger(Log.INFO, "$logPrefix 🔗 addNeighbor - Attempting to establish connection with $neighborRealInetAddr:$neighborRealPort (timeout=${timeout}ms)", null)

        //send originating packets out to the other device until we get something back from it
        val sendOriginatingMessageJob = scope.launch {
            var messageCount = 0
            while (true) {
                try {
                    messageCount++
                    logger(Log.INFO, "$logPrefix 📤 addNeighbor - Sending originating message #$messageCount to $neighborRealInetAddr:$neighborRealPort", null)
                    
                    val originatingMessage = makeOriginatingMessage()  // Use no-arg version with callbacks
                    socket.send(
                        nextHopAddress = neighborRealInetAddr,
                        nextHopPort = neighborRealPort,
                        virtualPacket = originatingMessage.toVirtualPacket(
                            toAddr = ADDR_BROADCAST,
                            fromAddr = localNodeAddress,
                            lastHopAddr = localNodeAddress,
                            hopCount = 1,
                        )
                    )
                    
                    logger(Log.DEBUG, "$logPrefix ✅ addNeighbor - Message #$messageCount sent successfully", null)
                }catch(e: Exception) {
                    logger(Log.WARN, "$logPrefix : addNeighbor : exception trying to send originating message #$messageCount", e)
                }

                delay(sendInterval.toLong())
            }
        }

        try {
            logger(Log.INFO, "$logPrefix ⏳ addNeighbor - Waiting for reply from $neighborRealInetAddr:$neighborRealPort...", null)
            
            withTimeout(timeout.toLong()) {
                val replyMessage = receivedMessages.filter {
                    it.lastHopRealInetAddr == neighborRealInetAddr && it.lastHopRealPort == neighborRealPort
                }.first()
                
                logger(Log.INFO, "$logPrefix ✅ addNeighbor - SUCCESS! Received reply from ${replyMessage.lastHopAddr.addressToDotNotation()} at $neighborRealInetAddr:$neighborRealPort", null)
                logBeta(LogLevel.INFO, "Received originating message reply from ${replyMessage.lastHopAddr.addressToDotNotation()}")
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger(Log.ERROR, "$logPrefix ❌ addNeighbor - TIMEOUT! No reply received from $neighborRealInetAddr:$neighborRealPort after ${timeout}ms", e)
            throw e
        } finally {
            sendOriginatingMessageJob.cancel()
            logger(Log.DEBUG, "$logPrefix addNeighbor - Stopped sending originating messages to $neighborRealInetAddr:$neighborRealPort", null)
        }

    }

    fun neighbors() : List<Pair<Int, VirtualNode.LastOriginatorMessage>> {
        return originatorMessages.filter { it.value.hopCount == 1.toByte() }.map {
            it.key to it.value
        }
    }


    fun close(){
        sendOriginatorMessagesFuture.cancel(true)
        pingNeighborsFuture.cancel(true)
        checkLostNodesFuture.cancel(true)
        scope.cancel("$logPrefix closed")
        closed = true
    }

    // Add a method to get neighbor fitness info
    fun getNeighborFitnessInfo(): Map<Int, Pair<Int, Byte>> = neighborFitnessInfo.toMap()

    fun gossipFitnessScore() {
        logBeta(LogLevel.DEBUG, "Gossiping fitness score")
        // Enhanced gossip protocol: propagate multi-hop neighbor info
        // For each direct neighbor, include our own neighbor count and centrality score in the message
        // (In a real implementation, you might extend MmcpOriginatorMessage to carry this info explicitly)
        sendOriginatingMessageRunnable.run()
        // Optionally, could send additional messages with multi-hop info, or piggyback on existing ones
    }

    /**
     * Send a custom message (deprecated - use makeOriginatingMessage for originating messages).
     * This is a placeholder for generic message sending.
     */
    fun sendMessage(message: MmcpMessage) {
        // Generic message sending - add to pending messages
        val messageId = message.messageId
        if (message is MmcpOriginatorMessage) {
            _state.value = _state.value.copy(
                pendingMessages = _state.value.pendingMessages + (messageId to message)
            )
        }
    }

    fun handlePong(pong: MmcpPong) {
        val messageId = pong.messageId
        _state.value = _state.value.copy(
            pendingMessages = _state.value.pendingMessages.filterKeys { it != messageId }
        )
    }

    fun getCurrentState(): OriginatingMessageState {
        return state.value
    }

    fun getNextMessageId(): Int {
        return messageCounter.incrementAndGet()
    }

    // === PHASE 3C: GATEWAY MESSAGE TRACKING METHODS ===
    
    /**
     * Tracks a message sent via gateway for return path routing.
     * Phase 3C: Gateway packet tracking
     *
     * @param fromAddr Source virtual address
     * @param fromPort Source port
     * @param toAddr Destination address (internet)
     * @param toPort Destination port
     * @param gatewayType Gateway type (TOR or CLEARNET)
     * @param gatewayAddr Gateway node address
     */
    fun trackGatewayMessage(
        fromAddr: Int,
        fromPort: Int,
        toAddr: Int,
        toPort: Int,
        gatewayType: Byte,
        gatewayAddr: Int
    ) {
        val key = createGatewayMessageKey(fromAddr, fromPort)
        val message = GatewayMessage(
            fromAddr = fromAddr,
            fromPort = fromPort,
            toAddr = toAddr,
            toPort = toPort,
            timestamp = System.currentTimeMillis(),
            gatewayType = gatewayType,
            gatewayAddr = gatewayAddr
        )
        
        gatewayMessages[key] = message
        
        logger(
            priority = Log.DEBUG,
            message = { 
                "$logPrefix Tracked gateway message: ${fromAddr.addressToDotNotation()}:$fromPort → " +
                "gateway ${gatewayAddr.addressToDotNotation()} (type=$gatewayType)" 
            }
        )
    }

    /**
     * Gets gateway address for return traffic.
     * Phase 3C: Used to route return packets back through same gateway
     *
     * @param toAddr Destination address (local node)
     * @param toPort Destination port
     * @return Gateway node address, or null if not routed via gateway
     */
    fun getGatewayForReturnTraffic(toAddr: Int, toPort: Int): Int? {
        val key = createGatewayMessageKey(toAddr, toPort)
        return gatewayMessages[key]?.gatewayAddr
    }

    /**
     * Returns statistics on gateway usage.
     * Phase 3C: For debugging and monitoring
     *
     * @return Map of gateway type to usage count
     */
    fun getGatewayUsageStats(): Map<Byte, Int> {
        val stats = mutableMapOf<Byte, Int>()
        
        gatewayMessages.values.forEach { msg ->
            val count = stats.getOrDefault(msg.gatewayType, 0)
            stats[msg.gatewayType] = count + 1
        }
        
        return stats
    }

    /**
     * Creates a unique key for gateway message tracking.
     * Format: "fromAddr:fromPort"
     */
    private fun createGatewayMessageKey(fromAddr: Int, fromPort: Int): String {
        return "$fromAddr:$fromPort"
    }

    /**
     * Cleans up stale gateway messages older than threshold.
     * Called periodically to prevent memory leaks.
     */
    fun cleanupStaleGatewayMessages(maxAgeMs: Long = 60_000L) {
        val now = System.currentTimeMillis()
        val iterator = gatewayMessages.entries.iterator()
        var removed = 0
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.timestamp > maxAgeMs) {
                iterator.remove()
                removed++
            }
        }
        
        if (removed > 0) {
            logger(
                priority = Log.DEBUG,
                message = { "$logPrefix Cleaned up $removed stale gateway messages" }
            )
        }
    }

        // Expose the current originatorMessages map for state updates
        fun getOriginatorMessages(): Map<Int, VirtualNode.LastOriginatorMessage> = originatorMessages

        /**
         * Immediately clears gateway roles for a node on receipt of a GATEWAY_DOWN message.
         * Prevents stale routing to a gateway that has lost its internet connection.
         */
        fun markNodeGatewayDown(addr: Int) {
            val existing = _topologyMapInfo[addr] ?: return
            _topologyMapInfo[addr] = existing.copy(
                meshRoles = existing.meshRoles - MeshRole.TOR_GATEWAY - MeshRole.CLEARNET_GATEWAY
            )
            _topologyMapFlow.value = _topologyMapInfo.toMap()
            logger(Log.INFO, "$logPrefix markNodeGatewayDown: cleared gateway roles for ${addr.addressToDotNotation()}", null)
        }
    }

/**
 * Tracks a message sent via gateway for return path routing.
 * Phase 3C: Gateway packet tracking data structure
 */
data class GatewayMessage(
    val fromAddr: Int,
    val fromPort: Int,
    val toAddr: Int,
    val toPort: Int,
    val timestamp: Long,
    val gatewayType: Byte,
    val gatewayAddr: Int
)

data class OriginatingMessageState(
    val pendingMessages: Map<Int, MmcpOriginatorMessage> = emptyMap(),
)

## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/NodeTopologyInfo.kt
package com.ustadmobile.meshrabiya.vnet

/**
 * Topology information for a single mesh node, extracted from MmcpOriginatorMessage.
 * Used for gateway selection and mesh intelligence.
 * 
 * Stores ALL role types:
 * - Gateway roles (TOR/CLEARNET/I2P) for routing decisions
 * - Intelligence roles (STORAGE/COMPUTE/MESH_ROUTER) for future optimization
 * 
 * @param nodeAddress Virtual address of the mesh node
 * @param neighbors Set of virtual addresses of direct neighbors
 * @param meshRoles Set of all roles this node offers (ALL 7 types)
 * @param centralityScore BFS centrality score (0.0-1.0) indicating network position
 * @param fitnessScore Hardware fitness score (0.0-1.0) indicating capability
 * @param lastSeen Timestamp when this node was last seen (originator message received)
 * @param pingTime Round-trip latency to reach this node (milliseconds)
 */
data class NodeTopologyInfo(
    val nodeAddress: Int,
    val neighbors: Set<Int>,
    val meshRoles: Set<MeshRole>,  // ALL 7 types stored
    val centralityScore: Float,
    val fitnessScore: Float,
    val lastSeen: Long = System.currentTimeMillis(),
    val pingTime: Short = 0,  // Latency to reach this node (ms)
    /** RSSI of non-mesh WiFi to internet AP, dBm. 0 = unknown. */
    val internetSignalStrengthDbm: Int = 0,
    /** Link speed of non-mesh WiFi, Mbps. 0 = unknown. */
    val internetLinkSpeedMbps: Int = 0,
) {
    /**
     * Check if node offers a specific role (gateway or intelligence)
     * @param role The role to check for
     * @return true if this node has the specified role
     */
    fun hasRole(role: MeshRole): Boolean {
        return meshRoles.contains(role)
    }
    
    /**
     * Check if node is a gateway (TOR, CLEARNET, or I2P)
     * @return true if this node offers any gateway role
     */
    fun isGatewayNode(): Boolean {
        return meshRoles.any { it in GATEWAY_ROLES }
    }
    
    /**
     * Calculate gateway suitability score (0.0-1.0)
     * Higher is better for routing decisions.
     * Only meaningful for nodes with gateway roles.
     * 
     * Algorithm:
     * - 30% centrality (network position)
     * - 40% fitness (hardware capability)
     * - 30% latency (response time)
     * 
     * @param gatewayType The gateway role to calculate suitability for
     * @return Suitability score 0.0-1.0, or 0 if node doesn't have this role
     */
    fun calculateGatewaySuitability(gatewayType: MeshRole): Float {
        if (!hasRole(gatewayType)) return 0f

        // Normalize latency: 0ms = 1.0, 1000ms = 0.0
        val normalizedLatency = 1f - (pingTime / 1000f).coerceIn(0f, 1f)

        // Normalize RSSI [-90,-30] dBm → [0.0,1.0]; 0 (unknown) = 0.5 neutral
        val signalQuality = if (internetSignalStrengthDbm != 0) {
            ((internetSignalStrengthDbm.toFloat() + 90f) / 60f).coerceIn(0f, 1f)
        } else 0.5f

        // Weights: 25% centrality + 35% fitness + 25% latency + 15% signal
        return (centralityScore * 0.25f) +
               (fitnessScore * 0.35f) +
               (normalizedLatency * 0.25f) +
               (signalQuality * 0.15f)
    }
    
    /**
     * Check if this topology info is stale (older than threshold)
     * @param thresholdMs Age threshold in milliseconds (default 30 seconds)
     * @return true if this node info is older than threshold
     */
    fun isStale(thresholdMs: Long = 30_000): Boolean {
        return (System.currentTimeMillis() - lastSeen) > thresholdMs
    }
    
    companion object {
        /**
         * Gateway roles used for packet routing
         */
        val GATEWAY_ROLES = setOf(
            MeshRole.TOR_GATEWAY,
            MeshRole.CLEARNET_GATEWAY,
            MeshRole.I2P_GATEWAY
        )
        
        /**
         * Intelligence roles used for future mesh optimization
         */
        val INTELLIGENCE_ROLES = setOf(
            MeshRole.STORAGE_NODE,
            MeshRole.COMPUTE_NODE,
            MeshRole.MESH_ROUTER
        )
    }
}


## RECOMMENDED FIXES (append)

1. VirtualNode.getAvailableGatewayAddresses() should prioritize precise gateways and return non-empty when topology includes remote gateway roles.
   - If list is empty but local topology has gateway nodes, include at least their addresses.
   - Keep consistent path: uses `NodeTopologyInfo.hasRole(MeshRole.CLEARNET_GATEWAY)` and `MeshRole.TOR_GATEWAY`.

2. MeshLocalSocksProxy.getGatewayAddress lambda should not return null if there are known gateway addresses.
   - If no address available, log warn plus use a fallback to `originator` to avoid immediate ECONNREFUSED.
   - Ensure `start()` binds and listens on 60547 before checkMeshConnection gets called (race averted by semantic lifecycle or retry loop).

3. ClearnetGatewayForwarder.forward() should not re-broadcast to 255.255.255.255:0.
   - Determine target address from packet header `toAddr`, `toPort` (likely 0 in this case) and suit actual destination.
   - If this is an internal mesh packet, do not route via ClearnetGatewayForwarder; bypass to mesh route.
   - If using internet interface binding, use `socket.connect(destAddress,destPort)` with a valid unicast destination; avoid broadcast for non-broadcast-capable interfaces.

4. NodeTopologyInfo.hasRole() should properly evaluate role bits for both local and remote nodes.
   - if role flags use `roleMask`, ensure BITWISE operations match definitions and ensure remote node with role `CLEARNET_GATEWAY` is visible as true.

5. Enhance `OriginatingMessageManager` or gateway selection to avoid `ClearnetGateway forward error: EINVAL` and instead relay only non-mesh data.
   - Add guard: if incoming packet is MMCP originator message, do not forward via clearnet.
   - Archive/ignore remote gateway broadcast reporting path that triggers sendto with broadcast address.

6. UI side defense (optionally): in EnhancedMeshFragment `setupNetworkInfoObserver`, avoid hiding mesh row while peer count>0 and connected true, even if ssid is null.
   - Then display connectivity + find alternative ssid source once available.

