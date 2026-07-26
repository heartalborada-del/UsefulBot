package me.heartalborada.commons.bots

import kotlinx.coroutines.*
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.dto.FileInfo
import me.heartalborada.commons.bots.dto.MessageSender
import me.heartalborada.commons.bots.events.EventBus
import me.heartalborada.commons.bots.events.message.GroupMessageEvent
import me.heartalborada.commons.bots.events.message.PrivateMessageEvent
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.i18n.Translator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale

abstract class AbstractBot(
    private val commandStartWithAt: Boolean = true,
    private val commandOperator: Char = '/',
    private val commandDivider: Char = ' ',
    protected val translator: Translator = Translator(),
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.error("An unexpected error occurred.", throwable)
    }
    private val commonScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler + CoroutineName("BotExecutorScope"))

    private class CommandDefinition(
        val executor: CommandExecutor,
        val usage: String,
    )

    private val commandMap = linkedMapOf<String, CommandDefinition>()
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
                    val definitions = commandMap.values.distinct()
                    val helpText = buildString {
                        appendLine(translator.translate("command.help.header"))
                        definitions.forEach { definition ->
                            val names = commandMap
                                .filterValues { it === definition }
                                .keys
                                .joinToString(", ") { "$commandOperator$it" }
                            append("  $names — ${definition.usage}")
                            if (definition !== definitions.last()) appendLine()
                        }
                    }
                    reply(sender, messageID, helpText)
                }
            },
            usage = translator.translate("command.help.usage")
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
                isStartWithAtBot = commandStartWithAt,
                divider = commandDivider
            )
        return true
    }

    abstract fun getEventBus(): EventBus

    abstract fun sendMessage(type: ChatType, id: Long, message: MessageChain): Long

    abstract fun recallMessage(messageID: Long): Boolean

    /**
     * @param type ChatType
     * @param target Long
     * @param url <bold>base64</bold> or <bold>local file relative path</bold> with scheme
     * @throws IllegalArgumentException when uploadActionType is STREAMAPI
     * @author heartalborada-del
     */
    abstract fun sendFile(type: ChatType, target: Long, name: String, url: String): Boolean

    /**
     * @param type ChatType
     * @param target Long
     * @param file File
     */
    abstract fun sendFile(type: ChatType, target: Long, name: String, file: File): Boolean

    fun registerCommand(vararg commands: String, executor: CommandExecutor, usage: String) {
        require(commands.isNotEmpty()) { "At least one command name is required." }
        require(usage.isNotBlank()) { "Command usage must not be blank." }

        val normalizedCommands = commands
            .map { it.trim().lowercase(Locale.ROOT) }
            .also { names ->
                require(names.none(String::isBlank)) { "Command name must not be blank." }
                require(names.distinct().size == names.size) { "Command aliases must be unique." }
                require(names.none { commandDivider in it || commandOperator in it }) {
                    "Command name must not contain the operator or divider."
                }
            }

        normalizedCommands.firstOrNull(commandMap::containsKey)?.let {
            throw IllegalArgumentException("Command $it is already registered.")
        }

        val definition = CommandDefinition(executor, usage.trim())
        normalizedCommands.forEach { commandMap[it] = definition }
    }

    fun unregisterCommand(vararg commands: String) {
        val normalizedCommands = commands.map { it.trim().lowercase(Locale.ROOT) }
        normalizedCommands.firstOrNull { it !in commandMap }?.let {
            throw IllegalArgumentException("Command $it is not registered.")
        }
        normalizedCommands.forEach(commandMap::remove)
    }

    private fun registerCommandEvent(isStartWithAtBot: Boolean = true, operator: Char? = null, divider: Char = ' ') {
        this.getEventBus().register(GroupMessageEvent::class.java) {
            logger.info(
                "[Receive] {} <- [GROUP] [{}] [{}] {}",
                it.botID,
                it.groupID,
                it.sender.userID,
                it.message.toString()
            )
            if (isStartWithAtBot && (it.message.firstOrNull() as? At)?.target != it.botID) return@register
            val copy = MessageChain()
            copy.addAll(it.message)
            if (isStartWithAtBot) copy.removeAt(0)
            commandParser(MessageSender(it.groupID, it.sender, ChatType.GROUP), copy, operator, divider, it.messageID)
        }
        this.getEventBus().register(PrivateMessageEvent::class.java) {
            logger.info("[Receive] {} <- [PRIVATE] [{}] {}", it.botID, it.sender.userID, it.message.toString())
            commandParser(
                MessageSender(it.sender.userID, it.sender, ChatType.PRIVATE),
                it.message,
                operator,
                divider,
                it.messageID
            )
        }
        isRegistered = true
    }

    private fun commandParser(
        sender: MessageSender,
        messageChain: MessageChain,
        operator: Char?,
        divider: Char,
        messageID: Long
    ): Boolean {
        val firstText = (messageChain.firstOrNull() as? PlainText)?.text?.trimStart() ?: return false
        if (firstText.isEmpty() || operator != null && !firstText.startsWith(operator)) return false

        val commandBody = if (operator == null) firstText else firstText.drop(1)
        val commandName = commandBody
            .substringBefore(divider)
            .trim()
            .lowercase(Locale.ROOT)
        if (commandName.isEmpty()) return false

        val definition = commandMap[commandName]
        if (definition == null) {
            reply(
                sender,
                messageID,
                translator.translate(
                    "command.unknown",
                    "$commandOperator$commandName",
                    commandOperator
                )
            )
            return true
        }

        val args = MessageChain()
        val firstArgument = commandBody.substringAfter(divider, "").trimStart(divider)
        if (firstArgument.isNotEmpty()) {
            args.add(PlainText(firstArgument))
        }
        messageChain.drop(1).forEach(args::add)

        commonScope.launch {
            try {
                definition.executor.execute(sender, commandName, args, messageID)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.error(
                    "Failed to execute command {} for user {} in {} {}.",
                    commandName,
                    sender.user.userID,
                    sender.type,
                    sender.target,
                    exception
                )
                reply(
                    sender,
                    messageID,
                    translator.translate("command.execution_failed")
                )
            }
        }
        return true
    }

    private fun reply(sender: MessageSender, messageID: Long, text: String) {
        sendMessage(sender.type, sender.target, MessageChain.replyTo(messageID, text))
    }
}
