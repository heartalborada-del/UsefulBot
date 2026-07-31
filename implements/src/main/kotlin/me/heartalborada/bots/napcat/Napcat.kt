package me.heartalborada.bots.napcat

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.nio.sctp.IllegalReceiveException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.heartalborada.bots.MessageChainTypeAdapter
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.AbstractBot
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.dto.ApiCommon
import me.heartalborada.commons.bots.dto.FileInfo
import me.heartalborada.commons.bots.dto.ForwardMessageNode
import me.heartalborada.commons.bots.dto.ForwardMessageResult
import me.heartalborada.commons.bots.dto.UserInfo
import me.heartalborada.commons.bots.events.EventBus
import me.heartalborada.commons.bots.events.message.GroupMessageEvent
import me.heartalborada.commons.bots.events.message.PrivateMessageEvent
import me.heartalborada.commons.bots.events.meta.BotOnlineEvent
import me.heartalborada.commons.bots.events.meta.BotOfflineEvent
import me.heartalborada.commons.bots.events.meta.HeartBeatEvent
import me.heartalborada.commons.bots.events.notice.*
import me.heartalborada.commons.bots.events.request.FriendAddRequestEvent
import me.heartalborada.commons.bots.events.request.GroupAddRequestEvent
import me.heartalborada.commons.i18n.Translator
import me.heartalborada.commons.utils.calculateSHA256
import me.heartalborada.commons.utils.toBase64
import me.heartalborada.i18n.PropertiesTranslator
import okhttp3.*
import okio.IOException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.EOFException
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

class Napcat(
    private val wsURL: String,
    private val token: String,
    commandStartWithAt: Boolean = true,
    commandOperator: Char = '/',
    commandDivider: Char = ' ',
    private val useStreamAPI: Boolean = false,
    private val streamAPIChunkSize: Int = 512 * 1024,
    private val streamAPIExpireSeconds: Long = 30 * 60 * 60 * 24,
    translator: Translator = PropertiesTranslator(),
    autoConnect: Boolean = true,
) : AbstractBot(commandStartWithAt, commandOperator, commandDivider, translator) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val connected = AtomicBoolean(false)
    private val offlinePublished = AtomicBoolean(true)
    private var eventWS: WebSocket? = null
    private var apiWS: WebSocket? = null
    private var httpClient = OkHttpClient.Builder().build()
    private val eventBus = EventBus()
    private val mutex = Mutex()
    private val gson = GsonBuilder().registerTypeAdapter(MessageChain::class.java, MessageChainTypeAdapter()).create()
    private val botContext: CoroutineContext by lazy {
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        SupervisorJob() + dispatcher + CoroutineName("NapcatScope")
    }
    private var botID: Long = 0L
    private val apiScope = CoroutineScope(botContext)
    private val pendingReqs = ConcurrentHashMap<String, CompletableDeferred<String>>()

    init {
        if (autoConnect) connect()
    }

    override fun close(): Boolean {
        val wasConnected = connected.getAndSet(false)
        if (wasConnected) publishOffline(expected = true, reason = "Closed")
        super.close()
        apiScope.cancel()
        val eventClosed = eventWS?.close(1000, "Close") ?: true
        val apiClosed = apiWS?.close(1000, "Close") ?: true
        return eventClosed && apiClosed
    }

    override fun connect(): Boolean {
        check(connected.compareAndSet(false, true)) { "NapCat bot is already connected." }
        return try {
            super.connect()
            eventWS = httpClient.newWebSocket(
                Request.Builder().url("${wsURL}/event").addHeader("Authorization", token).build(),
                EventListener()
            )
            apiWS = httpClient.newWebSocket(
                Request.Builder().url("${wsURL}/api").addHeader("Authorization", token).build(),
                ApiListener()
            )
            true
        } catch (exception: Exception) {
            connected.set(false)
            throw exception
        }
    }

    override fun getEventBus(): EventBus {
        return eventBus
    }

    override fun respondFriendRequest(requestFlag: String, approve: Boolean, remark: String?): Boolean =
        callOneBotAction(
            "set_friend_add_request",
            buildFriendRequestResponseParams(requestFlag, approve, remark),
        )

    override fun respondGroupRequest(
        requestFlag: String,
        requestType: GroupAddRequestEvent.ActionType,
        approve: Boolean,
        reason: String?,
    ): Boolean = callOneBotAction(
        "set_group_add_request",
        buildGroupRequestResponseParams(requestFlag, requestType, approve, reason),
    )

    override fun sendMessage(type: ChatType, id: Long, message: MessageChain): Long {
        logger.info("[Send] {} -> [{}] [{}] {}", botID, type.name, id, message.toString())
        return runBlocking {
            withContext(botContext) {
                mutex.withLock {
                    val uuid = UUID.randomUUID().toString()
                    try {
                        val data = mutableMapOf<String, Any>()
                        data["message"] = gson.toJsonTree(message)
                        val action = when (type) {
                            ChatType.GROUP -> {
                                data["group_id"] = id
                                "send_group_msg"
                            }

                            ChatType.PRIVATE -> {
                                data["user_id"] = id
                                "send_private_msg"
                            }

                            else -> throw IllegalArgumentException("Invalid chat type")
                        }
                        val responseDiffered = CompletableDeferred<String>()
                        pendingReqs[uuid] = responseDiffered
                        val sent = apiWS?.send(gson.toJson(ApiCommon(action, uuid, data))) == true
                        if (!sent) throw IllegalStateException("Failed to send message")
                        val response = withTimeoutOrNull(5000.milliseconds) {
                            responseDiffered.await().also {
                                pendingReqs.remove(uuid)
                            }
                        }.also { pendingReqs.remove(uuid) }
                        if (response == null) throw IOException("Timeout")
                        val root = JsonParser.parseString(response).asJsonObject
                        when (val code = root.getAsJsonPrimitive("retcode").asInt) {
                            0 -> return@withContext root.getAsJsonObject("data").getAsJsonPrimitive("message_id").asLong
                            else -> throw IllegalReceiveException(
                                "Invalid response code: $code, message: ${
                                    root.getAsJsonPrimitive(
                                        "message"
                                    ).asString
                                }"
                            )
                        }
                    } catch (e: Exception) {
                        pendingReqs.remove(uuid)
                        logger.error("An unexpected error occurred.", e)
                        throw e
                    }
                }
            }
        }
    }

    override fun sendForwardMessage(
        type: ChatType,
        target: Long,
        messages: List<ForwardMessageNode>,
    ): ForwardMessageResult {
        require(messages.isNotEmpty()) { "Forward messages must not be empty." }
        logger.info("[SendForward] {} -> [{}] [{}] {} nodes", botID, type.name, target, messages.size)
        return runBlocking {
            withContext(botContext) {
                mutex.withLock {
                    val uuid = UUID.randomUUID().toString()
                    try {
                        val data = buildForwardMessageParams(
                            type = type,
                            target = target,
                            messages = messages,
                            gson = gson,
                            defaultUserID = botID.takeIf { it > 0 } ?: INVALID_FORWARD_USER_ID,
                        )
                        val responseDeferred = CompletableDeferred<String>()
                        pendingReqs[uuid] = responseDeferred
                        val sent = apiWS?.send(gson.toJson(ApiCommon("send_forward_msg", uuid, data))) == true
                        if (!sent) throw IllegalStateException("Failed to send forward message")
                        val response = withTimeoutOrNull(5000.milliseconds) {
                            responseDeferred.await().also {
                                pendingReqs.remove(uuid)
                            }
                        }.also { pendingReqs.remove(uuid) }
                        if (response == null) throw IOException("Timeout")

                        val root = JsonParser.parseString(response).asJsonObject
                        when (val code = root.getAsJsonPrimitive("retcode").asInt) {
                            0 -> {
                                val responseData = root.getAsJsonObject("data")
                                return@withContext ForwardMessageResult(
                                    messageID = responseData.getAsJsonPrimitive("message_id").asLong,
                                    resourceID = responseData.get("res_id")
                                        ?.takeUnless { it.isJsonNull }
                                        ?.asString,
                                )
                            }

                            else -> throw IllegalReceiveException(
                                "Invalid response code: $code, message: ${
                                    root.getAsJsonPrimitive("message").asString
                                }"
                            )
                        }
                    } catch (e: Exception) {
                        pendingReqs.remove(uuid)
                        logger.error("An unexpected error occurred.", e)
                        throw e
                    }
                }
            }
        }
    }

    override fun recallMessage(messageID: Long): Boolean {
        logger.debug("[Recall] {} {}", botID, messageID)
        return runBlocking {
            withContext(botContext) {
                mutex.withLock {
                    val uuid = UUID.randomUUID().toString()
                    try {
                        val data = mutableMapOf<String, Any>().let {
                            it["message_id"] = messageID
                            return@let it
                        }
                        val responseDiffered = CompletableDeferred<String>()
                        pendingReqs[uuid] = responseDiffered
                        val sent = apiWS?.send(gson.toJson(ApiCommon("delete_msg", uuid, data))) == true
                        if (!sent) throw IllegalStateException("Failed to send message")
                        val response = withTimeoutOrNull(5000.milliseconds) {
                            responseDiffered.await().also {
                                pendingReqs.remove(uuid)
                            }
                        }.also { pendingReqs.remove(uuid) }
                        if (response == null) throw IOException("Timeout")
                        val root = JsonParser.parseString(response).asJsonObject
                        when (val code = root.getAsJsonPrimitive("retcode").asInt) {
                            0 -> return@withContext true
                            else -> throw IllegalReceiveException(
                                "Invalid response code: $code, message: ${
                                    root.getAsJsonPrimitive(
                                        "message"
                                    ).asString
                                }"
                            )
                        }
                    } catch (e: Exception) {
                        pendingReqs.remove(uuid)
                        logger.error("An unexpected error occurred.", e)
                        throw e
                    }
                }
            }
        }
    }

    override fun sendFile(type: ChatType, target: Long, name: String, file: File): Boolean {
        if (!file.exists() || !file.isFile) throw IllegalArgumentException("Invalid file.")
        return runBlocking {
            withContext(botContext) {
                val uuid = UUID.randomUUID().toString()
                if (useStreamAPI) {
                    val sha256 = file.calculateSHA256()
                    val size = file.length()
                    val chunks = if (size % streamAPIChunkSize == 0L) {
                        size / streamAPIChunkSize
                    } else {
                        size / streamAPIChunkSize + 1
                    }
                    try {
                        val shareParameter = mutableMapOf<String, Any>()
                        shareParameter["stream_id"] = uuid
                        shareParameter["total_chunks"] = chunks
                        shareParameter["file_size"] = size
                        shareParameter["expected_sha256"] = sha256
                        shareParameter["filename"] = name
                        shareParameter["file_retention"] = streamAPIExpireSeconds * 1000 // MILLISECONDS
                        file.inputStream().use { fis ->
                            val buffer = ByteArray(streamAPIChunkSize)
                            var chunkIndex = 0L
                            while (true) {
                                val readBytes = fis.read(buffer)
                                if (readBytes == -1) break // EOF
                                if (readBytes == 0) continue // defensive: should not happen
                                val echoID = "${uuid}_$chunkIndex"
                                val param = shareParameter.toMutableMap()
                                val b64 = buffer.copyOfRange(0, readBytes).toBase64()
                                param["chunk_index"] = chunkIndex
                                param["chunk_data"] = b64
                                val responseDiffered = CompletableDeferred<String>()
                                pendingReqs[echoID] = responseDiffered
                                val sent = apiWS?.send(
                                    gson.toJson(
                                        ApiCommon(
                                            "upload_file_stream",
                                            echoID,
                                            param
                                        )
                                    )
                                ) == true
                                if (!sent) {
                                    logger.error(
                                        "[Upload] {} -> [{}] [{}] {} Couldn't upload file chunk {}",
                                        botID,
                                        type.name,
                                        target,
                                        name,
                                        chunkIndex
                                    )
                                    throw IllegalStateException("Failed to send file chunk $chunkIndex")
                                }
                                val response = withTimeoutOrNull(50000.milliseconds) {
                                    responseDiffered.await().also {
                                        pendingReqs.remove(echoID)
                                    }
                                }.also { pendingReqs.remove(echoID) }
                                if (response == null) {
                                    pendingReqs.remove(echoID)
                                    throw IOException("Timeout")
                                }
                                val jo = JsonParser.parseString(response).asJsonObject
                                val rdata = jo.getAsJsonObject("data")
                                if (rdata.isJsonNull) return@withContext false
                                when (val code = jo.getAsJsonPrimitive("retcode").asInt) {
                                    0 -> {
                                        logger.debug(
                                            "[Upload] {} -> [{}] [{}] {} Successfully uploaded file chunk {}",
                                            botID,
                                            type.name,
                                            target,
                                            name,
                                            chunkIndex
                                        )
                                        chunkIndex++
                                        continue
                                    }

                                    else -> throw IllegalStateException(
                                        "Invalid response code: $code, message: ${
                                            jo.getAsJsonPrimitive(
                                                "message"
                                            ).asString
                                        }"
                                    )
                                }
                            }
                            shareParameter["total_chunks"] = chunkIndex
                        }
                        val echoID = uuid + "_merge"
                        val param = shareParameter.toMutableMap()
                        param["is_complete"] = true
                        val responseDiffered = CompletableDeferred<String>()
                        pendingReqs[echoID] = responseDiffered
                        val sent = apiWS?.send(
                            gson.toJson(
                                ApiCommon(
                                    "upload_file_stream",
                                    echoID,
                                    param
                                )
                            )
                        ) == true
                        if (!sent) {
                            logger.error(
                                "[Upload] {} -> [{}] [{}] {}: Couldn't request merge file chucks.",
                                botID,
                                type.name,
                                target,
                                name,
                            )
                            throw IllegalStateException("Failed to send merge request")
                        }
                        val response = withTimeoutOrNull(50000.milliseconds) {
                            responseDiffered.await().also {
                                pendingReqs.remove(echoID)
                            }
                        }.also { pendingReqs.remove(echoID) }
                        if (response == null) {
                            pendingReqs.remove(echoID)
                            throw IOException("Timeout")
                        }
                        val jo = JsonParser.parseString(response).asJsonObject
                        val rdata = jo.getAsJsonObject("data")
                        if (rdata.isJsonNull) return@withContext false
                        if (rdata.getAsJsonPrimitive("status").asString != "file_complete") {
                            throw IllegalStateException("File merge failed.")
                        }
                        logger.debug(
                            "[Upload] {} -> [{}] [{}] {}: Successfully merged the file.",
                            botID,
                            type.name,
                            target,
                            name,
                        )
                        val path = rdata.getAsJsonPrimitive("file_path").asString
                        return@withContext sendFile(type, target, name, "file://${path.replace("\\", "/")}")
                    } catch (e: Exception) {
                        if (e.message != "Timeout") {
                            return@withContext false
                        } else {
                            logger.error("An unexpected error occurred.", e)
                        }
                        throw e
                    }
                } else {
                    return@withContext sendFile(type, target, name, "base64://${file.toBase64()}")
                }
            }

        }
    }

    override fun sendFile(type: ChatType, target: Long, name: String, url: String): Boolean {
        logger.debug("[Send] {} -> [{}] [{}] {}", botID, type.name, target, name)
        if (url.trim() == "") throw IllegalArgumentException("Invalid url.")
        return runBlocking {
            withContext(botContext) {
                val uuid = UUID.randomUUID().toString()
                try {
                    val data = mutableMapOf<String, Any>()
                    data["file"] = url
                    data["name"] = name
                    val action = when (type) {
                        ChatType.GROUP -> {
                            data["group_id"] = target
                            "upload_group_file"
                        }

                        ChatType.PRIVATE -> {
                            data["user_id"] = target
                            "upload_private_file"
                        }

                        else -> throw IllegalArgumentException("Invalid chat type")
                    }
                    val responseDiffered = CompletableDeferred<String>()
                    pendingReqs[uuid] = responseDiffered
                    val sent = apiWS?.send(gson.toJson(ApiCommon(action, uuid, data))) == true
                    if (!sent) throw IllegalStateException("Failed to send file")
                    val response = withTimeoutOrNull(50000.milliseconds) {
                        responseDiffered.await().also {
                            pendingReqs.remove(uuid)
                        }
                    }.also { pendingReqs.remove(uuid) }
                    if (response == null) throw IOException("Timeout")
                    val root = JsonParser.parseString(response).asJsonObject
                    val rdata = root.getAsJsonObject("data")
                    if (rdata.isJsonNull) return@withContext false
                    when (val code = root.getAsJsonPrimitive("retcode").asInt) {
                        0 -> return@withContext true
                        else -> throw IllegalStateException(
                            "Invalid response code: $code, message: ${
                                root.getAsJsonPrimitive(
                                    "message"
                                ).asString
                            }"
                        )
                    }
                } catch (e: Exception) {
                    pendingReqs.remove(uuid)
                    if (e.message == "Timeout") {
                        return@withContext false
                    } else {
                        logger.error("An unexpected error occurred.", e)
                    }
                    throw e
                }

            }
        }
    }

    private inner class EventListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            logger.info("Connected to $wsURL")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            logger.info("Disconnected from $wsURL")
            eventWS?.close(1000, "Close")
            if (connected.getAndSet(false)) publishOffline(expected = false, reason = reason)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            logger.debug("[Event] $text")
            try {
                val root = JsonParser.parseString(text).asJsonObject
                val ts = root.getAsJsonPrimitive("time").asLong
                botID = root.getAsJsonPrimitive("self_id").asLong
                val post = root.getAsJsonPrimitive("post_type").asString
                when (post) {
                    "meta_event" -> {
                        when (root.getAsJsonPrimitive("meta_event_type").asString) {
                            "heartbeat" -> {
                                val online = root.getAsJsonObject("status").getAsJsonPrimitive("online").asBoolean
                                val good = root.getAsJsonObject("status").getAsJsonPrimitive("good").asBoolean
                                val event = HeartBeatEvent(online, good, botID, ts)
                                eventBus.broadcast(event)
                            }

                            "lifecycle" -> {
                                when (root.getAsJsonPrimitive("sub_type").asString) {
                                    "connect" -> {
                                        offlinePublished.set(false)
                                        val event = BotOnlineEvent(botID, ts)
                                        eventBus.broadcast(event)
                                    }
                                }
                            }
                        }
                    }

                    "message" -> {
                        val id = root.getAsJsonPrimitive("message_id").asLong
                        when (root.getAsJsonPrimitive("message_type").asString) {
                            "group" -> {
                                val groupID = root.getAsJsonPrimitive("group_id").asLong
                                val sender = root.getAsJsonObject("sender").let {
                                    UserInfo(
                                        it.getAsJsonPrimitive("user_id").asLong,
                                        it.getAsJsonPrimitive("nickname").asString,
                                        it.getAsJsonPrimitive("role").asString,
                                        it.getAsJsonPrimitive("card")?.asString
                                    )
                                }
                                val list = root.getAsJsonArray("message")
                                root.getAsJsonArray("message")
                                val chain = gson.fromJson(list, MessageChain::class.java)
                                val event = GroupMessageEvent(botID, ts, groupID, sender, chain, id)
                                eventBus.broadcast(event)
                            }

                            "private" -> {
                                val sender = root.getAsJsonObject("sender").let {
                                    UserInfo(
                                        it.getAsJsonPrimitive("user_id").asLong,
                                        it.getAsJsonPrimitive("nickname").asString,
                                        null,
                                        it.getAsJsonPrimitive("card")?.asString
                                    )
                                }
                                val list = root.getAsJsonArray("message")
                                root.getAsJsonArray("message")
                                val chain = gson.fromJson(list, MessageChain::class.java)
                                val event = PrivateMessageEvent(botID, ts, sender, chain, id)
                                eventBus.broadcast(event)
                            }
                        }
                    }

                    "notice" -> {
                        when (root.getAsJsonPrimitive("notice_type").asString) {
                            "group_upload" -> {
                                val groupID = root.getAsJsonPrimitive("group_id").asLong
                                val uploader = root.getAsJsonPrimitive("user_id").asLong
                                val fileInfo = root.getAsJsonObject("file").let {
                                    FileInfo(
                                        it.getAsJsonPrimitive("name").asString,
                                        it.getAsJsonPrimitive("size").asLong,
                                        it.getAsJsonPrimitive("id").asString
                                    )
                                }
                                val event = GroupFileUploadEvent(botID, ts, groupID, uploader, fileInfo)
                                eventBus.broadcast(event)
                            }

                            "group_admin" -> {
                                val groupID = root.getAsJsonPrimitive("group_id").asLong
                                val userID = root.getAsJsonPrimitive("user_id").asLong
                                val type = if (root.getAsJsonPrimitive("sub_type").asString == "set") {
                                    GroupAdminChangeEvent.ActionType.ADD
                                } else {
                                    GroupAdminChangeEvent.ActionType.REMOVE
                                }
                                val event = GroupAdminChangeEvent(botID, ts, groupID, userID, type)
                                eventBus.broadcast(event)
                            }

                            "group_decrease" -> {
                                val groupID = root.getAsJsonPrimitive("group_id").asLong
                                val userID = root.getAsJsonPrimitive("user_id").asLong
                                val operatorID = root.getAsJsonPrimitive("operator_id").asLong
                                val tts = root.getAsJsonPrimitive("sub_type").asString
                                val type = if (tts == "leave") {
                                    GroupMemberDecreaseEvent.ActionType.LEAVE
                                } else if (tts == "kick") {
                                    GroupMemberDecreaseEvent.ActionType.KICK
                                } else {
                                    GroupMemberDecreaseEvent.ActionType.KICK_BOT
                                }
                                val event = GroupMemberDecreaseEvent(botID, ts, groupID, userID, operatorID, type)
                                eventBus.broadcast(event)
                            }

                            "group_increase" -> {
                                val groupID = root.getAsJsonPrimitive("group_id").asLong
                                val userID = root.getAsJsonPrimitive("user_id").asLong
                                val operatorID = root.getAsJsonPrimitive("operator_id").asLong
                                val type = if (root.getAsJsonPrimitive("sub_type").asString == "approve") {
                                    GroupMemberIncreaseEvent.ActionType.APPROVE
                                } else {
                                    GroupMemberIncreaseEvent.ActionType.INVITE
                                }
                                val event = GroupMemberIncreaseEvent(botID, ts, groupID, userID, operatorID, type)
                                eventBus.broadcast(event)
                            }

                            "group_ban" -> {
                                val groupID = root.getAsJsonPrimitive("group_id").asLong
                                val userID = root.getAsJsonPrimitive("user_id").asLong
                                val operatorID = root.getAsJsonPrimitive("operator_id").asLong
                                val duration = root.getAsJsonPrimitive("duration").asLong
                                val type = if (root.getAsJsonPrimitive("sub_type").asString == "ban") {
                                    GroupMemberMuteEvent.ActionType.BAN
                                } else {
                                    GroupMemberMuteEvent.ActionType.PARDON
                                }
                                val event = GroupMemberMuteEvent(botID, ts, groupID, userID, operatorID, type, duration)
                                eventBus.broadcast(event)
                            }

                            "friend_add" -> {
                                val userID = root.getAsJsonPrimitive("user_id").asLong
                                val event = FriendAddEvent(botID, ts, userID)
                                eventBus.broadcast(event)
                            }

                            "group_recall" -> {
                                val groupID = root.getAsJsonPrimitive("group_id").asLong
                                val userID = root.getAsJsonPrimitive("user_id").asLong
                                val messageID = root.getAsJsonPrimitive("message_id").asLong
                                val operatorID = root.getAsJsonPrimitive("operator_id").asLong
                                val event = GroupRecallEvent(botID, ts, groupID, userID, operatorID, messageID)
                                eventBus.broadcast(event)
                            }

                            "friend_recall" -> {
                                val userID = root.getAsJsonPrimitive("user_id").asLong
                                val messageID = root.getAsJsonPrimitive("message_id").asLong
                                val event = PrivateRecallEvent(botID, ts, userID, messageID)
                                eventBus.broadcast(event)
                            }

                            "notify" -> {
                                when (root.getAsJsonPrimitive("sub_type").asString) {
                                    "poke" -> {
                                        val userID = root.getAsJsonPrimitive("user_id").asLong
                                        val groupID = root.getAsJsonPrimitive("group_id").asLong
                                        val targetID = root.getAsJsonPrimitive("target_id").asLong
                                        val event = GroupPokeEvent(botID, ts, groupID, userID, targetID)
                                        eventBus.broadcast(event)
                                    }

                                    "lucky_king" -> {
                                        val groupID = root.getAsJsonPrimitive("group_id").asLong
                                        val userID = root.getAsJsonPrimitive("user_id").asLong
                                        val targetID = root.getAsJsonPrimitive("target_id").asLong
                                        eventBus.broadcast(GroupLuckyKingEvent(botID, ts, groupID, userID, targetID))
                                    }

                                    "honor" -> {
                                        val groupID = root.getAsJsonPrimitive("group_id").asLong
                                        val userID = root.getAsJsonPrimitive("user_id").asLong
                                        val honorType = root.getAsJsonPrimitive("honor_type").asString
                                        eventBus.broadcast(GroupHonorChangeEvent(botID, ts, groupID, userID, honorType))
                                    }

                                    else -> logger.debug(
                                        "Unknown notify subtype: {}",
                                        root.getAsJsonPrimitive("sub_type").asString,
                                    )
                                }
                            }

                            else -> logger.debug("Unknown notice type: ${root.getAsJsonPrimitive("notice_type").asString}")
                        }
                    }

                    "request" -> {
                        when (root.getAsJsonPrimitive("request_type").asString) {
                            "friend" -> {
                                val userID = root.getAsJsonPrimitive("user_id").asLong
                                val comment = root.getAsJsonPrimitive("comment").asString
                                val flag = root.getAsJsonPrimitive("flag")?.asString.orEmpty()
                                val event = FriendAddRequestEvent(botID, ts, userID, comment, flag)
                                eventBus.broadcast(event)
                            }

                            "group" -> {
                                val groupID = root.getAsJsonPrimitive("group_id").asLong
                                val userID = root.getAsJsonPrimitive("user_id").asLong
                                val comment = root.getAsJsonPrimitive("comment").asString
                                val type = if (root.getAsJsonPrimitive("sub_type").asString == "invite") {
                                    GroupAddRequestEvent.ActionType.INVITE
                                } else {
                                    GroupAddRequestEvent.ActionType.ADD
                                }
                                val flag = root.getAsJsonPrimitive("flag")?.asString.orEmpty()
                                val event = GroupAddRequestEvent(botID, ts, groupID, userID, type, comment, flag)
                                eventBus.broadcast(event)
                            }

                            else -> logger.debug("Unknown request type: ${root.getAsJsonPrimitive("request_type").asString}")
                        }
                    }

                    else -> {
                        logger.debug("Unknown post type: $post")
                    }
                }
            } catch (t: Throwable) {
                logger.error("An unexpected error occurred.", t)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            logger.error("An unexpected error occurred.", t)
            if (connected.getAndSet(false)) publishOffline(expected = false, reason = t.message)
            logger.warn("NapCat event connection failed; other bot adapters will keep running.")
        }
    }

    private inner class ApiListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            logger.trace("[API] $text")
            apiScope.launch {
                try {
                    val json = JsonParser.parseString(text).asJsonObject
                    if (json.get("echo") is JsonNull) return@launch
                    json.get("echo")?.asString?.let { requestId ->
                        pendingReqs[requestId]?.complete(text)
                    }
                } catch (e: Exception) {
                    logger.error("An unexpected error occurred.", e)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (t is EOFException) {
                return
            }
            logger.error("An unexpected error occurred.", t)
        }
    }

    private fun callOneBotAction(action: String, params: Map<String, Any>): Boolean = runBlocking {
        withContext(botContext) {
            mutex.withLock {
                val requestID = UUID.randomUUID().toString()
                val response = try {
                    val deferred = CompletableDeferred<String>()
                    pendingReqs[requestID] = deferred
                    check(apiWS?.send(gson.toJson(ApiCommon(action, requestID, params))) == true) {
                        "Failed to send OneBot action $action"
                    }
                    withTimeoutOrNull(5_000.milliseconds) { deferred.await() }
                        ?: throw IOException("Timeout while executing OneBot action $action")
                } finally {
                    pendingReqs.remove(requestID)
                }
                val root = JsonParser.parseString(response).asJsonObject
                val code = root.getAsJsonPrimitive("retcode")?.asInt ?: -1
                check(code == 0) {
                    "OneBot action $action failed with code $code: ${root.get("message")?.asString.orEmpty()}"
                }
                true
            }
        }
    }

    private fun publishOffline(expected: Boolean, reason: String?) {
        if (botID == 0L || !offlinePublished.compareAndSet(false, true)) return
        runBlocking {
            eventBus.publish(BotOfflineEvent(botID, System.currentTimeMillis() / 1_000, expected, reason))
        }
    }
}

internal fun buildFriendRequestResponseParams(
    requestFlag: String,
    approve: Boolean,
    remark: String?,
): Map<String, Any> {
    require(requestFlag.isNotBlank()) { "Friend request flag must not be blank." }
    return buildMap {
        put("flag", requestFlag)
        put("approve", approve)
        remark?.let { put("remark", it) }
    }
}

internal fun buildGroupRequestResponseParams(
    requestFlag: String,
    requestType: GroupAddRequestEvent.ActionType,
    approve: Boolean,
    reason: String?,
): Map<String, Any> {
    require(requestFlag.isNotBlank()) { "Group request flag must not be blank." }
    return buildMap {
        put("flag", requestFlag)
        put("sub_type", requestType.name.lowercase(Locale.ROOT))
        put("approve", approve)
        reason?.let { put("reason", it) }
    }
}

internal fun buildForwardMessageParams(
    type: ChatType,
    target: Long,
    messages: List<ForwardMessageNode>,
    gson: Gson,
    defaultUserID: Long = INVALID_FORWARD_USER_ID,
): Map<String, Any> {
    require(messages.isNotEmpty()) { "Forward messages must not be empty." }
    val params = mutableMapOf<String, Any>(
        "message_type" to when (type) {
            ChatType.PRIVATE -> "private"
            ChatType.GROUP -> "group"
            else -> throw IllegalArgumentException("Invalid chat type")
        },
        "messages" to JsonArray().apply {
            messages.forEach { node ->
                add(
                    JsonObject().apply {
                        addProperty("type", "node")
                        add("data", node.toJson(gson, defaultUserID))
                    }
                )
            }
        },
    )
    when (type) {
        ChatType.PRIVATE -> params["user_id"] = target
        ChatType.GROUP -> params["group_id"] = target
    }
    return params
}

private fun ForwardMessageNode.toJson(gson: Gson, defaultUserID: Long): JsonObject = JsonObject().apply {
    when (this@toJson) {
        is ForwardMessageNode.ExistingMessage -> {
            addProperty("id", messageID)
        }

        is ForwardMessageNode.CustomMessage -> {
            addProperty("user_id", userID ?: defaultUserID)
            addProperty("nickname", nickname)
            add("content", gson.toJsonTree(content, MessageChain::class.java))
        }
    }
}

private const val INVALID_FORWARD_USER_ID = 0L
