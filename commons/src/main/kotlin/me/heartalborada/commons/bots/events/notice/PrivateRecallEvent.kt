package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.events.AbstractEvent

class PrivateRecallEvent(
    val botID: Long,
    val timestamp: Long,
    val senderID: Long,
    val messageID: Long,
) : AbstractEvent()