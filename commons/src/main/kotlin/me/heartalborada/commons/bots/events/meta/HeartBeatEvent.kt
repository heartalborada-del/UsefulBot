package me.heartalborada.commons.bots.events.meta

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** OneBot heartbeat and connection health snapshot. */
@SupportedBotTypes(BotType.NAPCAT)
data class HeartBeatEvent(
    val online: Boolean,
    val good: Boolean,
    override val botID: Long,
    override val timestamp: Long,
) : AbstractEvent(), BotEvent
