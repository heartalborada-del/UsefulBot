package me.heartalborada.commons.bots.events

data class BotOnlineEvent(
    val botID: Long,
    val timestamp: Long
): AbstractEvent()
