package me.heartalborada.commons.bots

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.heartalborada.commons.ActionResponse
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.beans.FileInfo
import me.heartalborada.commons.bots.beans.MessageSender
import me.heartalborada.commons.bots.events.EventBus
import me.heartalborada.commons.bots.events.GroupMessageEvent
import me.heartalborada.commons.bots.events.PrivateMessageEvent
import me.heartalborada.commons.commands.CommandExecutor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

abstract class AbstractBot(
    private val isCommandStartWithAt: Boolean = true,
    private val commandOperator: Char = '/',
    private val commandDivider: Char = ' '
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val commonScope = CoroutineScope(CoroutineName("BotExecutorScope"))

    private val commandMap = mutableMapOf<String, CommandExecutor>()
    private var isRegistered: Boolean = false

    init {
        registerCommand(
            commands = arrayOf("help", "h"),
            executor = object : CommandExecutor {
                override suspend fun execute(
                    sender: MessageSender,
                    command: String,
                    args: MessageChain,
                    messageID: Long
                ) {
                    sendMessage(
                        sender.type,
                        sender.target,
                        MessageChain().also {
                            val builder = StringBuilder("Commands: \n")
                            commandMap.forEach { (key) ->
                                builder.append("  $commandOperator$key\n")
                            }
                            it.add(PlainText(builder.toString().trimIndent()))
                        }
                    )
                }
            }
        )
    }
    open fun close(): Boolean {
        commonScope.cancel()
        commandMap.clear()
        return true
    }

    open fun connect(): Boolean {
        if (!isRegistered)
            registerCommandEvent(
                operator = commandOperator,
                isStartWithAtBot = isCommandStartWithAt,
                divider = commandDivider
            )
        return true
    }

    abstract fun getEventBus(): EventBus

    abstract fun sendMessage(type: ChatType, id: Long, message: MessageChain): Long

    abstract fun recallMessage(messageID: Long): Boolean

    abstract fun sendFile(type: ChatType, id: Long, fileInfo: FileInfo): Long

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
            logger.info(
                "[Receive] {} <- [GROUP] [{}] [{}] {}",
                it.botID,
                it.groupID,
                it.sender.userID,
                it.message.toString()
            )
            // 如果消息不是以@机器人开头，则返回
            if (isStartWithAtBot && (it.message[0] as? At)?.target != it.botID) return@register
            val copy = MessageChain()
            copy.addAll(it.message.toMutableList())
            if (isStartWithAtBot) copy.removeAt(0)
            commandParser(MessageSender(it.groupID, it.sender, ChatType.GROUP), copy, operator, divider,it.messageID)
        }
        this.getEventBus().register(PrivateMessageEvent::class.java) {
            logger.info("[Receive] {} <- [PRIVATE] [{}] {}", it.botID, it.sender.userID, it.message.toString())
            commandParser(MessageSender(it.sender.userID, it.sender, ChatType.PRIVATE), it.message, operator, divider,it.messageID)
        }
        isRegistered = true
    }

    // 解析命令并执行对应的命令执行器
    private fun commandParser(
        sender: MessageSender,
        messageChain: MessageChain,
        operator: Char?,
        divider: Char,
        messageID: Long
    ): Boolean {
        if (messageChain.size > 0 && messageChain[0] is PlainText) {
            val firstText = (messageChain[0] as PlainText).text.trim()
            var mainCommand = firstText.split(divider)[0].lowercase(Locale.getDefault())
            if (operator != null) mainCommand = mainCommand.removePrefix(operator.toString())
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
            commonScope.launch {
                commandMap[mainCommand]?.execute(sender, mainCommand, newMsgChain, messageID)
            }
        }
        return true
    }
}
