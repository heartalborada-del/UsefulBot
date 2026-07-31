package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** Member departure, removal, or removal of the bot itself. */
@SupportedBotTypes(BotType.NAPCAT)
class GroupMemberDecreaseEvent(
    override val botID: Long,
    override val timestamp: Long,
    val groupID: Long,
    val userID: Long,
    val operatorID: Long,
    val action: ActionType,
) : AbstractEvent(), BotEvent {
    enum class ActionType {
        LEAVE,
        KICK,
        KICK_BOT
    }
}
