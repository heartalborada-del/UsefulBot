package me.heartalborada.commons.bots.events.message

import me.heartalborada.commons.bots.dto.UserInfo
import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** Platform-native inline search query. */
@SupportedBotTypes(BotType.TELEGRAM)
data class InlineQueryEvent(
    override val botID: Long,
    val sender: UserInfo,
    val queryID: String,
    val query: String,
    val offset: String,
    override val timestamp: Long = System.currentTimeMillis() / 1_000,
) : AbstractEvent(), BotEvent
