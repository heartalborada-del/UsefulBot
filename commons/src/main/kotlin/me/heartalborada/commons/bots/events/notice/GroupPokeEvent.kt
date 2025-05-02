package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.events.AbstractEvent

data class GroupPokeEvent(
    val botID: Long,
    val timestamp: Long,
    val groupID: Long,
    val userID: Long,
    val target: Long,
) : AbstractEvent()
