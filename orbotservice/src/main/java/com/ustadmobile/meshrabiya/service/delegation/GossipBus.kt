package com.ustadmobile.meshrabiya.service.delegation

/**
 * Minimal gossip bus interface used for test injection and later pluggable transports.
 */
interface GossipBus {
    fun publish(msg: Any)
    fun subscribe(listener: (Any) -> Unit)
    fun unsubscribe(listener: (Any) -> Unit)
}
