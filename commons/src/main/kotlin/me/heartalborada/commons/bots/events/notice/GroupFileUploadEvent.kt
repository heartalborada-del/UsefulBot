package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.dto.FileInfo
import me.heartalborada.commons.bots.events.AbstractEvent
import me.heartalborada.commons.bots.events.BotEvent

/** File uploaded to a group. */
@SupportedBotTypes(BotType.NAPCAT)
class GroupFileUploadEvent(
    override val botID: Long,
    override val timestamp: Long,
    val groupID: Long,
    val senderID: Long,
    val file: FileInfo,
) : AbstractEvent(), BotEvent
