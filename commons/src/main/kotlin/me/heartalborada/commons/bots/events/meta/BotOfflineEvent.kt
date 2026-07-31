package me.heartalborada.commons.bots.events.meta

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** Adapter connection ended after it had reached the online state. */
@SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
data class BotOfflineEvent(
    override val botID: Long,
    override val timestamp: Long,
    /** True for an explicit [me.heartalborada.commons.bots.AbstractBot.close] call. */
    val expected: Boolean,
    val reason: String? = null,
) : AbstractEvent(), BotEvent
