package me.heartalborada.commons.bots.events.message

import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.dto.UserInfo
import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** Interactive button callback that may be intercepted before command dispatch. */
@SupportedBotTypes(BotType.TELEGRAM)
data class CallbackQueryEvent(
    override val botID: Long,
    override val timestamp: Long,
    val queryID: String,
    val sender: UserInfo,
    val data: String,
    val chatType: ChatType,
    val chatID: Long,
    val messageID: Long,
) : AbstractEvent(), BotEvent
