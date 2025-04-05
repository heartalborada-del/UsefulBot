package me.heartalborada.commons.bots.events

data class HeartBeatEvent(
    val online: Boolean,
    val good: Boolean,
    val botID: Long,
    val timestamp: Long
) : AbstractEvent()