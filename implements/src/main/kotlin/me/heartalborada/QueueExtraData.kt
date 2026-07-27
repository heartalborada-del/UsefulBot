package me.heartalborada

import me.heartalborada.commons.bots.AbstractBot
import me.heartalborada.commons.bots.dto.MessageSender

data class QueueExtraData(
    val messageID: Long,
    val sender: MessageSender,
    val bot: AbstractBot,
    val blurImages: Boolean,
)

data class QueueUser(
    val bot: AbstractBot,
    val userID: Long,
)
