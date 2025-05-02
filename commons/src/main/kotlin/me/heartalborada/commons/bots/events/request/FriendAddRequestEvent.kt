package me.heartalborada.commons.bots.events.request

import me.heartalborada.commons.bots.events.AbstractEvent

class FriendAddRequestEvent(
    val botID: Long,
    val timestamp: Long,
    val userID: Long,
    val comment: String
) : AbstractEvent()