package me.heartalborada.bots.napcat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import me.heartalborada.commons.bots.*
import me.heartalborada.commons.bots.beans.FileInfo

class MessageChainTypeAdapter: TypeAdapter<MessageChain>() {
    override fun write(writer: JsonWriter, chain: MessageChain) {
        writer.beginArray()
        var isFirst = true
        chain.forEach {
            writer.beginObject()
            when (it) {
                is PlainText -> {
                    writer.name("type").value("text")
                    writer.name("data")
                    writer.beginObject()
                    writer.name("text").value(it.text)
                    writer.endObject()
                }
                is At -> {
                    writer.name("type").value("at")
                    writer.name("data")
                    writer.beginObject()
                    writer.name("qq").value(it.target)
                    writer.endObject()
                }
                is AtAll -> {
                    writer.name("type").value("at")
                    writer.name("data")
                    writer.beginObject()
                    writer.name("qq").value("all")
                    writer.endObject()
                }
                is Image -> {
                    writer.name("type").value("image")
                    writer.name("data")
                    writer.beginObject()
                    writer.name("url").value(it.url)
                    writer.endObject()
                }
                is File -> {
                    if (it.info.url != null) {
                        writer.name("type").value("forward")
                        writer.name("data")
                        writer.beginObject()
                        writer.name("file").value(it.info.url)
                        writer.endObject()
                    }
                }
                is Face -> {
                    writer.name("type").value("face")
                    writer.name("data")
                    writer.beginObject()
                    writer.name("id").value(it.id)
                    writer.endObject()
                }
                is Reply -> {
                    if (isFirst) {
                        writer.name("type").value("reply")
                        writer.name("data")
                        writer.beginObject()
                        writer.name("id").value(it.id)
                        writer.endObject()
                    }
                }
                is Dice -> writer.name("type").value("dice")
                is Rps -> writer.name("type").value("rps")
            }
            isFirst = false
            writer.endObject()
        }
        writer.endArray()
    }

    override fun read(reader: JsonReader): MessageChain {
        val chain = MessageChain()
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            var type: String? = null
            var data: JsonObject? = null
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "type" -> type = reader.nextString()
                    "data" -> data = JsonParser.parseReader(reader).asJsonObject
                }
            }
            when (type) {
                "text" -> chain.add(PlainText(data!!.getAsJsonPrimitive("text").asString))
                "at" -> {
                    val qq = data!!.getAsJsonPrimitive("qq").asString
                    if (qq == "all") {
                        chain.add(AtAll())
                    } else {
                        chain.add(At(qq.toLong()))
                    }
                }
                "image" -> chain.add(Image(data!!.getAsJsonPrimitive("url").asString))
                "file" -> {
                    val info = FileInfo(
                        data!!.getAsJsonPrimitive("file").asString,
                        data.getAsJsonPrimitive("file_size").asString.toLong(),
                        data.getAsJsonPrimitive("file_id").asString
                    )
                    chain.add(File(info))
                }
                "face" -> chain.add(Face(data!!.getAsJsonPrimitive("id").asString))
                "reply" -> chain.add(Reply(data!!.getAsJsonPrimitive("id").asString))
                "forward" -> chain.add(Forward(data!!.getAsJsonPrimitive("id").asString))
                "dice" -> chain.add(Dice(data!!.getAsJsonPrimitive("result").asInt))
                "rps" -> chain.add(Rps(RpsResult.fromValue(data!!.getAsJsonPrimitive("result").asInt)))
            }
            reader.endObject()
        }
        reader.endArray()
        return chain
    }

}
