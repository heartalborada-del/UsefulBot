package me.heartalborada.commons.bots.events.message

import me.heartalborada.commons.bots.dto.UserInfo
import me.heartalborada.commons.bots.events.AbstractEvent

data class InlineQueryEvent(
    val botID: Long,
    val sender: UserInfo,
    val queryID: String,
    val query: String,
    val offset: String,
) : AbstractEvent()
