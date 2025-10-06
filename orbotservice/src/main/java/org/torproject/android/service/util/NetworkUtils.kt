package org.torproject.android.service.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Socket

object NetworkUtils {
    @JvmStatic
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    @JvmStatic
    fun checkPortOrAuto(portString: String) : String {
        if (!portString.equals("auto", ignoreCase = true)) {
            var isPortUsed = true
            var port = portString.toInt()
            while (isPortUsed) {
                isPortUsed = isPortOpen("127.0.0.1", port, 500)
                if (isPortUsed)  //the specified port is not available, so find one instead
                    port++
            }
            return port.toString()
        }
        return portString
    }

    @JvmStatic
    fun isPortOpen(ip: String?, port: Int, timeout: Int): Boolean {
        try {
            val socket = Socket()
            // If caller provided a negative timeout, fallback to provider value
            val connectTimeout = if (timeout > 0) timeout else com.ustadmobile.meshrabiya.net.DefaultSocketTimeoutsProvider().connectTimeoutMillis
            socket.connect(InetSocketAddress(ip, port), connectTimeout)
            socket.close()
            return true
        } catch (ex: Exception) {
            return false
        }
    }
}