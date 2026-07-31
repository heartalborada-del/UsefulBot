package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** Group message recall notification. */
@SupportedBotTypes(BotType.NAPCAT)
class GroupRecallEvent(
    override val botID: Long,
    override val timestamp: Long,
    val groupID: Long,
    val senderID: Long,
    val operatorId: Long,
    val messageID: Long,
) : AbstractEvent(), BotEvent
