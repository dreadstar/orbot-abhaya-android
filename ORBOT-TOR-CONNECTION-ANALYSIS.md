# Orbot Tor Connection Management - Technical Analysis

## Executive Summary

This document provides a comprehensive analysis of how Orbot manages Tor connections on Android, including the architecture, connection details access methods, and integration patterns for external applications.

---

## 1. Repository Overview

**Repository:** https://github.com/guardianproject/orbot-android  
**Primary Language:** Kotlin (64.3%), Java (34.0%)  
**Key Dependencies:**
- Tor binary (currently v0.4.8.x)
- hev-socks5-tunnel (submodule)
- IPtProxy (Obfs4Proxy, Meek, Snowflake)
- Go-Tun2Socks
- LibEvent

### Build Requirements
```bash
git clone --recursive https://github.com/guardianproject/orbot-android
git submodule update --init --recursive
```

---

## 2. Architecture Overview

### 2.1 Service Architecture Evolution

**Historical Context:**
- **Pre-v16.2.0:** Two separate services
  - `TorService` - Managed Tor daemon
  - `TorVPNService` - Managed VPN functionality
  
- **Post-v16.2.0 (Current):** Unified architecture
  - **`OrbotService`** - Single service extending `VpnService`
  - Integrated both Tor and VPN management
  - Removed `VPNEnableActivity`

**Key File Locations:**
- Main service: `app/src/main/java/org/torproject/android/service/OrbotService.java`
- Tor JNI interface: Previously in `tor-android-binary/src/main/java/org/torproject/jni/TorService.java`
- VPN Manager: `app/src/main/java/org/torproject/android/service/vpn/OrbotVpnManager.java`

### 2.2 Core Components

```
┌─────────────────────────────────────────┐
│         OrbotService                    │
│  (extends VpnService)                   │
│                                         │
│  ┌─────────────────────────────┐       │
│  │   Tor Control Connection    │       │
│  │   - TorControlConnection    │       │
│  │   - Control Port            │       │
│  └─────────────────────────────┘       │
│                                         │
│  ┌─────────────────────────────┐       │
│  │   Proxy Management          │       │
│  │   - SOCKS Port (9050)       │       │
│  │   - HTTP Port (8118)        │       │
│  │   - DNS Port (5400)         │       │
│  │   - Trans Port              │       │
│  └─────────────────────────────┘       │
│                                         │
│  ┌─────────────────────────────┐       │
│  │   VPN Management            │       │
│  │   - OrbotVpnManager         │       │
│  │   - tun2socks               │       │
│  └─────────────────────────────┘       │
└─────────────────────────────────────────┘
```

---

## 3. Tor Connection Management

### 3.1 Connection Initialization Flow

1. **Service Start**
   ```java
   // OrbotService binds to TorService via ServiceConnection
   shouldUnbindTorService = bindService(serviceIntent, BIND_AUTO_CREATE, 
                                        mExecutor, torServiceConnection);
   ```

2. **Control Connection Setup**
   ```java
   // Wait for TorControlConnection to become available
   while ((conn = torService.getTorControlConnection()) == null) {
       Thread.sleep(500);
   }
   
   // Initialize control connection
   initControlConnection();
   ```

3. **Control Port File Reading**
   ```java
   // Tor writes control port to file
   File fileControlPort = new File(appDataDir, "control.txt");
   BufferedReader bufferedReader = new BufferedReader(new FileReader(fileControlPort));
   String line = bufferedReader.readLine(); // Format: "PORT=127.0.0.1:12345"
   ```

### 3.2 Default Port Configuration

| Service | Default Port | Config Key | Purpose |
|---------|--------------|------------|---------|
| SOCKS Proxy | 9050 | `SOCKSPort` | Main SOCKS5 proxy |
| HTTP Proxy | 8118 | `HTTPTunnelPort` | HTTP tunnel |
| DNS | 5400 | `DNSPort` | DNS resolution |
| Trans | Auto | `TransPort` | Transparent proxy |
| Control | Dynamic | `ControlPort` | Tor control interface |

### 3.3 Tor Configuration (torrc)

Key configuration parameters set by Orbot:
```
ControlPortWriteToFile /data/data/org.torproject.android/files/control.txt
PidFile /data/data/org.torproject.android/files/torpid
SOCKSPort 9050 IsolateDestAddr IPv6Traffic PreferIPv6
SafeSocks 0
TestSocks 0
HTTPTunnelPort 8118
TransPort auto
DNSPort 5400
VirtualAddrNetwork 10.192.0.0/10
AutomapHostsOnResolve 1
DisableNetwork 0
```

---

## 4. Accessing Connection Details from Code

### 4.1 Broadcast Intents (Primary Method)

Orbot uses Android broadcast intents to communicate connection status and port information.

#### 4.1.1 Status Broadcasts

**Global Broadcasts:**
```java
// Action constants
public static final String ACTION_STATUS = "org.torproject.android.intent.action.STATUS";
public static final String ACTION_START = "org.torproject.android.intent.action.START";
public static final String ACTION_STOP = "org.torproject.android.intent.action.STOP";

// Status values
public static final String STATUS_OFF = "OFF";
public static final String STATUS_ON = "ON";
public static final String STATUS_STARTING = "STARTING";
public static final String STATUS_STOPPING = "STOPPING";

// Extra keys
public static final String EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS";
```

**Receiving Status Updates:**
```java
// Register broadcast receiver for Orbot status
BroadcastReceiver orbotStatusReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
        String status = intent.getStringExtra("org.torproject.android.intent.extra.STATUS");
        
        if ("ON".equals(status)) {
            // Tor is connected
            // Safe to retrieve proxy details
        } else if ("STARTING".equals(status)) {
            // Tor is connecting
        } else if ("OFF".equals(status)) {
            // Tor is disconnected
        }
    }
};

// Register receiver
IntentFilter filter = new IntentFilter("org.torproject.android.intent.action.STATUS");
context.registerReceiver(orbotStatusReceiver, filter);
```

#### 4.1.2 Port Information Broadcasts

**Local Broadcasts (Within App):**
```java
// From OrbotService.java
public static final String LOCAL_ACTION_PORTS = "org.torproject.android.service.LOCAL_ACTION_PORTS";
public static final String EXTRA_SOCKS_PROXY_PORT = "org.torproject.android.service.extra.SOCKS_PROXY_PORT";
public static final String EXTRA_HTTP_PROXY_PORT = "org.torproject.android.service.extra.HTTP_PROXY_PORT";
public static final String EXTRA_DNS_PORT = "org.torproject.android.service.extra.DNS_PORT";
public static final String EXTRA_TRANS_PORT = "org.torproject.android.service.extra.TRANS_PORT";

// Broadcasting ports
var intent = new Intent(LOCAL_ACTION_PORTS)
    .putExtra(EXTRA_SOCKS_PROXY_PORT, socksPort)
    .putExtra(EXTRA_HTTP_PROXY_PORT, httpPort)
    .putExtra(EXTRA_DNS_PORT, dnsPort)
    .putExtra(EXTRA_TRANS_PORT, transPort);
LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
```

**Receiving Port Information:**
```java
// Using LocalBroadcastManager for internal communication
BroadcastReceiver portsReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
        int socksPort = intent.getIntExtra(EXTRA_SOCKS_PROXY_PORT, 9050);
        int httpPort = intent.getIntExtra(EXTRA_HTTP_PROXY_PORT, 8118);
        int dnsPort = intent.getIntExtra(EXTRA_DNS_PORT, 5400);
        int transPort = intent.getIntExtra(EXTRA_TRANS_PORT, 0);
        
        // Use the port information
        String socksProxy = "socks5://127.0.0.1:" + socksPort;
        String httpProxy = "http://127.0.0.1:" + httpPort;
    }
};

LocalBroadcastManager.getInstance(context)
    .registerReceiver(portsReceiver, 
                     new IntentFilter(LOCAL_ACTION_PORTS));
```

### 4.2 Global Broadcast with Proxy Details

When Orbot responds to start requests, it includes proxy information:

```java
// Orbot's response includes:
reply.putExtra("org.torproject.android.intent.extra.SOCKS_PROXY", 
               "socks://127.0.0.1:" + socksPort);
reply.putExtra("org.torproject.android.intent.extra.SOCKS_PROXY_HOST", 
               "127.0.0.1");
reply.putExtra("org.torproject.android.intent.extra.SOCKS_PROXY_PORT", 
               socksPort);
reply.putExtra("org.torproject.android.intent.extra.HTTP_PROXY", 
               "http://127.0.0.1:" + httpPort);
reply.putExtra("org.torproject.android.intent.extra.HTTP_PROXY_HOST", 
               "127.0.0.1");
reply.putExtra("org.torproject.android.intent.extra.HTTP_PROXY_PORT", 
               httpPort);
```

### 4.3 Starting Orbot from External Apps

**Request Orbot to Start:**
```java
// Send start intent
Intent startIntent = new Intent("org.torproject.android.intent.action.START");
startIntent.setPackage("org.torproject.android");
context.sendBroadcast(startIntent);

// Register receiver to get response
BroadcastReceiver responseReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
        if ("ON".equals(intent.getStringExtra(EXTRA_STATUS))) {
            // Orbot is ready
            int socksPort = intent.getIntExtra(EXTRA_SOCKS_PROXY_PORT, 9050);
            int httpPort = intent.getIntExtra(EXTRA_HTTP_PROXY_PORT, 8118);
            
            // Configure your app to use these proxies
        }
    }
};
```

### 4.4 Direct Access via Shared Preferences (Legacy)

Some connection details may be stored in SharedPreferences:

```java
SharedPreferences prefs = context.getSharedPreferences("org.torproject.android_preferences", 
                                                       Context.MODE_PRIVATE);
String socksConfig = prefs.getString("pref_socks", "9050");
String httpConfig = prefs.getString("pref_http", "8118");
```

### 4.5 TorControlConnection Access (Advanced)

For direct control connection access (if you're within Orbot or have deep integration):

```java
// Get TorControlConnection from TorService
TorControlConnection conn = torService.getTorControlConnection();

if (conn != null) {
    try {
        // Get SOCKS listeners
        List<ConfigEntry> socksListeners = conn.getConf("net/listeners/socks");
        
        // Get HTTP listeners  
        List<ConfigEntry> httpListeners = conn.getConf("net/listeners/httptunnel");
        
        // Get circuit status
        conn.setEvents(Arrays.asList("CIRC"));
        
        // New identity
        conn.signal("NEWNYM");
        
        // Set exit node
        conn.setConf("ExitNodes", "{de}");
        
    } catch (IOException e) {
        e.printStackTrace();
    }
}
```

---

## 5. Integration Patterns

### 5.1 Using NetCipher Library (Recommended)

NetCipher is Guardian Project's official library for Tor integration:

**Gradle Dependency:**
```gradle
implementation 'info.guardianproject.netcipher:netcipher:2.1.0'
implementation 'info.guardianproject.netcipher:netcipher-okhttp3:2.1.0'
```

**Basic Usage:**
```java
// Initialize OrbotHelper early in app lifecycle
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        OrbotHelper.get(this).init();
    }
}

// Use StrongConnectionBuilder for HTTP connections
StrongConnectionBuilder.forMaxSecurity(context)
    .build(new StrongConnectionBuilder.Callback<HttpURLConnection>() {
        @Override
        public void onConnected(HttpURLConnection connection) {
            // Connection is now routed through Tor
            // Perform HTTP operations on background thread
        }
        
        @Override
        public void onConnectionException(Exception e) {
            // Handle connection errors
        }
        
        @Override
        public void onTimeout() {
            // Orbot connection timed out
        }
        
        @Override
        public void onInvalid() {
            // Tor validation failed
        }
    });
```

**OkHttp3 Integration:**
```java
StrongOkHttpClientBuilder.forMaxSecurity(context)
    .build(new StrongOkHttpClientBuilder.Callback<OkHttpClient>() {
        @Override
        public void onConnected(OkHttpClient client) {
            // Use OkHttpClient configured for Tor
            Request request = new Request.Builder()
                .url("https://check.torproject.org")
                .build();
                
            client.newCall(request).enqueue(callback);
        }
        
        @Override
        public void onConnectionException(Exception e) {
            Log.e(TAG, "Orbot connection failed", e);
        }
        
        @Override
        public void onTimeout() {
            Log.e(TAG, "Orbot connection timeout");
        }
        
        @Override
        public void onInvalid() {
            Log.e(TAG, "Tor validation failed");
        }
    });
```

### 5.2 Manual Proxy Configuration

**Setting SOCKS Proxy Manually:**
```java
// Wait for Orbot to be ready
private void configureProxyWhenReady() {
    BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(EXTRA_STATUS);
            if ("ON".equals(status)) {
                // Orbot is ready
                configureSocksProxy("127.0.0.1", 9050);
            }
        }
    };
    
    IntentFilter filter = new IntentFilter(ACTION_STATUS);
    registerReceiver(receiver, filter);
    
    // Request Orbot to start
    Intent start = new Intent(ACTION_START);
    start.setPackage("org.torproject.android");
    sendBroadcast(start);
}

private void configureSocksProxy(String host, int port) {
    // For Java networking
    System.setProperty("socksProxyHost", host);
    System.setProperty("socksProxyPort", String.valueOf(port));
    
    // For OkHttp
    Proxy proxy = new Proxy(Proxy.Type.SOCKS, 
                           new InetSocketAddress(host, port));
    OkHttpClient client = new OkHttpClient.Builder()
        .proxy(proxy)
        .build();
}
```

### 5.3 VPN Mode Integration

If using Orbot's VPN mode:

```java
// Check if VPN mode is enabled
SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
boolean vpnMode = prefs.getBoolean("pref_vpn", false);

if (vpnMode) {
    // In VPN mode, all app traffic is automatically routed through Tor
    // No need to configure proxy settings
} else {
    // Configure SOCKS/HTTP proxy manually
    configureProxySettings();
}
```

---

## 6. Connection State Management

### 6.1 Status Flow

```
OFF → STARTING → ON
 ↑                 ↓
 ←── STOPPING ←───┘
```

### 6.2 Event Handling

**OrbotRawEventListener:**
```java
// From OrbotService
mOrbotRawEventListener = new OrbotRawEventListener(OrbotService.this);

// Listens for Tor events:
// - CIRC (Circuit status)
// - STREAM (Stream status)
// - ORCONN (OR connection status)
// - BW (Bandwidth usage)
// - NOTICE (Notice messages)
```

### 6.3 Bootstrap Progress

Tor reports bootstrap progress from 0-100%:
```java
// Bootstrap messages example:
"Tor process starting"
"Establishing circuits"
"Loading network status"
"Establishing connection to Tor" 
"Circuit established" // 100% - STATUS_ON
```

---

## 7. Common Connection Details

### 7.1 Default Configuration

```java
public class OrbotConnectionInfo {
    public static final String SOCKS_HOST = "127.0.0.1";
    public static final int SOCKS_PORT = 9050;
    
    public static final String HTTP_HOST = "127.0.0.1";
    public static final int HTTP_PORT = 8118;
    
    public static final String DNS_HOST = "127.0.0.1";
    public static final int DNS_PORT = 5400;
    
    public static final String PACKAGE_NAME = "org.torproject.android";
}
```

### 7.2 Connection URLs

```
SOCKS5: socks5://127.0.0.1:9050
HTTP:   http://127.0.0.1:8118
DNS:    127.0.0.1:5400
```

---

## 8. Troubleshooting

### 8.1 Common Port Conflicts

**Problem:** Port 9050 already in use  
**Solutions:**
1. Check for Samsung Link service (known to use port 9050)
2. Use SockStat app to identify conflicting app
3. Force stop/disable conflicting app
4. Change Orbot SOCKS port in settings (Debug → Tor SOCKS)

### 8.2 Connection Failures

**Symptoms:**
- ERR_PROXY_CONNECTION_FAILED
- SOCKS connection refused
- Tor stuck on "Waiting for lock"

**Debug Steps:**
```java
// 1. Check Orbot status
Intent statusIntent = new Intent(ACTION_STATUS);
sendBroadcast(statusIntent);

// 2. Verify ports are open
// Use netstat or ss: ss -tlp sport == 9050

// 3. Check Tor logs
// adb logcat | grep -i "tor"

// 4. Verify control connection
File controlPort = new File(context.getFilesDir(), "control.txt");
if (controlPort.exists()) {
    // Read port info
}
```

### 8.3 VPN Mode Issues

- Only one VPN can be active at a time on Android
- VPN mode requires VPN permission
- Some Android versions (Q/10+) have specific requirements

---

## 9. Security Considerations

### 9.1 Best Practices

1. **Always validate Tor connection** before sending sensitive data
2. **Use SafeSocks** when possible (validates SOCKS protocol)
3. **Enable DNS over Tor** to prevent DNS leaks
4. **Use circuit isolation** for different apps/activities
5. **Implement proper error handling** for connection failures

### 9.2 Connection Validation

```java
// Validate you're actually using Tor
private void validateTorConnection() {
    new Thread(() -> {
        try {
            URL url = new URL("https://check.torproject.org/api/ip");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            
            // Parse JSON response
            // {"IsTor": true, "IP": "xxx.xxx.xxx.xxx"}
            
        } catch (Exception e) {
            Log.e(TAG, "Tor validation failed", e);
        }
    }).start();
}
```

---

## 10. Key Takeaways

### For Accessing Connection Details:

1. **Broadcast Intents** are the primary mechanism
   - Register for `ACTION_STATUS` broadcasts
   - Listen for `LOCAL_ACTION_PORTS` for port info
   
2. **Default ports** are predictable:
   - SOCKS: 9050
   - HTTP: 8118
   - DNS: 5400

3. **NetCipher library** simplifies integration
   - Handles connection setup automatically
   - Provides callbacks for status changes

4. **Always check connection status** before using proxies

5. **Use LocalBroadcastManager** for internal app communication

### Architecture Summary:

- **Single service** (OrbotService) manages everything
- **Unified VPN and Tor** management since v16.2.0
- **TorControlConnection** provides low-level control
- **Broadcast intents** for app communication
- **File-based** control port configuration

---

## 11. Additional Resources

- **Official Orbot Documentation:** https://orbot.app
- **Guardian Project:** https://guardianproject.info
- **Tor Project:** https://torproject.org
- **NetCipher Library:** https://github.com/guardianproject/NetCipher
- **Orbot Intent API:** https://dev.guardianproject.info/projects/orbot/wiki/Orbot_Intent_API

---

## Appendix: Code Reference Locations

### Key Files in Repository:

```
orbot-android/
├── app/src/main/
│   ├── java/org/torproject/android/
│   │   ├── service/
│   │   │   ├── OrbotService.java          # Main service
│   │   │   ├── vpn/
│   │   │   │   └── OrbotVpnManager.java   # VPN management
│   │   │   └── util/
│   │   │       └── Prefs.java             # Preferences
│   │   └── OrbotMainActivity.java         # Main UI
│   └── res/
│       └── values/strings.xml             # String constants
└── tor-android-binary/
    └── src/main/java/org/torproject/jni/
        └── TorService.java                # JNI interface
```

### Important Constants Files:

- `OrbotConstants.java` - Broadcast action constants
- `TorServiceConstants.java` - Service configuration constants
- `Prefs.java` - Shared preferences keys

---

*Last Updated: December 2025*  
*Orbot Version: 17.7.x*  
*Tor Version: 0.4.8.x*