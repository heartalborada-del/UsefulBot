package me.heartalborada.commons.commands

import me.heartalborada.commons.bots.dto.MessageSender

fun interface CommandErrorHandler {
    fun handle(sender: MessageSender, operation: String, messageID: Long, error: Throwable): String
}
