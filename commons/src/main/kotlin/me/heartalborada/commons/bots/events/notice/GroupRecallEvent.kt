package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.events.AbstractEvent

class GroupRecallEvent(
    val botID: Long,
    val timestamp: Long,
    val groupID: Long,
    val senderID: Long,
    val operatorId: Long,
    val messageID: Long,
) : AbstractEvent()