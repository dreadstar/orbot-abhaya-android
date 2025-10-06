package com.ustadmobile.meshrabiya.service

import android.content.Context
import android.util.Log
import android.util.Base64
import java.security.KeyPair
import java.util.concurrent.atomic.AtomicReference

/**
 * SigningMediator provides a pluggable signing backend model.
 * Backends can be implemented to either load key material from disk
 * or call into native Tor APIs / Android Keystore for signing.
 */
class SigningMediator private constructor(private val ctx: Context) {

    companion object {
        private val instanceRef = AtomicReference<SigningMediator?>()

        fun getInstance(ctx: Context): SigningMediator {
            var inst = instanceRef.get()
            if (inst == null) {
                inst = SigningMediator(ctx.applicationContext)
                instanceRef.set(inst)
            }
            return inst
        }
    }

    private val backendRef = AtomicReference<SignerBackend?>(null)

    init {
        // Prefer Tor-native signer backend if available. Add the stub now.
        val torBackend = TorSignerBackend.createIfAvailable(ctx)
        if (torBackend != null) {
            backendRef.set(torBackend)
            Log.i("SigningMediator", "Using TorSignerBackend for signing")
        } else {
            // We keep a fallback file-backed signer implementation, but do not enable
            // it by default to avoid accidental exposure. It can be enabled later.
            // backendRef.set(FallbackFileSignerBackend(ctx))
            Log.i("SigningMediator", "No Tor signer backend available; signing disabled until backend implemented")
        }
    }

    fun getKeyPair(): KeyPair? {
        val backend = backendRef.get() ?: return null
        return backend.getKeyPair()
    }

    fun sign(data: ByteArray): ByteArray? {
        val backend = backendRef.get() ?: return null
        return backend.sign(data)
    }

    /**
     * Return the public key bytes encoded as compact Base64 (no-wrap) when available.
     * Backends that do not expose a KeyPair will cause this to return null.
     */
    fun getPublicKeyBase64(): String? {
        val kp = getKeyPair() ?: return null
        return try {
            Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w("SigningMediator", "Failed to encode public key to base64", e)
            null
        }
    }
}

/**
 * SignerBackend describes a minimal signing backend interface.
 * Implementations must not expose raw private key bytes to callers.
 */
interface SignerBackend {
    fun getKeyPair(): KeyPair?
    fun sign(data: ByteArray): ByteArray?
}

/**
 * TorSignerBackend is a placeholder/stub for a backend that would call into
 * native Tor or a trusted signer component. The actual implementation will
 * depend on the Tor build and available control APIs or JNI hooks.
 *
 * For now it returns null from createIfAvailable to indicate not present.
 */
class TorSignerBackend private constructor(private val ctx: Context) : SignerBackend {
    companion object {
        fun createIfAvailable(ctx: Context): TorSignerBackend? {
            // TODO: detect availability of native Tor signing API or ControlPort
            // For now return null so mediator remains disabled until implemented.
            return null
        }
    }

    override fun getKeyPair(): KeyPair? {
        // Native/Tor implementations may choose to return a KeyPair wrapper
        // that does not expose private bytes, or return null and only allow sign().
        return null
    }

    override fun sign(data: ByteArray): ByteArray? {
        // TODO: call into native/Tor signing bridge and return signature bytes
        return null
    }
}

/**
 * FallbackFileSignerBackend is an optional helper that attempts to read onion
 * key files from disk and build a KeyPair. It's intentionally left out of the
 * default mediator initialization for security reasons — only enable it if the
 * project decides file-based loading is acceptable.
 */
class FallbackFileSignerBackend(private val ctx: Context) : SignerBackend {
    override fun getKeyPair(): KeyPair? {
        // ...existing file-loader implementation could be moved here...
        return null
    }

    override fun sign(data: ByteArray): ByteArray? {
        val kp = getKeyPair() ?: return null
        try {
            val signer = java.security.Signature.getInstance("Ed25519")
            signer.initSign(kp.private)
            signer.update(data)
            return signer.sign()
        } catch (e: Exception) {
            Log.w("FallbackFileSignerBackend", "Signing failed", e)
            return null
        }
    }
}

