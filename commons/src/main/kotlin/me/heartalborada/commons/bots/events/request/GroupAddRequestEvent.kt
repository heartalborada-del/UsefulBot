package me.heartalborada.commons.bots.events.request

import me.heartalborada.commons.bots.events.AbstractEvent

class GroupAddRequestEvent(
    val botID: Long,
    val timestamp: Long,
    val groupID: Long,
    val userID: Long,
    val action: ActionType,
    val comment: String
) : AbstractEvent() {
    enum class ActionType {
        ADD,
        INVITE
    }
}