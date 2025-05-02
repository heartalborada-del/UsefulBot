package me.heartalborada

import me.heartalborada.commons.bots.beans.MessageSender

data class QueueExtraData(
    val messageID: Long,
    val sender: MessageSender
)