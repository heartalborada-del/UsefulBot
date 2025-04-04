package me.heartalborada.commons.bots.beans

import me.heartalborada.commons.ChatType


data class MessageSender(
    val group: Long,
    val user: UserInfo,
    val type: ChatType,
)