package me.heartalborada.commons.bots

import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.dto.MessageSender
import me.heartalborada.commons.bots.dto.ForwardMessageNode
import me.heartalborada.commons.bots.dto.ForwardMessageResult
import me.heartalborada.commons.bots.dto.UserInfo
import me.heartalborada.commons.bots.events.EventBus
import me.heartalborada.commons.bots.events.message.PrivateMessageEvent
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.commands.CommandErrorHandler
import me.heartalborada.commons.i18n.Translator
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AbstractBotCommandTest {
    private val sender = UserInfo(7L, "tester")

    @Test
    fun `parser removes command and preserves the argument message chain`() {
        val bot = FakeBot()
        val executed = CompletableFuture<Pair<String, MessageChain>>()
        bot.registerCommand(
            "echo",
            executor = executor { _, command, args, _ -> executed.complete(command to args) },
            usage = "Echo input."
        )
        bot.connect()

        val incoming = MessageChain().apply {
            add(PlainText("  /EcHo   gallery URL with spaces  "))
            add(At(99L))
            add(PlainText(" tail"))
        }
        bot.events.broadcast(PrivateMessageEvent(1L, 0L, sender, incoming, 42L))

        val (command, args) = executed.get(2, TimeUnit.SECONDS)
        assertEquals("echo", command)
        assertEquals("gallery URL with spaces  ", (args[0] as PlainText).text)
        assertIs<At>(args[1])
        assertEquals(" tail", (args[2] as PlainText).text)
    }

    @Test
    fun `unknown commands and executor failures return safe replies`() {
        val bot = FakeBot()
        bot.registerCommand(
            "fail",
            executor = executor { _, _, _, _ -> error("sensitive detail") },
            usage = "Fail."
        )
        bot.connect()

        bot.broadcast("/missing", messageID = 10L)
        val unknown = bot.sent.poll(2, TimeUnit.SECONDS)
        assertIs<Reply>(unknown[0])
        assertEquals(10L, (unknown[0] as Reply).id)
        assertTrue((unknown[1] as PlainText).text.contains("Unknown command"))
        assertTrue((unknown[1] as PlainText).text.contains("Available commands"))

        bot.broadcast("/fail", messageID = 11L)
        val failure = bot.sent.poll(2, TimeUnit.SECONDS)
        assertIs<Reply>(failure[0])
        val failureText = (failure[1] as PlainText).text
        assertTrue(failureText.contains("failed"))
        assertTrue("sensitive detail" !in failureText)
    }

    @Test
    fun `built-in command replies use the configured language`() {
        val bot = FakeBot(testTranslator(chinese = true))
        bot.connect()

        bot.broadcast("/missing", messageID = 12L)

        val reply = bot.sent.poll(2, TimeUnit.SECONDS)
        assertTrue((reply[1] as PlainText).text.startsWith("未知命令"))
    }

    @Test
    fun `subcommands dispatch without leaking their name into arguments`() {
        val bot = FakeBot()
        val executed = CompletableFuture<Pair<String, MessageChain>>()
        bot.registerCommand(
            "get",
            usage = "/get <eh|jm> <target>",
        ) {
            subcommand("eh", "ex", usage = "/get eh <gallery URL>") { _, command, args, _ ->
                executed.complete(command to args)
            }
        }
        bot.connect()

        bot.broadcast("/get EX https://example.test/g/1/token/", messageID = 13L)

        val (command, args) = executed.get(2, TimeUnit.SECONDS)
        assertEquals("eh", command)
        assertEquals("https://example.test/g/1/token/", args.toString())
    }

    @Test
    fun `missing or unknown subcommands show scoped help`() {
        val bot = FakeBot()
        bot.registerCommand(
            "get",
            usage = "/get <eh|jm> <target>",
        ) {
            subcommand("eh", usage = "/get eh <gallery URL>") { _, _, _, _ -> }
            subcommand("jm", usage = "/get jm <JM ID>") { _, _, _, _ -> }
        }
        bot.connect()

        bot.broadcast("/get", messageID = 14L)
        val missingReply = bot.sent.poll(2, TimeUnit.SECONDS)
        val invalidText = (missingReply[1] as PlainText).text
        assertTrue(invalidText.contains("/get <eh|jm> <target>"))
        assertTrue(invalidText.contains("Available subcommands"))
        assertTrue(invalidText.contains("/get eh <gallery URL>"))
        assertTrue(invalidText.contains("/get jm <JM ID>"))

        bot.broadcast("/get unsupported", messageID = 15L)
        val invalidReply = bot.sent.poll(2, TimeUnit.SECONDS)
        assertEquals(invalidText, (invalidReply[1] as PlainText).text)

        bot.broadcast("/help get", messageID = 16L)
        val helpReply = bot.sent.poll(2, TimeUnit.SECONDS)
        assertEquals(invalidText, (helpReply[1] as PlainText).text)
    }

    @Test
    fun `invalid command trees fail during registration`() {
        val bot = FakeBot()

        assertFailsWith<IllegalArgumentException> {
            bot.registerCommand("empty", usage = "/empty") {}
        }
        assertFailsWith<IllegalArgumentException> {
            bot.registerCommand("get", usage = "/get <source>") {
                subcommand("eh", "gallery", usage = "/get eh") { _, _, _, _ -> }
                subcommand("gallery", usage = "/get jm") { _, _, _, _ -> }
            }
        }
    }

    @Test
    fun `before command executors run before resolved commands`() {
        val bot = FakeBot()
        val calls = mutableListOf<String>()
        val completed = CompletableFuture<Unit>()
        bot.beforeCommandExecution(executor { _, command, _, _ ->
            calls += "before:$command"
        })
        bot.registerCommand("run", usage = "Run.") { _, command, _, _ ->
            calls += "command:$command"
            completed.complete(Unit)
        }
        bot.connect()

        bot.broadcast("/run", messageID = 17L)

        completed.get(2, TimeUnit.SECONDS)
        assertEquals(listOf("before:run", "command:run"), calls)
    }

    @Test
    fun `exposes canonical command metadata without aliases`() {
        val bot = FakeBot()
        bot.registerCommand("echo", "e", usage = "Echo input.") { _, _, _, _ -> }

        val commands = bot.registeredCommands()

        assertEquals(listOf("help", "echo"), commands.map { it.name })
        assertEquals("Echo input.", commands.last().description)
    }

    @Test
    fun `hidden commands are omitted from help for unauthorized senders`() {
        val bot = FakeBot()
        bot.registerCommand("admin", usage = "/admin <action>") {
            subcommand("status", usage = "/admin status") { _, _, _, _ -> }
        }
        bot.setCommandVisibility("admin") { it.user.userID == 1L }
        bot.connect()

        bot.broadcast("/help", messageID = 19L)
        val globalHelp = (bot.sent.poll(2, TimeUnit.SECONDS)[1] as PlainText).text
        assertTrue("/admin" !in globalHelp)

        bot.broadcast("/help admin", messageID = 20L)
        val scopedHelp = (bot.sent.poll(2, TimeUnit.SECONDS)[1] as PlainText).text
        assertTrue("/admin" !in scopedHelp)

        bot.broadcast("/admin", messageID = 21L)
        val hiddenCommand = (bot.sent.poll(2, TimeUnit.SECONDS)[1] as PlainText).text
        assertTrue(hiddenCommand.contains("Unknown command"))
        assertTrue("/admin status" !in hiddenCommand)

        bot.broadcast("/help", messageID = 22L, user = UserInfo(1L, "admin"))
        val adminHelp = (bot.sent.poll(2, TimeUnit.SECONDS)[1] as PlainText).text
        assertTrue(adminHelp.contains("/admin status"))
    }

    @Test
    fun `command error handlers receive the full operation and control the safe reply`() {
        val bot = FakeBot()
        val captured = CompletableFuture<Pair<String, Throwable>>()
        bot.onCommandError(CommandErrorHandler { _, operation, _, error ->
            captured.complete(operation to error)
            "Report error/test.err.log to the administrator."
        })
        bot.registerCommand("fail", usage = "Fail.") { _, _, _, _ -> error("root cause") }
        bot.connect()

        bot.broadcast("/fail input", messageID = 18L)

        val (operation, error) = captured.get(2, TimeUnit.SECONDS)
        assertEquals("/fail input", operation)
        assertEquals("root cause", error.message)
        val reply = bot.sent.poll(2, TimeUnit.SECONDS)
        assertTrue((reply[1] as PlainText).text.contains("error/test.err.log"))
    }

    private fun executor(
        block: suspend (MessageSender, String, MessageChain, Long) -> Unit
    ): CommandExecutor = CommandExecutor(block)

    private fun testTranslator(chinese: Boolean = false): Translator {
        val messages = if (chinese) {
            mapOf(
                "command.help.header" to "可用命令：",
                "command.help.usage" to "显示此命令列表。",
                "command.help.subcommands" to "可用子命令：",
                "command.unknown" to "未知命令“{0}”。",
                "command.execution_failed" to "命令执行失败，请稍后重试或联系管理员。",
            )
        } else {
            mapOf(
                "command.help.header" to "Available commands:",
                "command.help.usage" to "Show this command list.",
                "command.help.subcommands" to "Available subcommands:",
                "command.unknown" to "Unknown command \"{0}\".",
                "command.execution_failed" to
                    "Command execution failed. Please try again later or contact the administrator.",
            )
        }
        return Translator { key, arguments ->
            arguments.foldIndexed(messages[key] ?: key) { index, result, argument ->
                result.replace("{$index}", argument?.toString().orEmpty())
            }
        }
    }

    private inner class FakeBot(translator: Translator = testTranslator()) :
        AbstractBot(commandStartWithAt = false, translator = translator) {
        val events = EventBus()
        val sent = LinkedBlockingQueue<MessageChain>()

        override fun getEventBus(): EventBus = events

        override fun sendMessage(type: ChatType, id: Long, message: MessageChain): Long {
            sent.add(message)
            return 1L
        }

        override fun sendForwardMessage(
            type: ChatType,
            target: Long,
            messages: List<ForwardMessageNode>,
        ): ForwardMessageResult = ForwardMessageResult(1L)

        override fun recallMessage(messageID: Long): Boolean = true

        override fun sendFile(type: ChatType, target: Long, name: String, url: String): Boolean = true

        override fun sendFile(type: ChatType, target: Long, name: String, file: File): Boolean = true

        fun broadcast(text: String, messageID: Long, user: UserInfo = sender) {
            events.broadcast(
                PrivateMessageEvent(1L, 0L, user, MessageChain.text(text), messageID)
            )
        }
    }
}
