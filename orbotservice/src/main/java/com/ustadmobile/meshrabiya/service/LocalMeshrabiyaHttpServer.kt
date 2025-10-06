package com.ustadmobile.meshrabiya.service

import fi.iki.elonen.NanoHTTPD
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import org.json.JSONObject
import java.io.*
import java.util.*

/**
 * Small embedded HTTP server to expose local endpoints for sensor handoff.
 * Endpoints:
 *  - GET  /identity -> JSON { "onion_pubkey": ..., "api_version": 1 }
 *  - POST /store (multipart or raw body) -> JSON { "blobId": "..." }
 *  - POST /descriptor -> JSON { "descriptorId": "...", "accepted": true }
 *
 * This server is intentionally minimal and intended for loopback use only. It
 * should be bound to localhost only and not exposed beyond the device.
 */
class LocalMeshrabiyaHttpServer(private val service: MeshrabiyaAidlService, host: String = "127.0.0.1", port: Int = 0,
                               /** If false, the server will return 503 Service Unavailable for /store requests */
                               var participateInDelegation: Boolean = true) : NanoHTTPD(host, port) {

    // Shared BetaTestLogger instance for this server
    private val betaLogger: BetaTestLogger = try { BetaTestLogger.getInstance(service) } catch (e: Exception) { BetaTestLogger.createTestInstance() }

    companion object {
        private const val TAG = "LocalMeshrabiyaHttpServer"
    }

    override fun serve(session: IHTTPSession?): Response {
        if (session == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No session")

        // Simple local auth: require header X-Meshrabiya-Auth matching service token
        val authHeader = session.headers["x-meshrabiya-auth"]
        val token = try { service.getLocalAuthTokenInternal() } catch (_: Exception) { null }
        if (token == null || authHeader == null || authHeader != token) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"error\":\"unauthorized\"}")
        }

        val uri = session.uri
        try {
            return when {
                session.method == Method.GET && uri == "/identity" -> handleIdentity()
                session.method == Method.POST && uri == "/store" -> handleStore(session)
                session.method == Method.POST && uri == "/descriptor" -> handleDescriptor(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"not_found\"}")
            }
        } catch (e: Exception) {
            // Print to stderr/console to ensure visibility in test logs
            System.err.println("LocalMeshrabiyaHttpServer: Request handling failed: " + e.toString())
            e.printStackTrace()
            Log.w(TAG, "Request handling failed", e)
            // Include exception message in response JSON for debugging
            val err = JSONObject()
            err.put("error", "internal")
            err.put("ex", e.toString())
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", err.toString())
        }
    }

    private fun handleIdentity(): Response {
        val pubKey = service.getOnionPubKeyInternal()
        val onionAddr = service.getOnionAddressInternal()
        val json = JSONObject()
        json.put("onion_pubkey", pubKey)
        json.put("onion_address", onionAddr)
        json.put("api_version", service.getApiVersionInternal())
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun handleStore(session: IHTTPSession): Response {
        // If this node is not participating in delegation/replication, return 503 with Retry-After
        if (!participateInDelegation) {
            val retrySeconds = 5
            val err = JSONObject()
            err.put("error", "service_unavailable")
            err.put("message", "node not participating in delegation")
            err.put("retryAfter", retrySeconds)
            val r = newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "application/json", err.toString())
            r.addHeader("Retry-After", retrySeconds.toString())
            r.addHeader("Connection", "close")
            return r
        }
        var tempFile: File? = null
        var pfd: ParcelFileDescriptor? = null
        try {
            val files: MutableMap<String, String> = HashMap()
            val contentType = session.headers["content-type"] ?: ""
            var tempPath: String? = null

            // Only use parseBody for multipart form uploads; for raw octet-stream reads we
            // copy the session.inputStream directly to a temp file to avoid parseBody
            // consuming the stream without returning a usable absolute temp path.
            if (contentType.contains("multipart", ignoreCase = true)) {
                try {
                    session.parseBody(files)
                    tempPath = files.values.firstOrNull()
                        betaLogger.log(LogLevel.DEBUG, TAG, "handleStore: parseBody tempPath=$tempPath headersContentLength=${session.headers["content-length"]}", emptyMap())
                } catch (e: Exception) {
                    betaLogger.log(LogLevel.WARN, TAG, "Failed to parse multipart request body: ${e.message}", emptyMap(), e)
                    // fallthrough to raw copy
                }
            }

            if (tempPath == null || !File(tempPath).isAbsolute) {
                tempFile = File.createTempFile("meshrabiya-upload-", null, service.filesDir)
                // copy raw input to temp file. Read exactly Content-Length bytes when available
                val contentLength = session.headers["content-length"]?.toLongOrNull()
                betaLogger.log(LogLevel.DEBUG, TAG, "handleStore: content-length header=$contentLength", emptyMap())
                if (contentLength != null) {
                    val buf = ByteArray(8 * 1024)
                    var remaining = contentLength
                    val input = session.inputStream
                    val out = tempFile.outputStream()
                    try {
                        while (remaining > 0) {
                            val toRead = if (remaining > buf.size) buf.size else remaining.toInt()
                            val read = input.read(buf, 0, toRead)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            remaining -= read
                        }
                    } finally {
                        try { out.close() } catch (_: Exception) {}
                    }
                } else {
                    session.inputStream.use { input ->
                        tempFile.outputStream().use { out ->
                            input.copyTo(out)
                        }
                    }
                }
                tempPath = tempFile.absolutePath
                betaLogger.log(LogLevel.DEBUG, TAG, "handleStore: wrote request body to temp file=$tempPath", emptyMap())
            }

            val temp = File(tempPath)
            if (!temp.exists()) {
                throw FileNotFoundException(tempPath)
            }

            betaLogger.log(LogLevel.INFO, TAG, "handleStore: got temp upload file=$tempPath size=${temp.length()}", emptyMap())

            pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val blobId = service.storeBlobInternal(pfd)
            betaLogger.log(LogLevel.INFO, TAG, "handleStore: storeBlobInternal returned blobId=$blobId", emptyMap())
            // Diagnostic logging: check for the canonical stored files and print
            // detailed information to help diagnose race conditions where the
            // service believes it stored the blob but test-level File.exists()
            // checks appear to fail.
            try {
                val blobsBase = java.io.File(service.filesDir, "meshrabiya_blobs")
                    betaLogger.log(LogLevel.DEBUG, TAG, "handleStore: checking blobsBase=${blobsBase.absolutePath}", emptyMap())
                if (blobsBase.exists() && blobsBase.isDirectory) {
                    betaLogger.log(LogLevel.DEBUG, TAG, "handleStore: blobsBase listing (pre-poll):", emptyMap())
                    blobsBase.listFiles()?.forEach { f -> betaLogger.log(LogLevel.DEBUG, TAG, " - ${f.name} -> abs=${f.absolutePath} canon=${try { f.canonicalPath } catch (e: Exception) { "<err:${e.message}>" } } len=${f.length()} lm=${f.lastModified()}") }
                } else {
                    betaLogger.log(LogLevel.DEBUG, TAG, "handleStore: blobsBase does not exist (yet)", emptyMap())
                }

                val expectedFiles = listOf(
                    java.io.File(blobsBase, "$blobId.blob"),
                    java.io.File(blobsBase, "$blobId.json"),
                    java.io.File(blobsBase, "$blobId.repl.json")
                )

                // Short polling loop to detect transient visibility races. This is
                // intentionally short to avoid changing production behavior much but
                // long enough to catch quick ordering races under Robolectric.
                val maxPollMs = 500L
                val pollIntervalMs = 50L
                var elapsed = 0L
                while (elapsed <= maxPollMs && expectedFiles.any { !it.exists() }) {
                    Thread.sleep(pollIntervalMs)
                    elapsed += pollIntervalMs
                }

                betaLogger.log(LogLevel.DEBUG, TAG, "handleStore: post-poll blobsBase listing:", emptyMap())
                blobsBase.listFiles()?.forEach { f -> betaLogger.log(LogLevel.DEBUG, TAG, " + ${f.name} -> abs=${f.absolutePath} canon=${try { f.canonicalPath } catch (e: Exception) { "<err:${e.message}>" } } len=${f.length()} lm=${f.lastModified()}") }

                expectedFiles.forEach { ef ->
                    try {
                        betaLogger.log(LogLevel.DEBUG, TAG, "handleStore: expected=${ef.name} exists=${ef.exists()} abs=${ef.absolutePath} canon=${ef.canonicalPath} len=${if (ef.exists()) ef.length() else -1}")
                    } catch (e: Exception) {
                        betaLogger.log(LogLevel.WARN, TAG, "handleStore: expected=${ef.name} check failed: ${e}", emptyMap(), e)
                    }
                }
                } catch (diagEx: Exception) {
                betaLogger.log(LogLevel.WARN, TAG, "handleStore: diagnostic check failed: ${diagEx}", emptyMap(), diagEx)
            }
            val resp = JSONObject()
            resp.put("blobId", blobId)
            // Ensure the server indicates the connection will be closed; some clients
            // (especially in test environments) behave better when the server closes
            // the connection after the response is sent.
            val body = resp.toString()
            val r = newFixedLengthResponse(Response.Status.OK, "application/json", body)
            r.addHeader("Connection", "close")
            r.addHeader("Content-Length", body.toByteArray(Charsets.UTF_8).size.toString())
            betaLogger.log(LogLevel.DEBUG, TAG, "handleStore: responding with body=$body", emptyMap())
            return r
        } catch (ex: Exception) {
            Log.e(TAG, "Request handling failed", ex)
            ex.printStackTrace()
            val err = JSONObject()
            err.put("error", "internal")
            err.put("ex", ex.toString())
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", err.toString())
        } finally {
            try { pfd?.close() } catch (_: Exception) {}
            try { tempFile?.delete() } catch (_: Exception) {}
        }
    }

    private fun handleDescriptor(session: IHTTPSession): Response {
        // Assume descriptor JSON body
        val baos = ByteArrayOutputStream()
        session.inputStream.use { input ->
            val buf = ByteArray(8 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                baos.write(buf, 0, read)
            }
        }
        val body = baos.toString(Charsets.UTF_8.name())

        // For now, simply create an acceptance response. In a full implementation
        // this would perform peer discovery and obtain an upload token or direct acceptance.
        val descriptorId = UUID.randomUUID().toString()
        val resp = JSONObject()
        resp.put("descriptorId", descriptorId)
        resp.put("accepted", true)
        resp.put("note", "stub acceptance: producer should upload to returned endpoint if provided")
        try {
            // Provide a recommended upload endpoint and token so local clients can
            // perform identical HTTP upload semantics as remote peers.
            val port = try { this.getListeningPort() } catch (_: Exception) { -1 }
            if (port > 0) {
                val endpoint = "http://127.0.0.1:${port}/store"
                resp.put("upload_endpoint", endpoint)
            }
            val token = try { service.getLocalAuthTokenInternal() } catch (_: Exception) { null }
            if (!token.isNullOrEmpty()) resp.put("token", token)
        } catch (_: Exception) {
            // best-effort; do not fail descriptor acceptance if we can't provide extras
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }
}
