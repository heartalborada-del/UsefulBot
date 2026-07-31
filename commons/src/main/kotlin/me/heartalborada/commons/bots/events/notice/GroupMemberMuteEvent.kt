package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** Member mute or unmute; [duration] is expressed in seconds. */
@SupportedBotTypes(BotType.NAPCAT)
class GroupMemberMuteEvent(
    override val botID: Long,
    override val timestamp: Long,
    val groupID: Long,
    val userID: Long,
    val operatorID: Long,
    val action: ActionType,
    val duration: Long,
) : AbstractEvent(), BotEvent {
    enum class ActionType {
        BAN,
        PARDON
    }
}
