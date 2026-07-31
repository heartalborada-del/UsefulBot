package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** OneBot red-packet notification identifying the sender and lucky recipient. */
@SupportedBotTypes(BotType.NAPCAT)
data class GroupLuckyKingEvent(
    override val botID: Long,
    override val timestamp: Long,
    val groupID: Long,
    val userID: Long,
    val targetID: Long,
) : AbstractEvent(), BotEvent
