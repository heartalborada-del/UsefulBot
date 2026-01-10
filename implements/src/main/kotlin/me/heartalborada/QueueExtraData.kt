package me.heartalborada

import me.heartalborada.commons.bots.dto.MessageSender

data class QueueExtraData(
    val messageID: Long,
    val sender: MessageSender
)