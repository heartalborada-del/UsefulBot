package me.heartalborada.commons.bots

import me.heartalborada.commons.bots.beans.FileInfo

class MessageChain : MutableList<AbstractMessageObject> by mutableListOf() {
    override fun toString(): String {
        return joinToString(separator = "") { it.toString() }
    }
}

abstract class AbstractMessageObject {
    abstract override fun toString(): String
}

class PlainText(val text: String) : AbstractMessageObject() {
    override fun toString(): String {
        return text
    }
}

class At(val target: Long) : AbstractMessageObject() {
    override fun toString(): String {
        return "@$target"
    }
}

class AtAll : AbstractMessageObject() {
    override fun toString(): String {
        return "@All"
    }
}

class Image(val info: FileInfo) : AbstractMessageObject() {
    override fun toString(): String {
        return "[Image]"
    }
}

class File(val info: FileInfo) : AbstractMessageObject() {
    override fun toString(): String {
        return "[File${info.name}]"
    }
}

class Face(val id: String) : AbstractMessageObject() {
    override fun toString(): String {
        return "[Face:$id]"
    }
}

class Reply(val id: Long) : AbstractMessageObject() {
    override fun toString(): String {
        return "[Reply:$id]"
    }
}

class Forward(val id: String) : AbstractMessageObject() {
    override fun toString(): String {
        return "[Forward:$id]"
    }
}

class Dice(val result: Int) : AbstractMessageObject() {
    override fun toString(): String {
        return "[Dice:$result]"
    }
}

class Rps(val result: RpsResult) : AbstractMessageObject() {
    override fun toString(): String {
        return "[Rps:$result]"
    }

    enum class RpsResult(val value: Int) {
        PAPER(1),
        SCISSORS(2),
        ROCK(3);

        companion object {
            fun fromValue(value: Int): RpsResult {
                return entries.first { it.value == value }
            }
        }
    }
}

class Poke(val id: Int, val type: Int) : AbstractMessageObject() {
    override fun toString(): String {
        return "[Poke:$id,$type]"
    }
}

class Contact(val type: ContactType, val id: Long) : AbstractMessageObject() {
    override fun toString(): String {
        return "[Contact:$type:$id]"
    }

    enum class ContactType(val value: String) {
        QQ("qq"),
        GROUP("group");

        companion object {
            fun fromValue(value: String): ContactType {
                return entries.first { it.value == value }
            }
        }
    }
}

class Location(val latitude: String, val longitude: String, val title: String?, val content: String?) :
    AbstractMessageObject() {
    override fun toString(): String {
        return "[Location:$latitude,$longitude]"
    }
}

class Xml(val data: String) : AbstractMessageObject() {
    override fun toString(): String {
        return "[Xml:$data]"
    }
}

class Json(val data: String) : AbstractMessageObject() {
    override fun toString(): String {
        return "[Json:$data]"
    }
}

class Share(val title: String, val url: String, val content: String?, val imageUrl: String?) : AbstractMessageObject() {
    override fun toString(): String {
        return "[Share:$url]"
    }
}

class Unknown(val raw: String) : AbstractMessageObject() {
    override fun toString(): String {
        return "[Unknown:$raw]"
    }
}
//TODO add more message objects