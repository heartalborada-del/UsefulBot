package me.heartalborada.commons.bots.events

import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.beans.UserInfo

class PrivateMessageEvent(
    val botID: Long,
    val timestamp: Long,
    val sender: UserInfo,
    val message: MessageChain,
    val messageID: Long,
) : AbstractEvent()