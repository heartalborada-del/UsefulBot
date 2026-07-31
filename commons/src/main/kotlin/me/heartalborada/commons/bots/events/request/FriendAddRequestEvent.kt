package me.heartalborada.commons.bots.events.request

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** Incoming friend request. [requestFlag] is the platform token used to answer it. */
@SupportedBotTypes(BotType.NAPCAT)
class FriendAddRequestEvent(
    override val botID: Long,
    override val timestamp: Long,
    val userID: Long,
    val comment: String,
    val requestFlag: String = "",
) : AbstractEvent(), BotEvent
