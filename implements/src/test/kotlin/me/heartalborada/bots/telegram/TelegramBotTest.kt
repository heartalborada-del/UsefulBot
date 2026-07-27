package me.heartalborada.bots.telegram

import com.google.gson.JsonParser
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.At
import me.heartalborada.commons.bots.Image
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.PlainText
import me.heartalborada.commons.bots.Reply
import me.heartalborada.commons.bots.dto.FileInfo
import me.heartalborada.commons.bots.dto.ForwardMessageNode
import me.heartalborada.commons.bots.events.message.InlineQueryEvent
import me.heartalborada.commons.bots.events.message.PrivateMessageEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.nio.file.Files
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
    fun `renders search result command as Telegram Markdown code`() {
        assertEquals(
            "\\#9\n标题：Example\n获取：`/get eh https://e-hentai.org/g/1/token/`",
            renderTelegramMarkdownV2(
                "#9\n标题：Example\n获取：`/get eh https://e-hentai.org/g/1/token/`"
            ),
        )
    }

    @Test
    fun `sends forwarded search results separately and quotes the search message`() {
        val requestBodies = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestBodies += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                            {"ok":true,"result":{"message_id":${requestBodies.size},"chat":{"id":123}}}
                        """.trimIndent().toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        val bot = TelegramBot(
            token = "test-token",
            apiBaseUrl = "https://telegram.test",
            parentClient = client,
            autoConnect = false,
        )
        val results = listOf("#1\n获取：`/get eh first`", "#2\n获取：`/get eh second`")
            .map { text ->
                ForwardMessageNode.CustomMessage(
                    nickname = "result",
                    content = MessageChain().apply {
                        add(Reply(99L))
                        add(PlainText(text))
                    },
                )
            }

        bot.sendForwardMessage(ChatType.PRIVATE, 123L, results)

        assertEquals(2, requestBodies.size)
        requestBodies.forEachIndexed { index, body ->
            val json = JsonParser.parseString(body).asJsonObject
            assertEquals("\\#${index + 1}\n获取：`/get eh ${if (index == 0) "first" else "second"}`", json["text"].asString)
            assertEquals("MarkdownV2", json["parse_mode"].asString)
            assertEquals(99L, json.getAsJsonObject("reply_parameters")["message_id"].asLong)
        }
        bot.close()
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

    @Test
    fun `reports request entity too large as a typed Telegram error`() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(413)
                    .message("Payload Too Large")
                    .body(
                        """{"ok":false,"description":"Request Entity Too Large"}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        val file = Files.createTempFile("telegram-large-document-", ".pdf").toFile()
        file.writeText("test")
        val bot = TelegramBot(
            token = "test-token",
            apiBaseUrl = "https://telegram.test",
            parentClient = client,
            autoConnect = false,
        )
        try {
            val exception = assertFailsWith<TelegramApiException> {
                bot.sendFile(ChatType.PRIVATE, 123L, "test.pdf", file)
            }

            assertEquals("sendDocument", exception.method)
            assertEquals(413, exception.statusCode)
            assertTrue(exception.isRequestEntityTooLarge())
        } finally {
            bot.close()
            file.delete()
        }
    }

    @Test
    fun `returns reusable file ids from uploaded and resent documents`() {
        val requestBodies = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestBodies += Buffer().also { buffer ->
                    chain.request().body?.writeTo(buffer)
                }.readUtf8()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                            {
                              "ok":true,
                              "result":{
                                "message_id":${requestBodies.size},
                                "chat":{"id":123},
                                "document":{
                                  "file_id":"reusable-file-id",
                                  "file_unique_id":"stable-unique-id"
                                }
                              }
                            }
                        """.trimIndent().toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        val file = Files.createTempFile("telegram-document-receipt-", ".pdf").toFile()
        file.writeText("test")
        val bot = TelegramBot(
            token = "test-token",
            apiBaseUrl = "https://telegram.test",
            parentClient = client,
            autoConnect = false,
        )
        try {
            val uploaded = bot.uploadDocument(ChatType.PRIVATE, 123L, "test.pdf", file)
            val resent = bot.resendDocument(ChatType.PRIVATE, 456L, uploaded.fileId)

            assertEquals("reusable-file-id", uploaded.fileId)
            assertEquals("stable-unique-id", uploaded.fileUniqueId)
            assertEquals(uploaded.fileId, resent.fileId)
            val resendBody = JsonParser.parseString(requestBodies.last()).asJsonObject
            assertEquals(456L, resendBody["chat_id"].asLong)
            assertEquals("reusable-file-id", resendBody["document"].asString)
        } finally {
            bot.close()
            file.delete()
        }
    }

    @Test
    fun `recognizes Telegram rejected file identifiers`() {
        val exception = TelegramApiException(
            method = "sendDocument",
            statusCode = 400,
            description = "Bad Request: wrong remote file identifier specified",
        )

        assertTrue(exception.isInvalidFileIdentifier())
    }
}
