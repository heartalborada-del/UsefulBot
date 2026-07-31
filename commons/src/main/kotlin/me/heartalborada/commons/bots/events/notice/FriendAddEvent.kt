package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** Notification that a friend request was accepted. */
@SupportedBotTypes(BotType.NAPCAT)
class FriendAddEvent(
    override val botID: Long,
    override val timestamp: Long,
    val userID: Long,
) : AbstractEvent(), BotEvent
