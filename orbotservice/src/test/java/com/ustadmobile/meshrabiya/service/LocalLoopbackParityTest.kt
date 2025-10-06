package com.ustadmobile.meshrabiya.service

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.robolectric.Robolectric
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Integration-style parity test: run the local loopback server and a second simulated
 * endpoint (same LocalMeshrabiyaHttpServer backed by the same service) and verify
 * identical behavior for GET /identity and POST /store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalLoopbackParityTest {

    private lateinit var service: MeshrabiyaAidlService
    private var extraServer: LocalMeshrabiyaHttpServer? = null

    @Before
    fun setUp() {
        // Speed up offer windows and server polling in test environment
        System.setProperty("meshrabiya.offer_window_ms", "200")
        System.setProperty("meshrabiya.replication_wait_ms", "2000")
        System.setProperty("meshrabiya.replication_poll_ms", "50")
        // Create the service via Robolectric; this will call onCreate() and attempt to start
        // a LocalMeshrabiyaHttpServer bound to localhost on an ephemeral port.
        val controller = Robolectric.buildService(MeshrabiyaAidlService::class.java).create()
        service = controller.get()

        // Also create a second server instance (simulated remote) backed by the same service
    extraServer = LocalMeshrabiyaHttpServer(service, "127.0.0.1", 0)
    extraServer?.start(5000, false)
    }

    @After
    fun tearDown() {
        try {
            extraServer?.stop()
        } catch (_: Exception) {}
        try {
            // Stop the service's server via lifecycle
            service.onDestroy()
        } catch (_: Exception) {}
    }

    @Test
    fun identityAndStoreParity() {
        // Reflectively get the server instance started by the service (if any)
        val httpServerField = MeshrabiyaAidlService::class.java.getDeclaredField("httpServer")
        httpServerField.isAccessible = true
        val localServer = httpServerField.get(service) as? LocalMeshrabiyaHttpServer

        assertNotNull("Service should have started a LocalMeshrabiyaHttpServer instance", localServer)

        val serverA = localServer!!
        val serverB = extraServer!!

        // Obtain ports
        val portA = serverA.javaClass.getMethod("getListeningPort").invoke(serverA) as Int
        val portB = serverB.javaClass.getMethod("getListeningPort").invoke(serverB) as Int

        assertTrue("portA should be > 0", portA > 0)
        assertTrue("portB should be > 0", portB > 0)

        // Get auth token
        val token = service.getLocalAuthTokenInternal()
        assertNotNull("Local auth token should be present", token)

        // GET /identity against both servers
        val identityA = httpGetJson("http://127.0.0.1:$portA/identity", token!!)
        val identityB = httpGetJson("http://127.0.0.1:$portB/identity", token)

        // Responses should be identical (same onion_pubkey / api_version fields)
        assertEquals(identityA.getString("api_version"), identityB.getString("api_version"))
        assertEquals(identityA.optString("onion_pubkey"), identityB.optString("onion_pubkey"))

        // POST /store with small payload to both servers
        val payload = "hello-meshrabiya-parity-test".toByteArray(Charsets.UTF_8)

        val respA = httpPostRaw("http://127.0.0.1:$portA/store", token, payload)
        val respB = httpPostRaw("http://127.0.0.1:$portB/store", token, payload)

        // parse JSON
    val jsonA = JSONObject(respA)
    val jsonB = JSONObject(respB)

        // Debug output to help diagnose any mismatch between returned blobId and stored files
    println("POST respA=$respA")
    println("POST respB=$respB")

        assertTrue(jsonA.has("blobId"))
        assertTrue(jsonB.has("blobId"))

        val blobA = jsonA.getString("blobId")
        val blobB = jsonB.getString("blobId")

        // Ensure underlying files were created for each blobId
        val blobsBase = java.io.File(service.filesDir, "meshrabiya_blobs")

        // Diagnostic: print service filesDir and listing of blobs directory to help debug
        println("service.filesDir=${service.filesDir.absolutePath}")
        if (blobsBase.exists() && blobsBase.isDirectory) {
            println("blobsBase dir exists; listing:")
            blobsBase.listFiles()?.forEach { f -> println(" - ${f.name} -> ${f.absolutePath}") }
        } else {
            println("blobsBase dir does not exist: ${blobsBase.absolutePath}")
        }

    val blobAFile = java.io.File(blobsBase, "${blobA}.blob")
    val blobAMeta = java.io.File(blobsBase, "${blobA}.json")
    val blobARepl = java.io.File(blobsBase, "${blobA}.repl.json")

    val blobBFile = java.io.File(blobsBase, "${blobB}.blob")
    val blobBMeta = java.io.File(blobsBase, "${blobB}.json")
    val blobBRepl = java.io.File(blobsBase, "${blobB}.repl.json")

    // Print explicit exist checks
    println("blobA=$blobA exists=${blobAFile.exists()} file=${blobAFile.absolutePath}")
    println("blobA meta exists=${blobAMeta.exists()} file=${blobAMeta.absolutePath}")
    println("blobA repl exists=${blobARepl.exists()} file=${blobARepl.absolutePath}")

    println("blobB=$blobB exists=${blobBFile.exists()} file=${blobBFile.absolutePath}")
    println("blobB meta exists=${blobBMeta.exists()} file=${blobBMeta.absolutePath}")
    println("blobB repl exists=${blobBRepl.exists()} file=${blobBRepl.absolutePath}")

        // Print canonical paths to help detect path mismatches or hidden characters
        try {
            println("blobAFile abs=${blobAFile.absolutePath} canon=${try { blobAFile.canonicalPath } catch (e: Exception) { "<err:${e.message}>" } } exists=${blobAFile.exists()} len=${if (blobAFile.exists()) blobAFile.length() else -1}")
            println("blobAMeta abs=${blobAMeta.absolutePath} canon=${try { blobAMeta.canonicalPath } catch (e: Exception) { "<err:${e.message}>" } } exists=${blobAMeta.exists()} len=${if (blobAMeta.exists()) blobAMeta.length() else -1}")
            println("blobARepl abs=${blobARepl.absolutePath} canon=${try { blobARepl.canonicalPath } catch (e: Exception) { "<err:${e.message}>" } } exists=${blobARepl.exists()} len=${if (blobARepl.exists()) blobARepl.length() else -1}")

            println("blobBFile abs=${blobBFile.absolutePath} canon=${try { blobBFile.canonicalPath } catch (e: Exception) { "<err:${e.message}>" } } exists=${blobBFile.exists()} len=${if (blobBFile.exists()) blobBFile.length() else -1}")
            println("blobBMeta abs=${blobBMeta.absolutePath} canon=${try { blobBMeta.canonicalPath } catch (e: Exception) { "<err:${e.message}>" } } exists=${blobBMeta.exists()} len=${if (blobBMeta.exists()) blobBMeta.length() else -1}")
            println("blobBRepl abs=${blobBRepl.absolutePath} canon=${try { blobBRepl.canonicalPath } catch (e: Exception) { "<err:${e.message}>" } } exists=${blobBRepl.exists()} len=${if (blobBRepl.exists()) blobBRepl.length() else -1}")
        } catch (e: Exception) {
            println("Diagnostic canonical path check failed: ${e}")
        }

        assertTrue("blobA file should exist", blobAFile.exists())
        assertTrue("blobA meta should exist", blobAMeta.exists())
        assertTrue("blobA repl should exist", blobARepl.exists())

        assertTrue("blobB file should exist", blobBFile.exists())
        assertTrue("blobB meta should exist", blobBMeta.exists())
        assertTrue("blobB repl should exist", blobBRepl.exists())
    }

    private fun httpGetJson(url: String, token: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("X-Meshrabiya-Auth", token)
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        val code = conn.responseCode
        val input = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = input.readAllBytes().toString(Charsets.UTF_8)
        conn.disconnect()
        return JSONObject(body)
    }

    private fun httpPostRaw(url: String, token: String, payload: ByteArray): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("X-Meshrabiya-Auth", token)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        // Ensure the Content-Length header is present and close the connection after response
        conn.setRequestProperty("Content-Length", payload.size.toString())
        conn.setRequestProperty("Connection", "close")
        // Tell the server how many bytes we'll send so NanoHTTPD can process body correctly
        conn.setFixedLengthStreamingMode(payload.size)
    conn.connectTimeout = 10000
    conn.readTimeout = 15000
        conn.outputStream.use { os ->
            BufferedOutputStream(os).use { bos ->
                bos.write(payload)
                bos.flush()
            }
        }
        val code = conn.responseCode
        val input = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = input.readAllBytes().toString(Charsets.UTF_8)
        conn.disconnect()
        return resp
    }
}
