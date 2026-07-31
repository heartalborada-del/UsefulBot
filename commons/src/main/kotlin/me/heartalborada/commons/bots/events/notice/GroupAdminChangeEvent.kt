package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** Group administrator role change. */
@SupportedBotTypes(BotType.NAPCAT)
class GroupAdminChangeEvent(
    override val botID: Long,
    override val timestamp: Long,
    val groupID: Long,
    val userID: Long,
    val action: ActionType,
) : AbstractEvent(), BotEvent {
    enum class ActionType {
        ADD,
        REMOVE
    }
}
