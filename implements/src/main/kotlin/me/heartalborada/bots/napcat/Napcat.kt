package me.heartalborada.bots.napcat

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.time.withTimeoutOrNull
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.beans.FileInfo
import me.heartalborada.commons.bots.beans.UserInfo
import me.heartalborada.commons.bots.*
import me.heartalborada.commons.bots.events.EventBus
import me.heartalborada.commons.bots.events.HeartBeatEvent
import me.heartalborada.commons.bots.events.BotOnlineEvent
import me.heartalborada.commons.bots.events.GroupMessageEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

class Napcat(private var wsURL: String,private var token: String): AbstractBot() {
    private var isConnected = false
    private var eventWS: WebSocket? = null
    private var apiWS: WebSocket? = null
    private var httpClient = OkHttpClient()
    private val eventBus = EventBus()
    private val mutex = Mutex()
    private val botContext: CoroutineContext by lazy {
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            throwable.printStackTrace()
        }
        SupervisorJob() + dispatcher + exceptionHandler + CoroutineName("NapcatScope")
    }
    private val scope = CoroutineScope(botContext)
    private val pendingReqs = ConcurrentHashMap<String, CompletableDeferred<String>>()
    init {
        connect()
    }


    override fun close(): Boolean {
        scope.cancel()
        return eventWS?.close(1000, "Close") == true && apiWS?.close(1000, "Close") == true
    }

    override fun connect(): Boolean {
        if (isConnected) {
            throw RuntimeException("Already connected")
        }
        eventWS = httpClient.newWebSocket(
            Request.Builder().url("${wsURL}/ws/event").addHeader("Authorization", token).build(),
            EventListener()
        )
        apiWS = httpClient.newWebSocket(
            Request.Builder().url("${wsURL}/ws/api").addHeader("Authorization", token).build(),
            ApiListener()
        )
        return true
    }

    override fun getEventBus(): EventBus {
        return eventBus
    }

    override suspend fun sendMessage(type: ChatType, id: Long, message: MessageChain): Boolean =
        withContext(botContext) {
            mutex.withLock {
                val uuid = UUID.randomUUID().node().toString()
                try {
                    var root = JsonObject().let {
                        when (type) {
                            ChatType.GROUP -> it.addProperty("group_id", id)
                            ChatType.PRIVATE -> it.addProperty("user_id", id)
                            else -> throw IllegalArgumentException("Invalid chat type")
                        }
                        it.add("message", JsonObject())
                    }
                    val responseDiffered = CompletableDeferred<String>()
                    pendingReqs[uuid] = responseDiffered
                    val sent = apiWS?.send("") == true
                    if (!sent) return@withContext false
                    val response = withTimeoutOrNull(5000.milliseconds) {
                        responseDiffered.await().also {
                            pendingReqs.remove(uuid)
                        }
                    }.also { pendingReqs.remove(uuid) }
                    response
                    true
                } catch (e: Exception) {
                    pendingReqs.remove(uuid)
                    e.printStackTrace()
                    return@withContext false
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
            //println(text)
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
                            val chain = MessageChain()
                            val list = root.getAsJsonArray("message").asList()
                            list.forEach {
                                when(it.asJsonObject.getAsJsonPrimitive("type").asString) {
                                    "text" -> chain.add(PlainText(it.asJsonObject.getAsJsonObject("data").getAsJsonPrimitive("text").asString))
                                    "at" -> {
                                        val qq = it.asJsonObject.getAsJsonObject("data").getAsJsonPrimitive("qq").asString
                                        if (qq == "all") {
                                            chain.add(AtAll())
                                        } else {
                                            chain.add(At(qq.toLong()))
                                        }
                                    }
                                    "image" -> chain.add(Image(it.asJsonObject.getAsJsonObject("data").getAsJsonPrimitive("url").asString))
                                    "file" -> {
                                        val data = it.asJsonObject.getAsJsonObject("data")
                                        val info = FileInfo(
                                            data.getAsJsonPrimitive("file").asString,
                                            data.getAsJsonPrimitive("file_size").asString.toLong(),
                                            data.getAsJsonPrimitive("file_id").asString
                                            )
                                        chain.add(File(info))
                                    }
                                    "face" -> {
                                        chain.add(
                                            Face(
                                                it.asJsonObject.getAsJsonObject("data")
                                                    .getAsJsonPrimitive("id").asString
                                            )
                                        )
                                    }
                                    "reply" -> chain.add(Reply(it.asJsonObject.getAsJsonObject("data").getAsJsonPrimitive("id").asString))
                                    "forward" -> chain.add(Forward(it.asJsonObject.getAsJsonObject("data").getAsJsonPrimitive("id").asString))
                                }
                            }
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
            scope.launch {
                try {
                    val json = JsonParser.parseString(text).asJsonObject
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