package me.heartalborada.commons.bots.events.message

import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.dto.UserInfo
import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.MessageEvent

/** Message received in a group chat. */
@SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
data class GroupMessageEvent(
    override val botID: Long,
    override val timestamp: Long,
    val groupID: Long,
    override val sender: UserInfo,
    override val message: MessageChain,
    override val messageID: Long,
) : AbstractEvent(), MessageEvent
