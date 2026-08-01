package me.heartalborada.bots.telegram

import com.google.gson.JsonParser
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.At
import me.heartalborada.commons.bots.ActionButton
import me.heartalborada.commons.bots.ActionKeyboard
import me.heartalborada.commons.bots.Contact
import me.heartalborada.commons.bots.Dice
import me.heartalborada.commons.bots.File as FileMessage
import me.heartalborada.commons.bots.Image
import me.heartalborada.commons.bots.Location
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.PlainText
import me.heartalborada.commons.bots.Record
import me.heartalborada.commons.bots.Reply
import me.heartalborada.commons.bots.Video
import me.heartalborada.commons.bots.dto.FileInfo
import me.heartalborada.commons.bots.dto.ForwardMessageNode
import me.heartalborada.commons.bots.events.message.InlineQueryEvent
import me.heartalborada.commons.bots.events.message.CallbackQueryEvent
import me.heartalborada.commons.bots.events.message.PrivateMessageEvent
import me.heartalborada.commons.bots.events.meta.BotOfflineEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramBotTest {
    @Test
    fun `manages messages with explicit chat targets`() {
        val requests = mutableListOf<Pair<String, String>>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val method = chain.request().url.pathSegments.last()
                val body = Buffer().also { buffer -> chain.request().body?.writeTo(buffer) }.readUtf8()
                requests += method to body
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"ok":true,"result":true}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val bot = TelegramBot(
            token = "test-token",
            apiBaseUrl = "https://telegram.test",
            parentClient = client,
            autoConnect = false,
        )
        val edited = MessageChain().apply {
            add(PlainText("updated"))
            add(ActionKeyboard(listOf(listOf(ActionButton("Open", "/open")))))
        }

        try {
            assertTrue(bot.recallMessage(ChatType.GROUP, -100L, 7L))
            assertTrue(bot.editMessage(ChatType.PRIVATE, 42L, 8L, edited))
            assertTrue(bot.pinMessage(ChatType.GROUP, -100L, 9L, notify = true))
            assertTrue(bot.unpinMessage(ChatType.GROUP, -100L, 9L))

            assertEquals(
                listOf("deleteMessage", "editMessageText", "pinChatMessage", "unpinChatMessage"),
                requests.map { it.first },
            )
            val recall = JsonParser.parseString(requests[0].second).asJsonObject
            assertEquals(-100L, recall["chat_id"].asLong)
            assertEquals(7L, recall["message_id"].asLong)
            val edit = JsonParser.parseString(requests[1].second).asJsonObject
            assertEquals("updated", edit["text"].asString)
            assertEquals("Open", edit.getAsJsonObject("reply_markup")
                .getAsJsonArray("inline_keyboard")[0].asJsonArray[0].asJsonObject["text"].asString)
            val pin = JsonParser.parseString(requests[2].second).asJsonObject
            assertFalse(pin["disable_notification"].asBoolean)
        } finally {
            bot.close()
        }
    }

    @Test
    fun `connect automatically registers the current Telegram command menu`() {
        val methods = CopyOnWriteArrayList<String>()
        val commandRequest = CompletableFuture<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val method = chain.request().url.pathSegments.last()
                methods += method
                val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                val result = when (method) {
                    "getMe" -> """{"id":1,"username":"UsefulBot"}"""
                    "setMyCommands" -> {
                        commandRequest.complete(body)
                        "true"
                    }
                    "getUpdates" -> "[]"
                    else -> error("Unexpected Telegram method: $method")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"ok":true,"result":$result}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val bot = TelegramBot(
            token = "test-token",
            apiBaseUrl = "https://telegram.test",
            parentClient = client,
            autoConnect = false,
        )
        bot.registerCommand("tasks", "t", usage = "Show\n current tasks.") { _, _, _, _ -> }

        try {
            assertTrue(bot.connect())
            val request = JsonParser.parseString(commandRequest.get(2, TimeUnit.SECONDS)).asJsonObject
            val commands = request.getAsJsonArray("commands").map { it.asJsonObject }
            assertEquals(listOf("help", "tasks"), commands.map { it["command"].asString })
            assertEquals("Show current tasks.", commands.last()["description"].asString)
            assertTrue("getMe" in methods)
            assertTrue("setMyCommands" in methods)
        } finally {
            bot.close()
        }
    }

    @Test
    fun `normalizes commands addressed to this bot`() {
        assertEquals("/help get", normalizeTelegramCommand("/help@UsefulBot get", "usefulbot"))
        assertEquals("/help", normalizeTelegramCommand("/help", "usefulbot"))
        assertEquals("plain text", normalizeTelegramCommand("plain text", "usefulbot"))
        assertNull(normalizeTelegramCommand("/help@OtherBot", "usefulbot"))
    }

    @Test
    fun `maps Telegram media into common message segments`() {
        val chain = telegramMessageChain(
            JsonParser.parseString(
                """
                    {
                      "reply_to_message":{"message_id":7},
                      "photo":[
                        {"file_id":"small","file_unique_id":"photo-unique","file_size":10},
                        {"file_id":"large","file_unique_id":"photo-unique","file_size":20}
                      ],
                      "document":{"file_name":"document.pdf","file_id":"document-id","file_unique_id":"document-unique","file_size":30},
                      "voice":{"file_id":"voice-id","file_unique_id":"voice-unique","file_size":40},
                      "video":{"file_name":"video.mp4","file_id":"video-id","file_unique_id":"video-unique","file_size":50},
                      "location":{"latitude":1.25,"longitude":2.5},
                      "contact":{"user_id":42},
                      "dice":{"value":6},
                      "caption":"caption"
                    }
                """.trimIndent(),
            ).asJsonObject,
        )

        assertEquals(7L, assertIs<Reply>(chain[0]).id)
        val image = assertIs<Image>(chain[1])
        assertEquals("large", image.info.id)
        assertEquals("photo-unique", image.info.uniqueId)
        val file = assertIs<FileMessage>(chain[2])
        assertEquals("document-id", file.info.id)
        assertEquals("document-unique", file.info.uniqueId)
        assertEquals("voice-id", assertIs<Record>(chain[3]).info.id)
        assertEquals("video-id", assertIs<Video>(chain[4]).info.id)
        assertEquals("1.25", assertIs<Location>(chain[5]).latitude)
        assertEquals(Contact.ContactType.TELEGRAM, assertIs<Contact>(chain[6]).type)
        assertEquals(6, assertIs<Dice>(chain[7]).result)
        assertEquals("caption", assertIs<PlainText>(chain[8]).text)
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
                        add(ActionKeyboard(listOf(listOf(ActionButton("Download", "/get eh item")))))
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
            val button = json.getAsJsonObject("reply_markup")
                .getAsJsonArray("inline_keyboard")[0].asJsonArray[0].asJsonObject
            assertEquals("Download", button["text"].asString)
            assertEquals("/get eh item", button["callback_data"].asString)
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
    fun `maps action button callbacks back into command events`() {
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                methods += chain.request().url.pathSegments.last()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"ok":true,"result":true}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val bot = TelegramBot(
            token = "test-token",
            apiBaseUrl = "https://telegram.test",
            parentClient = client,
            autoConnect = false,
        )
        val event = CompletableFuture<PrivateMessageEvent>()
        val callbackEvent = CompletableFuture<CallbackQueryEvent>()
        bot.getEventBus().register(PrivateMessageEvent::class.java, event::complete)
        bot.getEventBus().register(CallbackQueryEvent::class.java, callbackEvent::complete)

        bot.handleUpdate(
            JsonParser.parseString(
                """
                    {
                      "callback_query":{
                        "id":"callback-1",
                        "from":{"id":42,"is_bot":false,"first_name":"Alice"},
                        "data":"/get jm JM123",
                        "message":{"message_id":9,"date":1700000000,"chat":{"id":42,"type":"private"}}
                      }
                    }
                """.trimIndent()
            ).asJsonObject
        )

        val callback = callbackEvent.get(2, TimeUnit.SECONDS)
        assertEquals("callback-1", callback.queryID)
        assertEquals("/get jm JM123", callback.data)
        assertEquals(ChatType.PRIVATE, callback.chatType)
        assertEquals(42L, callback.chatID)
        assertEquals("/get jm JM123", event.get(2, TimeUnit.SECONDS).message.toString())
        assertEquals(listOf("answerCallbackQuery"), methods)
        bot.close()
    }

    @Test
    fun `intercepted callback is not acknowledged or dispatched as a command`() {
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                methods += chain.request().url.pathSegments.last()
                error("Intercepted callback must not call Telegram")
            }
            .build()
        val bot = TelegramBot(
            token = "test-token",
            apiBaseUrl = "https://telegram.test",
            parentClient = client,
            autoConnect = false,
        )
        val command = CompletableFuture<PrivateMessageEvent>()
        bot.getEventBus().register(CallbackQueryEvent::class.java) { it.intercept() }
        bot.getEventBus().register(PrivateMessageEvent::class.java, command::complete)

        bot.handleUpdate(
            JsonParser.parseString(
                """
                    {
                      "callback_query":{
                        "id":"callback-2",
                        "from":{"id":42,"is_bot":false,"first_name":"Alice"},
                        "data":"/admin status",
                        "message":{"message_id":10,"date":1700000001,"chat":{"id":42,"type":"private"}}
                      }
                    }
                """.trimIndent(),
            ).asJsonObject,
        )

        assertTrue(methods.isEmpty())
        assertFalse(command.isDone)
        bot.close()
    }

    @Test
    fun `normal close publishes one expected offline event`() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val result = when (chain.request().url.pathSegments.last()) {
                    "getMe" -> """{"id":1,"username":"UsefulBot"}"""
                    "setMyCommands" -> "true"
                    "getUpdates" -> "[]"
                    else -> error("Unexpected Telegram method")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"ok":true,"result":$result}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val bot = TelegramBot(
            token = "test-token",
            apiBaseUrl = "https://telegram.test",
            parentClient = client,
            autoConnect = false,
        )
        val events = mutableListOf<BotOfflineEvent>()
        bot.getEventBus().register(BotOfflineEvent::class.java) { events += it }

        bot.connect()
        bot.close()

        assertEquals(1, events.size)
        assertTrue(events.single().expected)
        assertEquals("Closed", events.single().reason)
    }

    @Test
    fun `rejects an empty token`() {
        assertFailsWith<IllegalArgumentException> {
            TelegramBot(token = "", autoConnect = false)
        }
    }

    @Test
    fun `only the official Bot API applies the 50 MiB upload limit`() {
        val file = Files.createTempFile("telegram-upload-limit-", ".pdf").toFile()
        RandomAccessFile(file, "rw").use { it.setLength(50L * 1024 * 1024 + 1) }
        val officialBot = TelegramBot(token = "test-token", autoConnect = false)
        val localBot = TelegramBot(
            token = "test-token",
            apiBaseUrl = "http://127.0.0.1:8081",
            autoConnect = false,
        )
        try {
            assertTrue(officialBot.exceedsOfficialUploadLimit(file))
            assertFalse(localBot.exceedsOfficialUploadLimit(file))
        } finally {
            officialBot.close()
            localBot.close()
            file.delete()
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
