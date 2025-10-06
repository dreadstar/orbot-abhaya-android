package com.ustadmobile.meshrabiya.service

/**
 * TorSignerBridge
 *
 * This file contains a TODO scaffold for integrating with Tor's native signing
 * capabilities. There are a few possible approaches:
 *  - Call Tor ControlPort commands or a helper process that exposes a signing RPC.
 *  - Expose a JNI bridge into the Tor library that can access the hidden service
 *    key material and perform signatures on-demand.
 *
 * Security note: prefer designs that never export private key bytes into Java
 * memory. Instead request Tor to produce the signature and return only the
 * signature bytes to the Java layer.
 *
 * Implementation guidance:
 *  - If using ControlPort: ensure authenticated connection and restrict allowed
 *    commands; implement a small helper or Tor plugin that accepts a signing
 *    request and returns signature bytes.
 *  - If using JNI: implement a native method that accepts a byte[] and returns
 *    a byte[] signature. Keep native code minimal and audit memory handling.
 */

object TorSignerBridge {
    /**
     * signWithTor: request a signature from Tor-native components. Must be
     * implemented by native/ControlPort bridge. Returns null if not available
     * or on error.
     */
    fun signWithTor(data: ByteArray): ByteArray? {
        // TODO: implement native or control-port based signing. For now, return null.
        return null
    }
}
