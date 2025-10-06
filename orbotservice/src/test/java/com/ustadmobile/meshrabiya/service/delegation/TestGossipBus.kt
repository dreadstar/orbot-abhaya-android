
package com.ustadmobile.meshrabiya.service.delegation

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Simple in-memory gossip bus for tests. Peers can register listeners and publish messages.
 * This is intentionally minimal and synchronous for ease of unit testing.
 */
class TestGossipBus : GossipBus {
    private val listeners = CopyOnWriteArrayList<(Any) -> Unit>()

    override fun publish(msg: Any) {
        // deliver to all listeners
        listeners.forEach { it.invoke(msg) }
    }

    override fun subscribe(listener: (Any) -> Unit) {
        listeners.add(listener)
    }

    override fun unsubscribe(listener: (Any) -> Unit) {
        listeners.remove(listener)
    }
}
