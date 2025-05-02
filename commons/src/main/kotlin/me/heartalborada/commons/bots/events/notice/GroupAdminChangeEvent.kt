package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.events.AbstractEvent

class GroupAdminChangeEvent(
    val botID: Long,
    val timestamp: Long,
    val groupID: Long,
    val userID: Long,
    val action: ActionType,
) : AbstractEvent() {
    enum class ActionType {
        ADD,
        REMOVE
    }
}