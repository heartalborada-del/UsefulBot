package me.heartalborada

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryableDeliveryGateTest {
    @Test
    fun `delivery is emitted once until a failed attempt is released`() {
        val gate = RetryableDeliveryGate()

        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())

        gate.release()

        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
    }
}
