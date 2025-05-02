package me.heartalborada.commons.bots.events.meta

import me.heartalborada.commons.bots.events.AbstractEvent

data class HeartBeatEvent(
    val online: Boolean,
    val good: Boolean,
    val botID: Long,
    val timestamp: Long
) : AbstractEvent()