package me.heartalborada.commons.commands

import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.dto.MessageSender

fun interface CommandGuard {
    fun allow(sender: MessageSender, command: String, args: MessageChain, messageID: Long): Boolean
}
