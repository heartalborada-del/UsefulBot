package me.heartalborada.bots

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.internal.Streams
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import me.heartalborada.commons.bots.*
import me.heartalborada.commons.bots.dto.FileInfo
import okhttp3.internal.toLongOrDefault

class MessageChainTypeAdapter(
    private val allowMarkdown: Boolean = false,
) : TypeAdapter<MessageChain>() {
    override fun write(writer: JsonWriter, chain: MessageChain) {
        Streams.write(serializeChain(chain), writer)
    }

    override fun read(reader: JsonReader): MessageChain {
        val value = JsonParser.parseReader(reader)
        if (!value.isJsonArray) return MessageChain()
        return fromJson(value.asJsonArray)
    }

    private fun serializeChain(chain: MessageChain): JsonArray = JsonArray().apply {
        chain.mapNotNull(::serializeSegment).forEach(::add)
    }

    private fun serializeSegment(message: AbstractMessageObject): JsonObject? {
        val data = JsonObject()
        val type = when (message) {
            is PlainText -> "text".also { data.addProperty("text", message.text) }
            is At -> "at".also { data.addProperty("qq", message.target) }
            is AtAll -> "at".also { data.addProperty("qq", "all") }
            is Image -> "image".also {
                data.addProperty("file", message.info.resource())
                data.addOptional("name", message.info.name)
                data.addOptional("summary", message.summary)
                data.addOptional("sub_type", message.subType)
            }
            is Record -> "record".also {
                data.addProperty("file", message.info.resource())
                data.addOptional("name", message.info.name)
            }
            is Video -> "video".also {
                data.addProperty("file", message.info.resource())
                data.addOptional("name", message.info.name)
                data.addOptional("thumb", message.thumbnail)
            }
            is Face -> "face".also { data.addProperty("id", message.id) }
            is Reply -> "reply".also { data.addProperty("id", message.id) }
            is Forward -> "forward".also { data.addProperty("id", message.id) }
            is ForwardNode -> "node".also {
                data.addOptional("id", message.id)
                message.content?.let { content -> data.add("content", serializeChain(content)) }
                message.userId?.let { userId -> data.addProperty("user_id", userId) }
                data.addOptional("nickname", message.nickname)
            }
            is Dice -> "dice"
            is Rps -> "rps"
            is Contact -> if (message.type != Contact.ContactType.TELEGRAM) "contact".also {
                data.addProperty("type", message.type.value)
                data.addProperty("id", message.id)
            } else return null
            is Music.Standard -> "music".also {
                data.addProperty("type", message.source.value)
                data.addProperty("id", message.id)
            }
            is Music.Custom -> "music".also {
                data.addProperty("type", "custom")
                data.addProperty("url", message.url)
                data.addProperty("audio", message.audio)
                data.addProperty("title", message.title)
                data.addOptional("image", message.image)
                data.addOptional("singer", message.singer)
            }
            is Json -> "json".also { data.addProperty("data", message.data) }
            is MarketFace -> "mface".also {
                data.addProperty("emoji_id", message.emojiId)
                data.addProperty("emoji_package_id", message.emojiPackageId)
                data.addProperty("key", message.key)
                data.addOptional("summary", message.summary)
            }
            is File -> "file".also {
                data.addProperty("file", message.info.resource())
                data.addOptional("name", message.info.name)
            }
            is Markdown -> if (allowMarkdown) {
                "markdown".also { data.addProperty("content", message.content) }
            } else {
                return null
            }
            is ActionKeyboard -> "text".also { data.addProperty("text", message.toString()) }
            is Poke, is Shake, is Share, is Location, is Xml, is Unknown -> return null
            else -> return null
        }
        return JsonObject().apply {
            addProperty("type", type)
            add("data", data)
        }
    }

    private fun fromJson(segments: JsonArray): MessageChain = MessageChain().apply {
        segments.forEach { value ->
            val raw = value.takeIf { it.isJsonObject }?.asJsonObject
            val type = raw?.stringOrNull("type")
            val data = raw?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            add(fromJson(type, data) ?: Unknown(raw?.toString() ?: value.toString()))
        }
    }

    private fun fromJson(type: String?, data: JsonObject?): AbstractMessageObject? {
        if (type == null || data == null) return null
        return when (type) {
            "text" -> data.stringOrNull("text")?.let(::PlainText)
            "face" -> data.stringOrNull("id")?.let(::Face)
            "image" -> Image(
                info = data.fileInfo(fallbackIdToFile = true),
                summary = data.stringOrNull("summary"),
                subType = data.stringOrNull("sub_type"),
            )
            "record" -> Record(data.fileInfo(fallbackIdToFile = true))
            "video" -> Video(data.fileInfo(fallbackIdToFile = true), data.stringOrNull("thumb"))
            "at" -> when (val target = data.stringOrNull("qq")) {
                null -> null
                "all" -> AtAll()
                else -> At(target.toLongOrDefault(-1))
            }
            "rps" -> data.intOrNull("result")?.let { Rps(Rps.RpsResult.fromValue(it)) }
            "dice" -> data.intOrNull("result")?.let(::Dice)
            "shake" -> Shake()
            "poke" -> Poke(data.intOrNull("id") ?: -1, data.intOrNull("type") ?: -1)
            "share" -> Share(
                title = data.stringOrNull("title").orEmpty(),
                url = data.stringOrNull("url").orEmpty(),
                content = data.stringOrNull("content"),
                imageUrl = data.stringOrNull("image"),
            )
            "contact" -> runCatching {
                Contact(
                    Contact.ContactType.fromValue(data.stringOrNull("type").orEmpty()),
                    data.stringOrNull("id")?.toLongOrDefault(-1) ?: -1,
                )
            }.getOrNull()
            "location" -> Location(
                latitude = data.stringOrNull("lat").orEmpty(),
                longitude = data.stringOrNull("lon").orEmpty(),
                title = data.stringOrNull("title"),
                content = data.stringOrNull("content"),
            )
            "music" -> music(data)
            "reply" -> data.stringOrNull("id")?.toLongOrDefault(-1)?.let(::Reply)
            "forward" -> data.stringOrNull("id")?.let(::Forward)
            "node" -> node(data)
            "json" -> data.stringOrNull("data")?.let(::Json)
            "mface" -> MarketFace(
                emojiId = data.stringOrNull("emoji_id").orEmpty(),
                emojiPackageId = data.stringOrNull("emoji_package_id").orEmpty(),
                key = data.stringOrNull("key").orEmpty(),
                summary = data.stringOrNull("summary"),
            )
            "file" -> File(data.fileInfo())
            "markdown" -> data.stringOrNull("content")?.let(::Markdown)
            "xml" -> data.stringOrNull("data")?.let(::Xml)
            else -> null
        }
    }

    private fun music(data: JsonObject): Music? = when (val source = data.stringOrNull("type")) {
        "custom" -> Music.Custom(
            url = data.stringOrNull("url").orEmpty(),
            audio = data.stringOrNull("audio").orEmpty(),
            title = data.stringOrNull("title").orEmpty(),
            image = data.stringOrNull("image"),
            singer = data.stringOrNull("singer"),
        )
        null -> null
        else -> runCatching {
            Music.Standard(Music.Source.fromValue(source), data.stringOrNull("id").orEmpty())
        }.getOrNull()
    }

    private fun node(data: JsonObject): ForwardNode? {
        val id = data.stringOrNull("id")
        val content = data.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.let(::fromJson)
        if (id == null && content == null) return null
        return ForwardNode(
            id = id,
            content = content,
            userId = data.stringOrNull("user_id")?.toLongOrNull(),
            nickname = data.stringOrNull("nickname"),
        )
    }
}

private fun FileInfo.resource(): String = id ?: url ?: name

private fun JsonObject.fileInfo(fallbackIdToFile: Boolean = false): FileInfo {
    val file = stringOrNull("file").orEmpty()
    return FileInfo(
        name = stringOrNull("name") ?: file,
        size = stringOrNull("file_size")?.toLongOrNull() ?: -1,
        id = stringOrNull("file_id") ?: file.takeIf { fallbackIdToFile },
        url = stringOrNull("url"),
        path = stringOrNull("path"),
        uniqueId = stringOrNull("file_unique"),
    )
}

private fun JsonObject.addOptional(name: String, value: String?) {
    value?.takeIf(String::isNotBlank)?.let { addProperty(name, it) }
}

private fun JsonObject.stringOrNull(name: String): String? {
    val value = get(name) ?: return null
    return if (value.isJsonPrimitive) value.asString else null
}

private fun JsonObject.intOrNull(name: String): Int? = stringOrNull(name)?.toIntOrNull()
