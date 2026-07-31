package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** Group poke notification from [userID] to [target]. */
@SupportedBotTypes(BotType.NAPCAT)
data class GroupPokeEvent(
    override val botID: Long,
    override val timestamp: Long,
    val groupID: Long,
    val userID: Long,
    val target: Long,
) : AbstractEvent(), BotEvent
