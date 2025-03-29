package me.heartalborada.bots.napcat

import com.google.gson.JsonParser
import me.heartalborada.commons.bots.beans.FileInfo
import me.heartalborada.commons.bots.beans.UserInfo
import me.heartalborada.commons.bots.*
import me.heartalborada.commons.events.EventBus
import me.heartalborada.commons.events.HeartBeatEvent
import me.heartalborada.commons.events.BotOnlineEvent
import me.heartalborada.commons.events.GroupMessageEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class Napcat(private var wsURL: String,private var token: String): AbstractBot() {
    private var isConnected = false
    private var eventWS: WebSocket? = null
    private var apiWS: WebSocket? = null
    private var httpClient = OkHttpClient()
    private val eventBus = EventBus()
    init {
        connect()
    }

    override fun close(): Boolean {
        return eventWS?.close(1000, "Close") == true && apiWS?.close(1000, "Close") == true
    }

    override fun connect(): Boolean {
        if (isConnected) {
            throw RuntimeException("Already connected")
        }
        eventWS = httpClient.newWebSocket(
            Request.Builder().url("${wsURL}/ws/event").addHeader("Authorization", token).build(),
            EventListener(eventBus)
        )
        return true
    }

    override fun getEventBus(): EventBus {
        return eventBus
    }

    private class EventListener(val eventBus: EventBus): WebSocketListener() {
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
                                    "text" -> chain.append(PlainText(it.asJsonObject.getAsJsonObject("data").getAsJsonPrimitive("text").asString))
                                    "at" -> {
                                        val qq = it.asJsonObject.getAsJsonObject("data").getAsJsonPrimitive("qq").asString
                                        if (qq == "all") {
                                            chain.append(AtAll())
                                        } else {
                                            chain.append(At(qq.toLong()))
                                        }
                                    }
                                    "image" -> chain.append(Image(it.asJsonObject.getAsJsonObject("data").getAsJsonPrimitive("url").asString))
                                    "file" -> {
                                        val data = it.asJsonObject.getAsJsonObject("data")
                                        val info = FileInfo(
                                            data.getAsJsonPrimitive("file").asString,
                                            data.getAsJsonPrimitive("file_size").asString.toLong(),
                                            data.getAsJsonPrimitive("file_id").asString
                                            )
                                        chain.append(File(info))
                                    }
                                    "face" -> {
                                        chain.append(
                                            Face(
                                                it.asJsonObject.getAsJsonObject("data")
                                                    .getAsJsonPrimitive("id").asString
                                            )
                                        )
                                    }
                                    "reply" -> chain.append(Reply(it.asJsonObject.getAsJsonObject("data").getAsJsonPrimitive("id").asString))
                                    "forward" -> chain.append(Forward(it.asJsonObject.getAsJsonObject("data").getAsJsonPrimitive("id").asString))
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
}