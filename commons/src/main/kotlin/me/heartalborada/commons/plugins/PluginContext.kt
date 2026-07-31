package me.heartalborada.commons.plugins

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.heartalborada.commons.bots.AbstractBot
import me.heartalborada.commons.bots.events.Event
import me.heartalborada.commons.bots.events.EventPriority
import me.heartalborada.commons.bots.events.EventSubscription
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.dto.MessageSender
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.permissions.PermissionContext
import me.heartalborada.commons.permissions.PermissionDefault
import me.heartalborada.commons.permissions.PermissionNodeRegistry
import me.heartalborada.commons.permissions.PermissionService
import me.heartalborada.commons.permissions.PermissionSubject
import me.heartalborada.commons.permissions.PermissionSubjectType
import org.slf4j.Logger
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Event callback that identifies the adapter which published the event. */
fun interface PluginEventListener<E : Event> {
    fun onEvent(bot: AbstractBot, event: E)
}

/** Command callback that identifies the adapter which received the command. */
fun interface PluginCommandExecutor {
    fun execute(
        bot: AbstractBot,
        sender: MessageSender,
        command: String,
        arguments: MessageChain,
        messageID: Long,
    )
}

/** Permission-aware subcommand declaration used by plugins. */
class PluginSubcommandBuilder internal constructor(
    private val pluginId: String,
    private val parentCommand: String,
) {
    internal data class Definition(
        val commands: Array<out String>,
        val usage: String,
        val permission: String,
        val permissionDefault: PermissionDefault,
        val executor: PluginCommandExecutor,
    )

    private val definitions = mutableListOf<Definition>()

    fun subcommand(
        vararg commands: String,
        usage: String,
        permission: String? = null,
        permissionDefault: PermissionDefault = PermissionDefault.ALLOW,
        executor: PluginCommandExecutor,
    ) {
        require(commands.isNotEmpty()) { "At least one subcommand name is required." }
        definitions += Definition(
            commands = commands,
            usage = usage,
            permission = permission ?: defaultPermission(pluginId, parentCommand, commands.first()),
            permissionDefault = permissionDefault,
            executor = executor,
        )
    }

    internal fun build(): List<Definition> = definitions.toList()
}

/**
 * Resources exposed to one plugin instance.
 *
 * Registrations made through this context are owned by the plugin and are
 * removed automatically during unload. [scope] is cancelled at the same time.
 */
class PluginContext(
    val pluginId: String,
    val rootDirectory: File,
    val configDirectory: File,
    val dataDirectory: File,
    val bots: List<AbstractBot>,
    val logger: Logger,
    private val services: PluginServiceRegistry = PluginServiceRegistry(),
    private val platformResolver: (AbstractBot) -> String = { bot ->
        bot.javaClass.simpleName.lowercase()
    },
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val cleanupActions = CopyOnWriteArrayList<() -> Unit>()

    /** Scope for plugin background work; failures do not cancel sibling tasks. */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("Plugin-$pluginId"),
    )

    init {
        require(configDirectory.mkdirs() || configDirectory.isDirectory) {
            "Could not create plugin config directory: ${configDirectory.absolutePath}"
        }
        require(dataDirectory.mkdirs() || dataDirectory.isDirectory) {
            "Could not create plugin data directory: ${dataDirectory.absolutePath}"
        }
    }

    /** Registers [listener] on every active bot adapter. */
    @JvmOverloads
    fun <E : Event> listen(
        eventType: Class<E>,
        priority: Int = EventPriority.NORMAL,
        receiveIntercepted: Boolean = false,
        listener: PluginEventListener<E>,
    ): EventSubscription {
        ensureOpen()
        val subscriptions = mutableListOf<EventSubscription>()
        try {
            bots.forEach { bot ->
                subscriptions += bot.getEventBus().register(eventType, priority, receiveIntercepted) { event ->
                    listener.onEvent(bot, event)
                }
            }
        } catch (throwable: Throwable) {
            subscriptions.asReversed().forEach(EventSubscription::close)
            throw throwable
        }
        val composite = EventSubscription { subscriptions.asReversed().forEach(EventSubscription::close) }
        own(composite::close)
        return composite
    }

    /** Registers the same leaf command and aliases on every active bot adapter. */
    fun registerCommand(
        vararg commands: String,
        usage: String,
        permission: String? = null,
        permissionDefault: PermissionDefault = PermissionDefault.ALLOW,
        executor: PluginCommandExecutor,
    ) {
        require(commands.isNotEmpty()) { "At least one command name is required." }
        val permissionNode = permission ?: defaultPermission(pluginId, commands.first())
        registerCommandOnBots(commands) { bot ->
            bot.registerCommand(
                *commands,
                usage = usage,
                permissionDefault = permissionDefault,
                executor = CommandExecutor { sender, command, arguments, messageID ->
                    executeAuthorized(
                        bot,
                        sender,
                        messageID,
                        permissionNode,
                        permissionDefault,
                    ) {
                        executor.execute(bot, sender, command, arguments, messageID)
                    }
                },
            )
        }
        registerPermissionNodes(listOf(permissionNode))
    }

    /** Registers the same subcommand tree on every active bot adapter. */
    fun registerCommand(
        vararg commands: String,
        usage: String,
        configure: PluginSubcommandBuilder.() -> Unit,
    ) {
        require(commands.isNotEmpty()) { "At least one command name is required." }
        val definitions = PluginSubcommandBuilder(pluginId, commands.first()).apply(configure).build()
        registerCommandOnBots(commands) { bot ->
            bot.registerCommand(*commands, usage = usage) {
                definitions.forEach { definition ->
                    subcommand(
                        *definition.commands,
                        usage = definition.usage,
                        permissionDefault = definition.permissionDefault,
                        executor = CommandExecutor { sender, command, arguments, messageID ->
                            executeAuthorized(
                                bot,
                                sender,
                                messageID,
                                definition.permission,
                                definition.permissionDefault,
                            ) {
                                definition.executor.execute(bot, sender, command, arguments, messageID)
                            }
                        },
                    )
                }
            }
        }
        registerPermissionNodes(definitions.map(PluginSubcommandBuilder.Definition::permission))
    }

    /** Adds an arbitrary idempotent cleanup action to the plugin lifecycle. */
    fun onClose(action: () -> Unit) {
        ensureOpen()
        own(action)
    }

    /** Publishes a service until this plugin is disabled. */
    fun <S : Any> registerService(type: Class<S>, service: S) {
        ensureOpen()
        own(services.register(pluginId, type, service)::close)
    }

    /** Finds a service published by an already enabled plugin. */
    fun <S : Any> findService(type: Class<S>): S? = services.find(type)

    /** Returns a service or fails with a descriptive dependency error. */
    fun <S : Any> requireService(type: Class<S>): S = services.require(type)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        cleanupActions.asReversed().forEach { action ->
            runCatching(action).onFailure { logger.warn("Plugin cleanup action failed.", it) }
        }
        cleanupActions.clear()
    }

    private fun registerCommandOnBots(
        commands: Array<out String>,
        register: (AbstractBot) -> Unit,
    ) {
        ensureOpen()
        require(commands.isNotEmpty()) { "At least one command name is required." }
        val registered = mutableListOf<AbstractBot>()
        try {
            bots.forEach { bot ->
                register(bot)
                registered += bot
            }
        } catch (throwable: Throwable) {
            registered.asReversed().forEach { bot -> runCatching { bot.unregisterCommand(*commands) } }
            throw throwable
        }
        own { registered.asReversed().forEach { bot -> bot.unregisterCommand(*commands) } }
    }

    private fun own(action: () -> Unit) {
        cleanupActions += action
    }

    private fun registerPermissionNodes(nodes: Collection<String>) {
        val registry = findService(PermissionNodeRegistry::class.java) ?: return
        val distinctNodes = nodes.distinct()
        distinctNodes.forEach(registry::register)
        own { distinctNodes.forEach(registry::unregister) }
    }

    private fun executeAuthorized(
        bot: AbstractBot,
        sender: MessageSender,
        messageID: Long,
        permission: String,
        default: PermissionDefault,
        action: () -> Unit,
    ) {
        if (sender.type == ChatType.SELF) {
            check(default.allowsConsole) { "Permission $permission is not available from the console." }
            action()
            return
        }
        val service = requireService(PermissionService::class.java)
        val platform = platformResolver(bot)
        val context = PermissionContext(
            user = PermissionSubject(platform, PermissionSubjectType.USER, sender.user.userID),
            group = sender.target.takeIf { sender.type == ChatType.GROUP }
                ?.let { PermissionSubject(platform, PermissionSubjectType.GROUP, it) },
        )
        if (service.hasPermission(context, permission, default)) {
            action()
        } else {
            bot.sendCommandMessage(
                sender,
                MessageChain.replyTo(messageID, "Permission denied ($permission)."),
            )
        }
    }

    private fun ensureOpen() {
        check(!closed.get()) { "Plugin context $pluginId is closed." }
    }
}

private fun defaultPermission(pluginId: String, vararg commandPath: String): String =
    (listOf(pluginId) + commandPath).joinToString(".") { segment -> segment.trim().lowercase() }
