package me.heartalborada.bots

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import me.heartalborada.commons.bots.*
import me.heartalborada.commons.bots.beans.FileInfo
import okhttp3.internal.toLongOrDefault

class MessageChainTypeAdapter : TypeAdapter<MessageChain>() {
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
                    writer.name("url").value(it.info.url)
                    writer.endObject()
                }
                //don't parse it
                /*is File -> {
                    if (it.info.url != null) {
                        writer.name("type").value("file")
                        writer.name("data")
                        writer.beginObject()
                        writer.name("file").value(it.info.url)
                        writer.endObject()
                    }
                }*/

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
                is Poke -> {
                    writer.name("type").value("poke")
                    writer.name("data")
                    writer.beginObject()
                    writer.name("id").value(it.id)
                    writer.name("type").value(it.type)
                    writer.endObject()
                }

                is Share -> {
                    writer.name("type").value("share")
                    writer.name("data")
                    writer.beginObject()
                    writer.name("title").value(it.title)
                    writer.name("url").value(it.url)
                    if (it.content != null) writer.name("content").value(it.content)
                    if (it.imageUrl != null) writer.name("image").value(it.imageUrl)
                    writer.endObject()
                }

                is Contact -> {
                    writer.name("type").value("contact")
                    writer.name("data")
                    writer.beginObject()
                    writer.name("id").value(it.id)
                    writer.name("type").value(it.type.value)
                    writer.endObject()
                }

                is Location -> {
                    writer.name("type").value("location")
                    writer.name("data")
                    writer.beginObject()
                    writer.name("lat").value(it.latitude)
                    writer.name("lon").value(it.longitude)
                    if (it.title != null) writer.name("title").value(it.title)
                    if (it.content != null) writer.name("content").value(it.content)
                    writer.endObject()
                }

                is Xml -> {
                    writer.name("type").value("xml")
                    writer.name("data")
                    writer.beginObject()
                    writer.name("data").value(it.data)
                    writer.endObject()
                }

                is Json -> {
                    writer.name("type").value("json")
                    writer.name("data")
                    writer.beginObject()
                    writer.name("data").value(it.data)
                    writer.endObject()
                }
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
                        chain.add(At(qq.toLongOrDefault(-1)))
                    }
                }

                "image" -> {
                    val info = FileInfo(
                        data!!.getAsJsonPrimitive("file").asString,
                        data.getAsJsonPrimitive("file_size").asString.toLongOrDefault(-1),
                        data.getAsJsonPrimitive("file").asString,
                        data.getAsJsonPrimitive("url").asString
                    )
                    chain.add(Image(info))
                }

                "file" -> {
                    val info = FileInfo(
                        data!!.getAsJsonPrimitive("file").asString,
                        data.getAsJsonPrimitive("file_size").asString.toLongOrDefault(-1),
                        data.getAsJsonPrimitive("file_id").asString
                    )
                    chain.add(File(info))
                }

                "face" -> chain.add(Face(data!!.getAsJsonPrimitive("id").asString))
                "reply" -> chain.add(Reply(data!!.getAsJsonPrimitive("id").asString.toLongOrDefault(-1)))
                "forward" -> chain.add(Forward(data!!.getAsJsonPrimitive("id").asString))
                "dice" -> chain.add(Dice(data!!.getAsJsonPrimitive("result").asInt))
                "rps" -> chain.add(Rps(Rps.RpsResult.fromValue(data!!.getAsJsonPrimitive("result").asInt)))
                "poke" -> chain.add(
                    Poke(
                        data!!.getAsJsonPrimitive("id").asString.toIntOrNull() ?: -1,
                        data.getAsJsonPrimitive("type").asString.toIntOrNull() ?: -1
                    )
                )

                "share" -> chain.add(
                    Share(
                        data!!.getAsJsonPrimitive("title").asString,
                        data.getAsJsonPrimitive("url").asString,
                        data.getAsJsonPrimitive("content").asString,
                        data.getAsJsonPrimitive("image").asString
                    )
                )

                "contact" -> {
                    val id = data!!.getAsJsonPrimitive("id").asString.toLongOrDefault(-1)
                    val type = Contact.ContactType.fromValue(data.getAsJsonPrimitive("type").asString)
                    chain.add(Contact(type, id))
                }

                "location" -> chain.add(
                    Location(
                        data!!.getAsJsonPrimitive("lat").asString,
                        data.getAsJsonPrimitive("lon").asString,
                        data.getAsJsonPrimitive("title").asString,
                        data.getAsJsonPrimitive("content").asString,
                    )
                )

                "xml" -> chain.add(Xml(data!!.getAsJsonPrimitive("data").asString))
                "json" -> chain.add(Json(data!!.getAsJsonPrimitive("data").asString))
                else -> chain.add(Unknown(Gson().toJson(data)))
            }
            reader.endObject()
        }
        reader.endArray()
        return chain
    }
}