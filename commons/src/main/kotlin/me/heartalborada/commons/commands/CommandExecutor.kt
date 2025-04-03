package me.heartalborada.commons.commands

import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.MessageChain

interface CommandExecutor {
    fun execute(sender: ChatType, command: String, args: MessageChain)
}
