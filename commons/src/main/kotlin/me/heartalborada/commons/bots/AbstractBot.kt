package me.heartalborada.commons.bots

import me.heartalborada.commons.bots.events.EventBus
import me.heartalborada.commons.bots.events.GroupMessageEvent
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.beans.FileInfo

abstract class AbstractBot {
    init {
        registerCommandEvent()
    }

    private val commandMap = mutableMapOf<String, CommandExecutor>()

    abstract fun close(): Boolean

    abstract fun connect(): Boolean

    abstract fun getEventBus(): EventBus

    abstract suspend fun sendMessage(type: ChatType, id: Long, message: MessageChain): Boolean

    abstract suspend fun recallMessage(messageID: Long): Boolean

    abstract suspend fun getMessageByID(messageID: Long): MessageChain?

    abstract suspend fun getImageUrlByID(imageID: String): String?

    abstract suspend fun getFileByID(fileID: String): FileInfo?
    // 注册命令及其对应的执行器
    fun registerCommand(vararg commands: String, executor: CommandExecutor) {
        for (command in commands) {
            // 如果命令已经注册，则抛出异常
            if (commandMap.containsKey(command)) {
                throw IllegalArgumentException("Command $command is already registered.")
            }
            commandMap[command] = executor
        }
    }
    // 取消注册命令
    fun unregisterCommand(vararg commands: String) {
        for (command in commands) {
            // 如果命令不存在，则抛出异常
            if (!commandMap.containsKey(command)) {
                throw IllegalArgumentException("Command $command is not registered.")
            }
            commandMap.remove(command)
        }
    }
    // 注册命令事件
    private fun registerCommandEvent(isStartWithAtBot: Boolean = true, operator: Char? = null, divider: Char = ' ') {
        this.getEventBus().register(GroupMessageEvent::class.java) {
            // 如果消息不是以@机器人开头，则返回
            if (isStartWithAtBot && (it.message[0] as? At)?.target != it.botID) return@register
            val copy = MessageChain()
            copy.addAll(it.message.toMutableList())
            if (isStartWithAtBot) copy.removeAt(0)
            commandParser(ChatType.GROUP, copy, operator, divider)
        }
    }

    // 解析命令并执行对应的命令执行器
    private fun commandParser(sender: ChatType, messageChain: MessageChain, operator: Char?, divider: Char): Boolean {
        if (messageChain.size > 0 && messageChain[0] is PlainText) {
            val firstText = (messageChain[0] as PlainText).text.trim()
            val mainCommand = firstText.split(divider)[0]
            // 如果命令不以指定的操作符开头，则返回
            if (operator != null && firstText[0] != operator) return false
            // 如果命令为空或不在命令映射中，则返回
            if (firstText == "" || mainCommand == "" || !commandMap.containsKey(mainCommand)) return false
            val newMsgChain = MessageChain()
            messageChain.forEach {
                if (it is PlainText) {
                    it.text.trim().split(divider).forEach { its ->
                        if (its.isNotEmpty()) {
                            newMsgChain.add(PlainText(its))
                        }
                    }
                } else {
                    newMsgChain.add(it)
                }
            }
            // 执行对应的命令执行器
            commandMap[mainCommand]?.execute(sender, mainCommand, newMsgChain)
        }
        return true
    }
}
