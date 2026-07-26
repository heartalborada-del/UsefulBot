package me.heartalborada.commons.bots

import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.dto.MessageSender
import me.heartalborada.commons.bots.dto.UserInfo
import me.heartalborada.commons.bots.events.EventBus
import me.heartalborada.commons.bots.events.message.PrivateMessageEvent
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.i18n.Translator
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
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

        bot.broadcast("/fail", messageID = 11L)
        val failure = bot.sent.poll(2, TimeUnit.SECONDS)
        assertIs<Reply>(failure[0])
        val failureText = (failure[1] as PlainText).text
        assertTrue(failureText.contains("failed"))
        assertTrue("sensitive detail" !in failureText)
    }

    @Test
    fun `built-in command replies use the configured language`() {
        val bot = FakeBot(Translator("zh-CN"))
        bot.connect()

        bot.broadcast("/missing", messageID = 12L)

        val reply = bot.sent.poll(2, TimeUnit.SECONDS)
        assertTrue((reply[1] as PlainText).text.startsWith("未知命令"))
    }

    private fun executor(
        block: suspend (MessageSender, String, MessageChain, Long) -> Unit
    ): CommandExecutor = object : CommandExecutor {
        override suspend fun execute(
            sender: MessageSender,
            command: String,
            args: MessageChain,
            messageID: Long
        ) = block(sender, command, args, messageID)
    }

    private inner class FakeBot(translator: Translator = Translator()) :
        AbstractBot(commandStartWithAt = false, translator = translator) {
        val events = EventBus()
        val sent = LinkedBlockingQueue<MessageChain>()

        override fun getEventBus(): EventBus = events

        override fun sendMessage(type: ChatType, id: Long, message: MessageChain): Long {
            sent.add(message)
            return 1L
        }

        override fun recallMessage(messageID: Long): Boolean = true

        override fun sendFile(type: ChatType, target: Long, name: String, url: String): Boolean = true

        override fun sendFile(type: ChatType, target: Long, name: String, file: File): Boolean = true

        fun broadcast(text: String, messageID: Long) {
            events.broadcast(
                PrivateMessageEvent(1L, 0L, sender, MessageChain.text(text), messageID)
            )
        }
    }
}
