package me.heartalborada.commons.economic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComicPricingTest {
    @Test
    fun `JM PDF cost uses MiB times 1 point 1 rounded down`() {
        val mib = 1024L * 1024

        assertEquals(0, ComicPricing.jmPdfCost(0))
        assertEquals(0, ComicPricing.jmPdfCost(mib / 2))
        assertEquals(1, ComicPricing.jmPdfCost(mib - 1))
        assertEquals(1, ComicPricing.jmPdfCost(mib))
        assertEquals(10, ComicPricing.jmPdfCost(10 * mib - 1))
        assertEquals(11, ComicPricing.jmPdfCost(10 * mib))
    }

    @Test
    fun `JM PDF cost rejects negative sizes`() {
        assertFailsWith<IllegalArgumentException> {
            ComicPricing.jmPdfCost(-1)
        }
    }
}
