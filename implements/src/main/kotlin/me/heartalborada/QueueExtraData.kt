package me.heartalborada

import me.heartalborada.commons.bots.AbstractBot
import me.heartalborada.commons.bots.dto.MessageSender
import java.util.concurrent.atomic.AtomicBoolean

internal class RetryableDeliveryGate {
    private val delivered = AtomicBoolean(false)

    fun tryAcquire(): Boolean = delivered.compareAndSet(false, true)

    fun release() {
        delivered.set(false)
    }
}

data class QueueExtraData(
    val messageID: Long,
    val sender: MessageSender,
    val bot: AbstractBot,
    val blurImages: Boolean,
) {
    private val comicInformationDelivery = RetryableDeliveryGate()

    internal fun tryStartComicInformationDelivery(): Boolean =
        comicInformationDelivery.tryAcquire()

    internal fun retryComicInformationDelivery() {
        comicInformationDelivery.release()
    }
}

data class QueueUser(
    val bot: AbstractBot,
    val userID: Long,
)
