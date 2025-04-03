package me.heartalborada.commons.bots.events

import me.heartalborada.commons.bots.beans.UserInfo
import me.heartalborada.commons.bots.MessageChain

data class GroupMessageEvent(
    val botID: Long,
    val timestamp: Long,
    val groupID: Long,
    val sender: UserInfo,
    val message: MessageChain,
): AbstractEvent()
