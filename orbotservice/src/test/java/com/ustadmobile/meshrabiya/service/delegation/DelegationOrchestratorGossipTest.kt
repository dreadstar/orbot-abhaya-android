package com.ustadmobile.meshrabiya.service.delegation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DelegationOrchestratorGossipTest {

    @Test
    fun solicitOffers_receives_offers_from_test_bus() = runBlocking {
        val bus = TestGossipBus()
        val orch = DelegationOrchestrator(RuntimeEnvironment.getApplication(), bus)

        // Simulate a peer that listens for ResourceRequest and replies with an offer.
        bus.subscribe { msg ->
            if (msg is ResourceRequest) {
                // publish an offer referencing the same requestId
                val offer = ResourceOffer(msg.requestId, "peer-onion", 1000L, 2000L)
                bus.publish(offer)
            }
        }

        val req = ResourceRequest("r-gossip", "blob-gossip", 1024L, "onion:me", 1)
        val offers = orch.solicitOffers(req, 500)

        // The in-memory bus should cause one offer to be collected
        assertEquals(1, offers.size)
        assertEquals("peer-onion", offers[0].offererId)
    }
}
