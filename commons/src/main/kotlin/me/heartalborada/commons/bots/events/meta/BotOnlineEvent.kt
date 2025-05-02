package me.heartalborada.commons.bots.events.meta

import me.heartalborada.commons.bots.events.AbstractEvent

data class BotOnlineEvent(
    val botID: Long,
    val timestamp: Long
) : AbstractEvent()