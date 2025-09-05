package com.ustadmobile.orbotmeshrabiyaintegration.routing

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ustadmobile.orbotmeshrabiyaintegration.interfaces.MeshTrafficRouter
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// ...full code as previously read...
