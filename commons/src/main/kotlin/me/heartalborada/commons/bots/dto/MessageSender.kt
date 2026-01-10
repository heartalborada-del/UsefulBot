package me.heartalborada.commons.bots.dto

import me.heartalborada.commons.ChatType


data class MessageSender(
    val target: Long,
    val user: UserInfo,
    val type: ChatType,
)