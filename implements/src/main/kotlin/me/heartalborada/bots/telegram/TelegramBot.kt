package me.heartalborada.bots.telegram

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.AbstractBot
import me.heartalborada.commons.bots.AbstractMessageObject
import me.heartalborada.commons.bots.ActionKeyboard
import me.heartalborada.commons.bots.At
import me.heartalborada.commons.bots.AtAll
import me.heartalborada.commons.bots.File as FileMessage
import me.heartalborada.commons.bots.Image
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.PlainText
import me.heartalborada.commons.bots.Reply
import me.heartalborada.commons.bots.dto.ForwardMessageNode
import me.heartalborada.commons.bots.dto.ForwardMessageResult
import me.heartalborada.commons.bots.dto.InlineQueryResult
import me.heartalborada.commons.bots.dto.UserInfo
import me.heartalborada.commons.bots.events.EventBus
import me.heartalborada.commons.bots.events.message.GroupMessageEvent
import me.heartalborada.commons.bots.events.message.InlineQueryEvent
import me.heartalborada.commons.bots.events.message.PrivateMessageEvent
import me.heartalborada.commons.i18n.Translator
import me.heartalborada.i18n.PropertiesTranslator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TelegramBot(
    token: String,
    apiBaseUrl: String = DEFAULT_API_BASE_URL,
    parentClient: OkHttpClient = OkHttpClient(),
    commandOperator: Char = '/',
    translator: Translator = PropertiesTranslator(),
    private val inlineModeEnabled: Boolean = true,
    uploadTimeoutMinutes: Long = DEFAULT_UPLOAD_TIMEOUT_MINUTES,
    autoConnect: Boolean = true,
) : AbstractBot(
    commandStartWithAt = false,
    commandOperator = commandOperator,
    translator = translator,
), TelegramDocumentClient {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val gson = Gson()
    private val eventBus = EventBus()
    private val connected = AtomicBoolean(false)
    private val validatedUploadTimeoutMinutes = uploadTimeoutMinutes.also {
        require(it > 0) { "Telegram upload timeout must be positive." }
    }
    private val pollingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("TelegramPolling"))
    private val client = parentClient.newBuilder()
        .readTimeout(POLL_TIMEOUT_SECONDS + 10L, TimeUnit.SECONDS)
        .writeTimeout(validatedUploadTimeoutMinutes, TimeUnit.MINUTES)
        .build()
    private val apiRoot: String
    private val usesOfficialBotApi: Boolean
    private val commandOperator = commandOperator
    private val messageChats = ConcurrentHashMap<Long, Long>()
    private val chatSendLocks = Array(CHAT_SEND_LOCK_COUNT) { Any() }

    @Volatile
    private var botID: Long = 0L

    @Volatile
    private var botUsername: String = ""

    init {
        require(token.isNotBlank()) { "Telegram bot token must not be blank." }
        val normalizedApiBaseUrl = apiBaseUrl.trimEnd('/')
        apiRoot = "$normalizedApiBaseUrl/bot$token/"
        usesOfficialBotApi = normalizedApiBaseUrl == DEFAULT_API_BASE_URL
        if (autoConnect) connect()
    }

    override fun connect(): Boolean {
        check(connected.compareAndSet(false, true)) { "Telegram bot is already connected." }
        return try {
            super.connect()
            val me = call("getMe").asJsonObject
            botID = me["id"].asLong
            botUsername = me.get("username")?.asString.orEmpty()
            runCatching(::synchronizeCommands)
                .onFailure { logger.warn("Failed to register Telegram bot commands; polling will continue.", it) }
            pollingScope.launch { pollUpdates() }
            true
        } catch (exception: Exception) {
            connected.set(false)
            throw exception
        }
    }

    override fun close(): Boolean {
        connected.set(false)
        pollingScope.cancel()
        super.close()
        return true
    }

    override fun getEventBus(): EventBus = eventBus

    override fun sendMessage(type: ChatType, id: Long, message: MessageChain): Long {
        logger.info("[Send] {} -> [{}] [{}] {}", botID, type.name, id, message.toString())
        require(type == ChatType.PRIVATE || type == ChatType.GROUP) { "Unsupported Telegram chat type: $type" }
        val replyTo = message.filterIsInstance<Reply>().firstOrNull()?.id
        val text = renderTelegramText(message)
        val image = message.filterIsInstance<Image>().firstOrNull()
        val file = message.filterIsInstance<FileMessage>().firstOrNull()
        val keyboard = message.filterIsInstance<ActionKeyboard>().firstOrNull()
        return when {
            image != null -> sendPhoto(id, image, text, replyTo)
            file != null -> {
                val url = file.info.url ?: throw IllegalArgumentException("Telegram file message requires a URL.")
                sendDocument(id, file.info.name, url, text.takeIf(String::isNotBlank), replyTo)
            }
            else -> sendText(id, text, replyTo, keyboard = keyboard)
        }
    }

    override fun sendForwardMessage(
        type: ChatType,
        target: Long,
        messages: List<ForwardMessageNode>,
    ): ForwardMessageResult {
        require(type == ChatType.PRIVATE || type == ChatType.GROUP) { "Unsupported Telegram chat type: $type" }
        require(messages.isNotEmpty()) { "Forward messages must not be empty." }
        logger.info("[SendForward] {} -> [{}] [{}] {} nodes", botID, type.name, target, messages.size)
        return synchronized(chatSendLock(target)) {
            val sentMessageIDs = mutableListOf<Long>()
            messages.forEach { node ->
                when (node) {
                    is ForwardMessageNode.CustomMessage -> {
                        val text = renderTelegramText(node.content)
                        if (text.isNotBlank()) {
                            val replyTo = node.content.filterIsInstance<Reply>().firstOrNull()?.id
                            sentMessageIDs += sendText(
                                chatID = target,
                                text = text,
                                replyTo = replyTo,
                                markdownV2 = true,
                                keyboard = node.content.filterIsInstance<ActionKeyboard>().firstOrNull(),
                            )
                        }
                    }
                    is ForwardMessageNode.ExistingMessage -> {
                        val sourceChat = messageChats[node.messageID] ?: return@forEach
                        val params = JsonObject().apply {
                            addProperty("chat_id", target)
                            addProperty("from_chat_id", sourceChat)
                            addProperty("message_id", node.messageID)
                        }
                        val result = call("forwardMessage", params).asJsonObject
                        sentMessageIDs += rememberMessage(result)
                    }
                }
            }
            check(sentMessageIDs.isNotEmpty()) { "No Telegram forward nodes could be delivered." }
            ForwardMessageResult(sentMessageIDs.first())
        }
    }

    override fun answerInlineQuery(
        queryID: String,
        results: List<InlineQueryResult>,
        nextOffset: String?,
    ): Boolean {
        require(queryID.isNotBlank()) { "Inline query ID must not be blank." }
        require(results.size <= MAX_INLINE_RESULTS) { "Telegram accepts at most $MAX_INLINE_RESULTS inline results." }
        val params = JsonObject().apply {
            addProperty("inline_query_id", queryID)
            add("results", JsonArray().apply {
                results.forEach { result ->
                    add(JsonObject().apply {
                        addProperty("type", "article")
                        addProperty("id", result.id)
                        addProperty("title", result.title.take(MAX_INLINE_TITLE_LENGTH))
                        result.description?.takeIf(String::isNotBlank)?.let {
                            addProperty("description", it.take(MAX_INLINE_DESCRIPTION_LENGTH))
                        }
                        result.url?.takeIf(String::isNotBlank)?.let { addProperty("url", it) }
                        add("input_message_content", JsonObject().apply {
                            addProperty(
                                "message_text",
                                renderTelegramMarkdownV2(result.message).take(MAX_MESSAGE_LENGTH),
                            )
                            addProperty("parse_mode", "MarkdownV2")
                        })
                    })
                }
            })
            addProperty("cache_time", 0)
            addProperty("is_personal", true)
            nextOffset?.let { addProperty("next_offset", it.take(64)) }
        }
        return call("answerInlineQuery", params).asBoolean
    }

    internal fun synchronizeCommands(): Boolean {
        val commands = registeredCommands()
            .asSequence()
            .filter { TELEGRAM_COMMAND_NAME.matches(it.name) }
            .take(MAX_BOT_COMMANDS)
            .toList()
        val params = JsonObject().apply {
            add("commands", JsonArray().apply {
                commands.forEach { command ->
                    add(JsonObject().apply {
                        addProperty("command", command.name)
                        addProperty(
                            "description",
                            command.description.replace(WHITESPACE, " ").trim().take(MAX_COMMAND_DESCRIPTION_LENGTH),
                        )
                    })
                }
            })
        }
        return call("setMyCommands", params).asBoolean
    }

    override fun recallMessage(messageID: Long): Boolean {
        val chatID = messageChats[messageID] ?: return false
        val params = JsonObject().apply {
            addProperty("chat_id", chatID)
            addProperty("message_id", messageID)
        }
        val deleted = call("deleteMessage", params).asBoolean
        if (deleted) messageChats.remove(messageID)
        return deleted
    }

    override fun sendFile(type: ChatType, target: Long, name: String, url: String): Boolean {
        require(type == ChatType.PRIVATE || type == ChatType.GROUP) { "Unsupported Telegram chat type: $type" }
        sendDocument(target, name, url)
        return true
    }

    override fun sendFile(type: ChatType, target: Long, name: String, file: File): Boolean {
        uploadDocument(type, target, name, file)
        return true
    }

    override val telegramBotId: Long
        get() = botID

    override fun uploadDocument(
        type: ChatType,
        target: Long,
        name: String,
        file: File,
    ): TelegramDocumentReceipt {
        require(type == ChatType.PRIVATE || type == ChatType.GROUP) { "Unsupported Telegram chat type: $type" }
        require(file.isFile) { "Telegram upload file does not exist: ${file.absolutePath}" }
        return synchronized(chatSendLock(target)) {
            val result = upload(
                method = "sendDocument",
                chatID = target,
                fieldName = "document",
                fileName = name,
                body = file.asRequestBody("application/octet-stream".toMediaType()),
            )
            documentReceipt(result.asJsonObject)
        }
    }

    override fun resendDocument(
        type: ChatType,
        target: Long,
        fileId: String,
    ): TelegramDocumentReceipt {
        require(type == ChatType.PRIVATE || type == ChatType.GROUP) { "Unsupported Telegram chat type: $type" }
        require(fileId.isNotBlank()) { "Telegram file ID must not be blank." }
        return synchronized(chatSendLock(target)) {
            val result = call("sendDocument", JsonObject().apply {
                addProperty("chat_id", target)
                addProperty("document", fileId)
            }).asJsonObject
            documentReceipt(result)
        }
    }

    internal fun exceedsOfficialUploadLimit(file: File): Boolean =
        usesOfficialBotApi && file.length() > OFFICIAL_MAX_UPLOAD_BYTES

    internal fun handleUpdate(update: JsonObject) {
        update.getAsJsonObject("message")?.let(::handleMessage)
        update.getAsJsonObject("callback_query")?.let(::handleCallbackQuery)
        if (inlineModeEnabled) {
            update.getAsJsonObject("inline_query")?.let(::handleInlineQuery)
        }
    }

    private suspend fun pollUpdates() {
        var offset = 0L
        while (connected.get()) {
            try {
                val params = JsonObject().apply {
                    addProperty("offset", offset)
                    addProperty("timeout", POLL_TIMEOUT_SECONDS)
                    add("allowed_updates", JsonArray().apply {
                        add("message")
                        add("callback_query")
                        if (inlineModeEnabled) add("inline_query")
                    })
                }
                call("getUpdates", params).asJsonArray.forEach { element ->
                    val update = element.asJsonObject
                    offset = maxOf(offset, update["update_id"].asLong + 1)
                    runCatching { handleUpdate(update) }
                        .onFailure { logger.error("Failed to handle Telegram update.", it) }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warn("Telegram polling failed; retrying.", exception)
                delay(RETRY_DELAY_MILLIS)
            }
        }
    }

    private fun handleMessage(message: JsonObject) {
        val text = message.get("text")?.asString ?: return
        val from = message.getAsJsonObject("from") ?: return
        if (from.get("is_bot")?.asBoolean == true) return
        val chat = message.getAsJsonObject("chat") ?: return
        val chatID = chat["id"].asLong
        val messageID = message["message_id"].asLong
        val normalizedText = normalizeTelegramCommand(text, botUsername, commandOperator) ?: return
        val sender = from.toUserInfo()
        val chain = MessageChain.text(normalizedText)
        messageChats[messageID] = chatID
        when (chat["type"].asString) {
            "private" -> eventBus.broadcast(
                PrivateMessageEvent(
                    botID = botID,
                    timestamp = message.get("date")?.asLong ?: Instant.now().epochSecond,
                    sender = sender,
                    message = chain,
                    messageID = messageID,
                )
            )
            "group", "supergroup" -> eventBus.broadcast(
                GroupMessageEvent(
                    botID = botID,
                    timestamp = message.get("date")?.asLong ?: Instant.now().epochSecond,
                    groupID = chatID,
                    sender = sender,
                    message = chain,
                    messageID = messageID,
                )
            )
        }
    }

    private fun handleInlineQuery(query: JsonObject) {
        val from = query.getAsJsonObject("from") ?: return
        if (from.get("is_bot")?.asBoolean == true) return
        eventBus.broadcast(
            InlineQueryEvent(
                botID = botID,
                sender = from.toUserInfo(),
                queryID = query["id"].asString,
                query = query.get("query")?.asString.orEmpty(),
                offset = query.get("offset")?.asString.orEmpty(),
            )
        )
    }

    private fun handleCallbackQuery(callback: JsonObject) {
        val callbackID = callback.get("id")?.asString ?: return
        val command = callback.get("data")?.asString ?: return
        val message = callback.getAsJsonObject("message") ?: return
        val from = callback.getAsJsonObject("from") ?: return
        call("answerCallbackQuery", JsonObject().apply { addProperty("callback_query_id", callbackID) })
        val synthetic = message.deepCopy().apply {
            addProperty("text", command)
            add("from", from)
        }
        handleMessage(synthetic)
    }

    private fun JsonObject.toUserInfo(): UserInfo {
        val displayName = listOfNotNull(
            get("first_name")?.asString,
            get("last_name")?.asString,
        ).joinToString(" ").ifBlank {
            get("username")?.asString ?: get("id").asString
        }
        return UserInfo(
            userID = get("id").asLong,
            username = displayName,
            card = get("username")?.asString,
        )
    }

    private fun sendText(
        chatID: Long,
        text: String,
        replyTo: Long? = null,
        markdownV2: Boolean = false,
        keyboard: ActionKeyboard? = null,
    ): Long {
        require(text.isNotBlank()) { "Telegram message text must not be blank." }
        var firstMessageID: Long? = null
        val renderedText = if (markdownV2) renderTelegramMarkdownV2(text) else text
        splitTelegramText(renderedText).forEachIndexed { index, chunk ->
            val params = JsonObject().apply {
                addProperty("chat_id", chatID)
                addProperty("text", chunk)
                if (markdownV2) addProperty("parse_mode", "MarkdownV2")
                if (index == 0) addReplyParameters(replyTo)
                if (index == 0 && keyboard != null) add("reply_markup", keyboard.toTelegramMarkup())
            }
            val messageID = rememberMessage(call("sendMessage", params).asJsonObject)
            if (firstMessageID == null) firstMessageID = messageID
        }
        return checkNotNull(firstMessageID)
    }

    private fun sendPhoto(chatID: Long, image: Image, caption: String, replyTo: Long?): Long {
        val resource = image.info.url ?: image.info.id
            ?: throw IllegalArgumentException("Telegram image requires a URL, file ID, or base64 payload.")
        val result = when {
            resource.startsWith("base64://") -> upload(
                method = "sendPhoto",
                chatID = chatID,
                fieldName = "photo",
                fileName = image.info.name,
                body = Base64.getDecoder()
                    .decode(resource.removePrefix("base64://"))
                    .toRequestBody("image/png".toMediaType()),
                caption = caption,
                replyTo = replyTo,
            )
            resource.startsWith("file://") -> {
                val file = fileFromResource(resource)
                upload(
                    method = "sendPhoto",
                    chatID = chatID,
                    fieldName = "photo",
                    fileName = image.info.name,
                    body = file.asRequestBody("application/octet-stream".toMediaType()),
                    caption = caption,
                    replyTo = replyTo,
                )
            }
            else -> call("sendPhoto", JsonObject().apply {
                addProperty("chat_id", chatID)
                addProperty("photo", resource)
                caption.takeIf(String::isNotBlank)?.let {
                    addProperty("caption", it.take(MAX_CAPTION_LENGTH))
                }
                addReplyParameters(replyTo)
            })
        }
        return rememberMessage(result.asJsonObject)
    }

    private fun sendDocument(
        chatID: Long,
        name: String,
        resource: String,
        caption: String? = null,
        replyTo: Long? = null,
    ): Long {
        val result = when {
            resource.startsWith("base64://") -> upload(
                method = "sendDocument",
                chatID = chatID,
                fieldName = "document",
                fileName = name,
                body = Base64.getDecoder()
                    .decode(resource.removePrefix("base64://"))
                    .toRequestBody("application/octet-stream".toMediaType()),
                caption = caption,
                replyTo = replyTo,
            )
            resource.startsWith("file://") || File(resource).isFile -> {
                val file = fileFromResource(resource)
                upload(
                    method = "sendDocument",
                    chatID = chatID,
                    fieldName = "document",
                    fileName = name,
                    body = file.asRequestBody("application/octet-stream".toMediaType()),
                    caption = caption,
                    replyTo = replyTo,
                )
            }
            else -> call("sendDocument", JsonObject().apply {
                addProperty("chat_id", chatID)
                addProperty("document", resource)
                caption?.takeIf(String::isNotBlank)?.let {
                    addProperty("caption", it.take(MAX_CAPTION_LENGTH))
                }
                addReplyParameters(replyTo)
            })
        }
        return rememberMessage(result.asJsonObject)
    }

    private fun upload(
        method: String,
        chatID: Long,
        fieldName: String,
        fileName: String,
        body: RequestBody,
        caption: String? = null,
        replyTo: Long? = null,
    ): JsonElement {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatID.toString())
            .addFormDataPart(fieldName, fileName, body)
            .apply {
                caption?.takeIf(String::isNotBlank)?.let {
                    addFormDataPart("caption", it.take(MAX_CAPTION_LENGTH))
                }
                replyTo?.let {
                    addFormDataPart(
                        "reply_parameters",
                        gson.toJson(JsonObject().apply { addProperty("message_id", it) }),
                    )
                }
            }
            .build()
        return execute(method, multipart)
    }

    private fun call(method: String, params: JsonObject = JsonObject()): JsonElement {
        return execute(method, gson.toJson(params).toRequestBody(JSON_MEDIA_TYPE))
    }

    private fun execute(method: String, body: RequestBody): JsonElement {
        val request = Request.Builder()
            .url("$apiRoot$method")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            val root = runCatching {
                JsonParser.parseString(responseBody).asJsonObject
            }.getOrNull()
            if (!response.isSuccessful || root?.get("ok")?.asBoolean != true) {
                val description = root?.get("description")?.asString
                    ?: responseBody.take(512).takeIf(String::isNotBlank)
                    ?: "HTTP ${response.code}"
                throw TelegramApiException(method, response.code, description)
            }
            return root.get("result")
        }
    }

    private fun JsonObject.addReplyParameters(replyTo: Long?) {
        replyTo?.let {
            add("reply_parameters", JsonObject().apply {
                addProperty("message_id", it)
                addProperty("allow_sending_without_reply", true)
            })
        }
    }

    private fun ActionKeyboard.toTelegramMarkup(): JsonObject = JsonObject().apply {
        add("inline_keyboard", JsonArray().apply {
            rows.forEach { row ->
                add(JsonArray().apply {
                    row.forEach { button ->
                        require(button.command.toByteArray(Charsets.UTF_8).size <= MAX_CALLBACK_DATA_BYTES) {
                            "Telegram callback command exceeds $MAX_CALLBACK_DATA_BYTES bytes."
                        }
                        add(JsonObject().apply {
                            addProperty("text", button.label)
                            addProperty("callback_data", button.command)
                        })
                    }
                })
            }
        })
    }

    private fun rememberMessage(message: JsonObject): Long {
        val messageID = message["message_id"].asLong
        val chatID = message.getAsJsonObject("chat")["id"].asLong
        messageChats[messageID] = chatID
        return messageID
    }

    private fun documentReceipt(message: JsonObject): TelegramDocumentReceipt {
        val messageID = rememberMessage(message)
        val document = message.getAsJsonObject("document")
            ?: throw IllegalStateException("Telegram sendDocument response did not contain a document.")
        return TelegramDocumentReceipt(
            messageId = messageID,
            fileId = document["file_id"]?.asString
                ?: throw IllegalStateException("Telegram document response did not contain a file_id."),
            fileUniqueId = document["file_unique_id"]?.asString,
        )
    }

    private fun chatSendLock(chatID: Long): Any =
        chatSendLocks[Math.floorMod(chatID.hashCode(), chatSendLocks.size)]

    private fun fileFromResource(resource: String): File {
        val file = if (resource.startsWith("file://")) {
            runCatching { File(URI(resource)) }
                .getOrElse { File(resource.removePrefix("file://")) }
        } else {
            File(resource)
        }
        require(file.isFile) { "Telegram upload file does not exist: ${file.absolutePath}" }
        return file
    }

    private companion object {
        const val DEFAULT_API_BASE_URL = "https://api.telegram.org"
        const val POLL_TIMEOUT_SECONDS = 25
        const val RETRY_DELAY_MILLIS = 2_000L
        const val MAX_MESSAGE_LENGTH = 4_096
        const val MAX_CAPTION_LENGTH = 1_024
        const val MAX_INLINE_RESULTS = 50
        const val MAX_INLINE_TITLE_LENGTH = 256
        const val MAX_INLINE_DESCRIPTION_LENGTH = 512
        const val MAX_BOT_COMMANDS = 100
        const val MAX_COMMAND_DESCRIPTION_LENGTH = 256
        const val MAX_CALLBACK_DATA_BYTES = 64
        const val CHAT_SEND_LOCK_COUNT = 64
        const val OFFICIAL_MAX_UPLOAD_BYTES = 50L * 1024 * 1024
        const val DEFAULT_UPLOAD_TIMEOUT_MINUTES = 60L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val TELEGRAM_COMMAND_NAME = Regex("[a-z0-9_]{1,32}")
        val WHITESPACE = Regex("\\s+")
    }
}

interface TelegramDocumentClient {
    val telegramBotId: Long

    fun uploadDocument(
        type: ChatType,
        target: Long,
        name: String,
        file: File,
    ): TelegramDocumentReceipt

    fun resendDocument(
        type: ChatType,
        target: Long,
        fileId: String,
    ): TelegramDocumentReceipt
}

data class TelegramDocumentReceipt(
    val messageId: Long,
    val fileId: String,
    val fileUniqueId: String?,
)

internal class TelegramApiException(
    val method: String,
    val statusCode: Int,
    val description: String,
) : IllegalStateException("Telegram API $method failed: $description") {
    fun isRequestEntityTooLarge(): Boolean =
        statusCode == 413 ||
            description.contains("Request Entity Too Large", ignoreCase = true) ||
            description.contains("file is too big", ignoreCase = true)

    fun isInvalidFileIdentifier(): Boolean =
        statusCode == 400 && INVALID_FILE_IDENTIFIER_MESSAGES.any {
            description.contains(it, ignoreCase = true)
        }

    private companion object {
        val INVALID_FILE_IDENTIFIER_MESSAGES = listOf(
            "wrong file identifier",
            "wrong remote file identifier",
            "file identifier is invalid",
            "file_reference_expired",
        )
    }
}

internal fun normalizeTelegramCommand(
    text: String,
    botUsername: String,
    commandOperator: Char = '/',
): String? {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith(commandOperator)) return text
    val commandToken = trimmed.substringBefore(' ')
    val separator = commandToken.indexOf('@')
    if (separator < 0) return trimmed
    val addressedBot = commandToken.substring(separator + 1)
    if (!addressedBot.equals(botUsername, ignoreCase = true)) return null
    val command = commandToken.substring(0, separator)
    return command + trimmed.substring(commandToken.length)
}

internal fun renderTelegramText(message: MessageChain): String =
    message.asSequence()
        .filterNot { it is Reply || it is Image || it is FileMessage || it is ActionKeyboard }
        .joinToString(separator = "") { item: AbstractMessageObject ->
            when (item) {
                is PlainText -> item.text
                is At -> "@${item.target}"
                is AtAll -> "@all"
                else -> item.toString()
            }
        }
        .trim()

internal fun renderTelegramMarkdownV2(text: String): String {
    val closingCode = text.lastIndexOf('`')
    val openingCode = if (closingCode > 0) text.lastIndexOf('`', closingCode - 1) else -1
    if (openingCode < 0) return escapeTelegramMarkdownV2(text)

    return buildString {
        append(escapeTelegramMarkdownV2(text.substring(0, openingCode)))
        append('`')
        append(
            text.substring(openingCode + 1, closingCode)
                .replace("\\", "\\\\")
                .replace("`", "\\`")
        )
        append('`')
        append(escapeTelegramMarkdownV2(text.substring(closingCode + 1)))
    }
}

private fun escapeTelegramMarkdownV2(text: String): String = buildString {
    text.forEach { character ->
        if (character in TELEGRAM_MARKDOWN_V2_SPECIAL_CHARACTERS) append('\\')
        append(character)
    }
}

internal fun splitTelegramText(text: String, limit: Int = 4_096): List<String> {
    require(limit > 0) { "Telegram message limit must be positive." }
    if (text.length <= limit) return listOf(text)
    val chunks = mutableListOf<String>()
    var remaining = text
    while (remaining.length > limit) {
        val newline = remaining.lastIndexOf('\n', startIndex = limit)
        val splitAt = newline.takeIf { it > 0 } ?: limit
        chunks += remaining.substring(0, splitAt).trimEnd()
        remaining = remaining.substring(splitAt).trimStart('\n')
    }
    if (remaining.isNotEmpty()) chunks += remaining
    return chunks
}

private const val TELEGRAM_MARKDOWN_V2_SPECIAL_CHARACTERS = "_*[]()~`>#+-=|{}.!"
