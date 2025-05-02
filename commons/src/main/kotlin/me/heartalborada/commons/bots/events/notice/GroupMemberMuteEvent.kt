package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.events.AbstractEvent

class GroupMemberMuteEvent(
    val botID: Long,
    val timestamp: Long,
    val groupID: Long,
    val userID: Long,
    val operatorID: Long,
    val action: ActionType,
    val duration: Long,
) : AbstractEvent() {
    enum class ActionType {
        BAN,
        PARDON
    }
}