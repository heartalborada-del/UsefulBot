package me.heartalborada.bots.napcat

import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.beans.FileInfo
import me.heartalborada.commons.bots.beans.UserInfo
import me.heartalborada.commons.bots.*
import me.heartalborada.commons.bots.beans.ApiCommon
import me.heartalborada.commons.bots.events.EventBus
import me.heartalborada.commons.bots.events.HeartBeatEvent
import me.heartalborada.commons.bots.events.BotOnlineEvent
import me.heartalborada.commons.bots.events.GroupMessageEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.toKotlinUuid

class Napcat(private var wsURL: String,private var token: String): AbstractBot() {
    private var isConnected = false
    private var eventWS: WebSocket? = null
    private var apiWS: WebSocket? = null
    private var httpClient = OkHttpClient()
    private val eventBus = EventBus()
    private val mutex = Mutex()
    private val gson = GsonBuilder().registerTypeAdapter(MessageChain::class.java,MessageChainTypeAdapter()).create()
    private val botContext: CoroutineContext by lazy {
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            throwable.printStackTrace()
        }
        SupervisorJob() + dispatcher + exceptionHandler + CoroutineName("NapcatScope")
    }
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

    override fun sendMessage(type: ChatType, id: Long, message: MessageChain): Boolean {
        return runBlocking {
            withContext(botContext) {
                mutex.withLock {
                    val uuid = UUID.randomUUID().toString()
                    try {
                        val action = when (type) {
                            ChatType.GROUP -> "send_group_msg"
                            ChatType.PRIVATE -> "send_private_msg"
                            else -> throw IllegalArgumentException("Invalid chat type")
                        }
                        val root = mutableMapOf<String,Any>().let {
                            when (type) {
                                ChatType.GROUP -> it["group_id"] = id
                                ChatType.PRIVATE -> it["user_id"] = id
                                else -> throw IllegalArgumentException("Invalid chat type")
                            }
                            it.put("message", gson.toJsonTree(message))
                            return@let it
                        }
                        val responseDiffered = CompletableDeferred<String>()
                        pendingReqs[uuid] = responseDiffered
                        val sent = apiWS?.send(gson.toJson(ApiCommon(action,uuid,root))) == true
                        if (!sent) return@withContext false
                        val response = withTimeoutOrNull(5000.milliseconds) {
                            responseDiffered.await().also {
                                pendingReqs.remove(uuid)
                            }
                        }.also { pendingReqs.remove(uuid) }
                        if (response == null) return@withContext false
                        println(response)
                        return@withContext true
                    } catch (e: Exception) {
                        pendingReqs.remove(uuid)
                        e.printStackTrace()
                        return@withContext false
                    }
                }
            }
        }
    }


    override suspend fun recallMessage(messageID: Long): Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun getMessageByID(messageID: Long): MessageChain? {
        TODO("Not yet implemented")
    }

    override suspend fun getImageUrlByID(imageID: String): String? {
        TODO("Not yet implemented")
    }

    override suspend fun getFileByID(fileID: String): FileInfo? {
        TODO("Not yet implemented")
    }

    private inner class EventListener: WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val root = JsonParser.parseString(text).asJsonObject
            val ts = root.getAsJsonPrimitive("time").asLong
            val botID = root.getAsJsonPrimitive("self_id").asLong
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
                            val event = GroupMessageEvent(botID, ts, groupID, sender, chain)
                            eventBus.broadcast(event)
                        }
                    }
                }
            }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            println("Something went wrong")
            t.printStackTrace()
        }
    }

    private inner class ApiListener: WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            apiScope.launch {
                try {
                    val json = JsonParser.parseString(text).asJsonObject
                    if (json.get("echo") is JsonNull) return@launch
                    json.get("echo")?.asString?.let { requestId ->
                        pendingReqs[requestId]?.complete(text)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            println("Something went wrong")
            t.printStackTrace()
        }
    }
}