package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.events.AbstractEvent

class FriendAddEvent(
    val botID: Long,
    val timestamp: Long,
    val userID: Long,
) : AbstractEvent()