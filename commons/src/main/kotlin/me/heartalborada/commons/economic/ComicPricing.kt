package me.heartalborada.commons.economic

import java.math.BigInteger

object ComicPricing {
    private val JM_RATE_NUMERATOR = BigInteger.valueOf(11)
    private val JM_RATE_DENOMINATOR = BigInteger.valueOf(10L * 1024 * 1024)

    /**
     * Charges 1.1 GP per MiB of the final PDF, rounded down.
     */
    fun jmPdfCost(fileSizeBytes: Long): Long {
        require(fileSizeBytes >= 0) { "PDF file size must not be negative." }
        return BigInteger.valueOf(fileSizeBytes)
            .multiply(JM_RATE_NUMERATOR)
            .divide(JM_RATE_DENOMINATOR)
            .toLong()
    }
}
