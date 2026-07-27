package me.heartalborada.bots.telegram

import com.google.gson.JsonParser
import me.heartalborada.commons.bots.At
import me.heartalborada.commons.bots.Image
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.PlainText
import me.heartalborada.commons.bots.Reply
import me.heartalborada.commons.bots.dto.FileInfo
import me.heartalborada.commons.bots.events.message.InlineQueryEvent
import me.heartalborada.commons.bots.events.message.PrivateMessageEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramBotTest {
    @Test
    fun `normalizes commands addressed to this bot`() {
        assertEquals("/help get", normalizeTelegramCommand("/help@UsefulBot get", "usefulbot"))
        assertEquals("/help", normalizeTelegramCommand("/help", "usefulbot"))
        assertEquals("plain text", normalizeTelegramCommand("plain text", "usefulbot"))
        assertNull(normalizeTelegramCommand("/help@OtherBot", "usefulbot"))
    }

    @Test
    fun `renders supported message objects as Telegram text`() {
        val chain = MessageChain().apply {
            add(Reply(1L))
            add(PlainText("hello "))
            add(At(42L))
            add(Image(FileInfo("cover.png", url = "base64://AA==")))
        }

        assertEquals("hello @42", renderTelegramText(chain))
    }

    @Test
    fun `splits long messages within Telegram limits`() {
        val chunks = splitTelegramText("first\n${"x".repeat(20)}", limit = 10)

        assertTrue(chunks.all { it.length <= 10 })
        assertEquals(listOf("first", "xxxxxxxxxx", "xxxxxxxxxx"), chunks)
    }

    @Test
    fun `maps message and inline updates to common events`() {
        val bot = TelegramBot(token = "test-token", autoConnect = false)
        val messageEvent = CompletableFuture<PrivateMessageEvent>()
        val inlineEvent = CompletableFuture<InlineQueryEvent>()
        bot.getEventBus().register(PrivateMessageEvent::class.java, messageEvent::complete)
        bot.getEventBus().register(InlineQueryEvent::class.java, inlineEvent::complete)

        bot.handleUpdate(
            JsonParser.parseString(
                """
                    {
                      "update_id":1,
                      "message":{
                        "message_id":9,
                        "date":1700000000,
                        "chat":{"id":123,"type":"private"},
                        "from":{"id":42,"is_bot":false,"first_name":"Alice","username":"alice"},
                        "text":"/help"
                      }
                    }
                """.trimIndent()
            ).asJsonObject
        )
        bot.handleUpdate(
            JsonParser.parseString(
                """
                    {
                      "update_id":2,
                      "inline_query":{
                        "id":"query-1",
                        "from":{"id":42,"is_bot":false,"first_name":"Alice"},
                        "query":"eh language:chinese",
                        "offset":"2"
                      }
                    }
                """.trimIndent()
            ).asJsonObject
        )

        val message = messageEvent.get(2, TimeUnit.SECONDS)
        assertEquals(42L, message.sender.userID)
        assertEquals("/help", message.message.toString())
        val inline = inlineEvent.get(2, TimeUnit.SECONDS)
        assertEquals("query-1", inline.queryID)
        assertEquals("eh language:chinese", inline.query)
        assertEquals("2", inline.offset)
        bot.close()
    }

    @Test
    fun `rejects an empty token`() {
        assertFailsWith<IllegalArgumentException> {
            TelegramBot(token = "", autoConnect = false)
        }
    }
}
