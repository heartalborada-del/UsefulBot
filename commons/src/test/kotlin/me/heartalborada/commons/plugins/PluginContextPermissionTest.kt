package me.heartalborada.commons.plugins

import kotlinx.coroutines.runBlocking
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.AbstractBot
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.dto.ForwardMessageNode
import me.heartalborada.commons.bots.dto.ForwardMessageResult
import me.heartalborada.commons.bots.dto.UserInfo
import me.heartalborada.commons.bots.events.EventBus
import me.heartalborada.commons.bots.events.message.GroupMessageEvent
import me.heartalborada.commons.bots.events.message.PrivateMessageEvent
import me.heartalborada.commons.i18n.Translator
import me.heartalborada.commons.permissions.PermissionContext
import me.heartalborada.commons.permissions.PermissionDefault
import me.heartalborada.commons.permissions.PermissionService
import me.heartalborada.commons.permissions.PermissionSubject
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginContextPermissionTest {
    @Test
    fun `leaf commands use generated nodes and platform scoped user contexts`() {
        val permissions = RecordingPermissionService { _, _, _ -> true }
        Fixture(permissions).use { fixture ->
            val executed = CompletableFuture<Unit>()
            fixture.context.registerCommand("hello", "hi", usage = "/hello") { _, _, _, _, _ ->
                executed.complete(Unit)
            }

            fixture.bot.broadcastPrivate("/hi", userId = 7, messageId = 10)

            executed.get(2, TimeUnit.SECONDS)
            val check = permissions.checks.poll(2, TimeUnit.SECONDS)
            assertEquals("demo.hello", check.node)
            assertEquals("tg:user:7", check.context.user.key)
            assertNull(check.context.group)
            assertEquals(PermissionDefault.ALLOW, check.default)
        }
    }

    @Test
    fun `subcommands use generated or explicit nodes and include the group subject`() {
        val permissions = RecordingPermissionService { _, _, _ -> true }
        Fixture(permissions).use { fixture ->
            val executions = LinkedBlockingQueue<String>()
            fixture.context.registerCommand("manage", "m", usage = "/manage <action>") {
                subcommand("reload", "r", usage = "/manage reload") { _, _, command, _, _ ->
                    executions += command
                }
                subcommand(
                    "status",
                    usage = "/manage status",
                    permission = "system.health.read",
                ) { _, _, command, _, _ ->
                    executions += command
                }
            }

            fixture.bot.broadcastGroup("/m r", groupId = -100, userId = 8, messageId = 11)
            assertEquals("reload", executions.poll(2, TimeUnit.SECONDS))
            val generated = permissions.checks.poll(2, TimeUnit.SECONDS)
            assertEquals("demo.manage.reload", generated.node)
            assertEquals("tg:user:8", generated.context.user.key)
            assertEquals("tg:group:-100", generated.context.group?.key)

            fixture.bot.broadcastGroup("/manage status", groupId = -100, userId = 8, messageId = 12)
            assertEquals("status", executions.poll(2, TimeUnit.SECONDS))
            assertEquals("system.health.read", permissions.checks.poll(2, TimeUnit.SECONDS).node)
        }
    }

    @Test
    fun `permission defaults are forwarded and denied commands never execute`() {
        val permissions = RecordingPermissionService { _, _, default -> default == PermissionDefault.ALLOW }
        Fixture(permissions).use { fixture ->
            val executions = LinkedBlockingQueue<String>()
            fixture.context.registerCommand(
                "open",
                usage = "/open",
                permissionDefault = PermissionDefault.ALLOW,
            ) { _, _, command, _, _ -> executions += command }
            fixture.context.registerCommand(
                "closed",
                usage = "/closed",
                permissionDefault = PermissionDefault.DENY,
            ) { _, _, command, _, _ -> executions += command }
            fixture.context.registerCommand(
                "admin",
                usage = "/admin",
                permissionDefault = PermissionDefault.ADMIN,
            ) { _, _, command, _, _ -> executions += command }

            fixture.bot.broadcastPrivate("/open", userId = 9, messageId = 13)
            assertEquals("open", executions.poll(2, TimeUnit.SECONDS))

            fixture.bot.broadcastPrivate("/closed", userId = 9, messageId = 14)
            assertTrue(fixture.bot.sent.poll(2, TimeUnit.SECONDS).toString().contains("demo.closed"))

            fixture.bot.broadcastPrivate("/admin", userId = 9, messageId = 15)
            assertTrue(fixture.bot.sent.poll(2, TimeUnit.SECONDS).toString().contains("demo.admin"))

            assertEquals(
                listOf(PermissionDefault.ALLOW, PermissionDefault.DENY, PermissionDefault.ADMIN),
                List(3) { permissions.checks.poll(2, TimeUnit.SECONDS).default },
            )
            assertTrue(executions.isEmpty())
        }
    }

    @Test
    fun `plugin console commands bypass chat subjects only when explicitly enabled`() = runBlocking {
        val permissions = RecordingPermissionService { _, _, _ -> false }
        Fixture(permissions).use { fixture ->
            val output = mutableListOf<String>()
            fixture.context.registerCommand(
                "console",
                usage = "/console",
                permissionDefault = PermissionDefault.DENY or PermissionDefault.ALLOW_CONSOLE,
            ) { bot, sender, _, _, _ ->
                bot.sendCommandMessage(sender, MessageChain.text("done"))
            }

            assertEquals(listOf("console"), fixture.bot.completeConsoleCommand(null, "con"))
            assertTrue(fixture.bot.executeConsoleCommand("console") { output += it })
            assertEquals(listOf("done"), output)
            assertTrue(permissions.checks.isEmpty())
        }
    }

    private data class PermissionCheck(
        val context: PermissionContext,
        val node: String,
        val default: PermissionDefault,
    )

    private class RecordingPermissionService(
        private val decision: (PermissionContext, String, PermissionDefault) -> Boolean,
    ) : PermissionService {
        val checks = LinkedBlockingQueue<PermissionCheck>()

        override fun hasPermission(
            context: PermissionContext,
            permission: String,
            default: PermissionDefault,
        ): Boolean {
            checks += PermissionCheck(context, permission, default)
            return decision(context, permission, default)
        }

        override fun grant(subject: PermissionSubject, permission: String): Boolean = false
        override fun deny(subject: PermissionSubject, permission: String): Boolean = false
        override fun clear(subject: PermissionSubject, permission: String): Boolean = false
        override fun rules(subject: PermissionSubject): Set<String> = emptySet()
    }

    private class Fixture(permissions: PermissionService) : AutoCloseable {
        private val directory = Files.createTempDirectory("plugin-permission-test-").toFile()
        val bot = FakeBot()
        val context = PluginContext(
            pluginId = "demo",
            rootDirectory = directory,
            configDirectory = directory.resolve("config"),
            dataDirectory = directory.resolve("data"),
            bots = listOf(bot),
            logger = LoggerFactory.getLogger("plugin.test.demo"),
            platformResolver = { "tg" },
        )

        init {
            context.registerService(PermissionService::class.java, permissions)
            bot.connect()
        }

        override fun close() {
            context.close()
            bot.close()
            directory.deleteRecursively()
        }
    }

    private class FakeBot : AbstractBot(commandStartWithAt = false, translator = TRANSLATOR) {
        private val events = EventBus()
        val sent = LinkedBlockingQueue<MessageChain>()

        override fun getEventBus(): EventBus = events

        override fun sendMessage(type: ChatType, id: Long, message: MessageChain): Long {
            sent += message
            return 1
        }

        override fun sendForwardMessage(
            type: ChatType,
            target: Long,
            messages: List<ForwardMessageNode>,
        ): ForwardMessageResult = ForwardMessageResult(1)

        override fun recallMessage(messageID: Long): Boolean = true
        override fun sendFile(type: ChatType, target: Long, name: String, url: String): Boolean = true
        override fun sendFile(type: ChatType, target: Long, name: String, file: File): Boolean = true

        fun broadcastPrivate(text: String, userId: Long, messageId: Long) {
            events.broadcast(
                PrivateMessageEvent(1, 0, UserInfo(userId, "user"), MessageChain.text(text), messageId),
            )
        }

        fun broadcastGroup(text: String, groupId: Long, userId: Long, messageId: Long) {
            events.broadcast(
                GroupMessageEvent(
                    1,
                    0,
                    groupId,
                    UserInfo(userId, "user"),
                    MessageChain.text(text),
                    messageId,
                ),
            )
        }
    }

    private companion object {
        val TRANSLATOR = Translator { key, arguments ->
            when (key) {
                "command.help.header" -> "Available commands:"
                "command.help.usage" -> "Show commands."
                "command.help.subcommands" -> "Available subcommands:"
                "command.unknown" -> "Unknown command ${arguments.firstOrNull()}."
                "command.execution_failed" -> "Command failed."
                else -> key
            }
        }
    }
}
