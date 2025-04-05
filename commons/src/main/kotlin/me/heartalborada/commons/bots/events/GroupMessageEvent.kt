package me.heartalborada.commons.bots.events

import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.beans.UserInfo

data class GroupMessageEvent(
    val botID: Long,
    val timestamp: Long,
    val groupID: Long,
    val sender: UserInfo,
    val message: MessageChain,
    val messageID: Long,
) : AbstractEvent()
