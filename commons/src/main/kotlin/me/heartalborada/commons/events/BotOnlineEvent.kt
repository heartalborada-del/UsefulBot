package me.heartalborada.commons.events

data class BotOnlineEvent(
    val botID: Long,
    val timestamp: Long
): AbstractEvent()
