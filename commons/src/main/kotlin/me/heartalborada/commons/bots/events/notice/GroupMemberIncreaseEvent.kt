package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.events.AbstractEvent

class GroupMemberIncreaseEvent(
    val botID: Long,
    val timestamp: Long,
    val groupID: Long,
    val userID: Long,
    val operatorID: Long,
    val action: ActionType,
) : AbstractEvent() {
    enum class ActionType {
        APPROVE,
        INVITE
    }
}