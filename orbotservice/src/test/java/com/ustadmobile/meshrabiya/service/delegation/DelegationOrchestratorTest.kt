package com.ustadmobile.meshrabiya.service.delegation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DelegationOrchestratorTest {

    @Test
    fun solicitOffers_defaults_empty() = runBlocking {
        val orch = DelegationOrchestrator(RuntimeEnvironment.getApplication())
        val req = ResourceRequest("r1", "b1", 10L, "onion:me", 1)
        val offers = orch.solicitOffers(req, 100)
        assertTrue(offers.isEmpty())
    }

    @Test
    fun selectOffers_orders_by_bandwidth_and_space() {
        val orch = DelegationOrchestrator(RuntimeEnvironment.getApplication())
        val offers = listOf(
            ResourceOffer("r1", "a", 100L, 1000L),
            ResourceOffer("r1", "b", 200L, 500L),
            ResourceOffer("r1", "c", 50L, 2000L)
        )
        val selected = orch.selectOffers(offers, 2)
        // highest bandwidth first: c(2000), a(1000), b(500) so c then a
        assertEquals(2, selected.size)
        assertEquals("c", selected[0].offererId)
        assertEquals("a", selected[1].offererId)
    }
}
