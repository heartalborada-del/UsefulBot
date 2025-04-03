package me.heartalborada.bots.napcat

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import me.heartalborada.commons.bots.*

class MessageChainTypeAdapter: TypeAdapter<MessageChain>() {
    override fun write(out: JsonWriter, chain: MessageChain) {
        out.beginObject()
        chain.forEach {
            when (it) {
                is PlainText -> {
                    out.name("type").value("text")
                }
                is At -> {
                    out.name("type").value("at")
                    out.name("qq").value(it.target)
                }
                is AtAll -> {
                    out.name("type").value("at")
                    out.name("qq").value("all")
                }
                is Image -> {
                    out.name("type").value("image")
                    out.name("url").value(it.url)
                }
            }
        }
    }

    override fun read(out: JsonReader?): MessageChain {
        TODO("Not yet implemented")
    }

}