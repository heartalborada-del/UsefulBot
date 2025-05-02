package me.heartalborada.commons.bots.events.notice

import me.heartalborada.commons.bots.beans.FileInfo
import me.heartalborada.commons.bots.events.AbstractEvent

class GroupFileUploadEvent(
    val botID: Long,
    val timestamp: Long,
    val groupID: Long,
    val senderID: Long,
    val file: FileInfo,
) : AbstractEvent()