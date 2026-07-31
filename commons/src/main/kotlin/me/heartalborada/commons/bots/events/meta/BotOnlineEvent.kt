package me.heartalborada.commons.bots.events.meta

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** Adapter connection completed and the bot identity is available. */
@SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
data class BotOnlineEvent(
    override val botID: Long,
    override val timestamp: Long,
) : AbstractEvent(), BotEvent
