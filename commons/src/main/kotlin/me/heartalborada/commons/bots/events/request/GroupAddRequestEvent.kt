package me.heartalborada.commons.bots.events.request

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** Incoming group join or bot invitation request. */
@SupportedBotTypes(BotType.NAPCAT)
class GroupAddRequestEvent(
    override val botID: Long,
    override val timestamp: Long,
    val groupID: Long,
    val userID: Long,
    val action: ActionType,
    val comment: String,
    val requestFlag: String = "",
) : AbstractEvent(), BotEvent {
    enum class ActionType {
        ADD,
        INVITE
    }
}
