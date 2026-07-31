package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** OneBot group honor change; [honorType] retains the platform value for forward compatibility. */
@SupportedBotTypes(BotType.NAPCAT)
data class GroupHonorChangeEvent(
    override val botID: Long,
    override val timestamp: Long,
    val groupID: Long,
    val userID: Long,
    val honorType: String,
) : AbstractEvent(), BotEvent
