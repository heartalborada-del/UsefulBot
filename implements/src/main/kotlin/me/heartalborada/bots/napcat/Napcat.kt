package me.heartalborada.bots.napcat

import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.sun.nio.sctp.IllegalReceiveException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.heartalborada.bots.MessageChainTypeAdapter
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.AbstractBot
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.beans.ApiCommon
import me.heartalborada.commons.bots.beans.FileInfo
import me.heartalborada.commons.bots.beans.UserInfo
import me.heartalborada.commons.bots.events.EventBus
import me.heartalborada.commons.bots.events.message.GroupMessageEvent
import me.heartalborada.commons.bots.events.message.PrivateMessageEvent
import me.heartalborada.commons.bots.events.meta.BotOnlineEvent
import me.heartalborada.commons.bots.events.meta.HeartBeatEvent
import me.heartalborada.commons.bots.events.notice.*
import me.heartalborada.commons.bots.events.request.FriendAddRequestEvent
import me.heartalborada.commons.bots.events.request.GroupAddRequestEvent
import okhttp3.*
import okio.IOException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.EOFException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

class Napcat(
    private var wsURL: String,
    private var token: String,
    isCommandStartWithAt: Boolean = true,
    commandOperator: Char = '/',
    commandDivider: Char = ' ',
) : AbstractBot(isCommandStartWithAt, commandOperator, commandDivider) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private var isConnected = false
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
        connect()
    }

    override fun close(): Boolean {
        super.close()
        apiScope.cancel()
        return eventWS?.close(1000, "Close") == true && apiWS?.close(1000, "Close") == true
    }

    override fun connect(): Boolean {
        if (isConnected) {
            throw RuntimeException("Already connected")
        }
        eventWS = httpClient.newWebSocket(
            Request.Builder().url("${wsURL}/event").addHeader("Authorization", token).build(),
            EventListener()
        )
        apiWS = httpClient.newWebSocket(
            Request.Builder().url("${wsURL}/api").addHeader("Authorization", token).build(),
            ApiListener()
        )
        super.connect()
        return true
    }

    override fun getEventBus(): EventBus {
        return eventBus
    }

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

    override fun sendFile(type: ChatType, id: Long, fileInfo: FileInfo): Long {
        logger.debug("[Send] {} -> [{}] [{}] {}", botID, type.name, id, fileInfo.name)
        if (fileInfo.url == null) throw IllegalArgumentException("Invalid file url")
        return runBlocking {
            withContext(botContext) {
                mutex.withLock {
                    val uuid = UUID.randomUUID().toString()
                    try {
                        val data = mutableMapOf<String, Any>()
                        data["file"] = fileInfo.url!!
                        data["name"] = fileInfo.name
                        val action = when (type) {
                            ChatType.GROUP -> {
                                data["group_id"] = id
                                "upload_group_file"
                            }

                            ChatType.PRIVATE -> {
                                data["user_id"] = id
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
                        if (rdata.isJsonNull) return@withContext -1
                        when (val code = root.getAsJsonPrimitive("retcode").asInt) {
                            0 -> return@withContext rdata.getAsJsonPrimitive("message_id").asLong
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
                        if (e.message != "Timeout") {
                            return@withContext -1
                        } else {
                            logger.error("An unexpected error occurred.", e)
                        }
                        throw e
                    }
                }
            }
        }
    }

    private inner class EventListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            logger.info("Connected to $wsURL")
            isConnected = true
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            logger.info("Disconnected from $wsURL")
            eventWS?.close(1000, "Close")
            isConnected = false
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            logger.trace("Event Raw Json: $text")
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
                                    //TODO MORE
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
                                val event = FriendAddRequestEvent(botID, ts, userID, comment)
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
                                val event = GroupAddRequestEvent(botID, ts, groupID, userID, type, comment)
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
            logger.error("Exit.")
            exitProcess(0)
        }
    }

    private inner class ApiListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            logger.trace("Api Raw Json: $text")
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
}