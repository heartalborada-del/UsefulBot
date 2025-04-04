package me.heartalborada.commons.bots

import me.heartalborada.commons.bots.beans.FileInfo
import java.io.File
import java.util.*

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

class Image(val url: String) : AbstractMessageObject() {
    constructor(file: File) : this("file://${file.toURI()}")
    constructor(bytes: ByteArray) : this("base64://${Base64.getEncoder().encodeToString(bytes)}")

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

class Reply(val id: String) : AbstractMessageObject() {
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
//TODO add more message objects