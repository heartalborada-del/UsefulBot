package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** Private message recall notification. */
@SupportedBotTypes(BotType.NAPCAT)
class PrivateRecallEvent(
    override val botID: Long,
    override val timestamp: Long,
    val senderID: Long,
    val messageID: Long,
) : AbstractEvent(), BotEvent
