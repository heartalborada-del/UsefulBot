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

class Image() : AbstractMessageObject() {
    private var url: String? = null
    constructor(url: String) : this() {
        this.url = url
    }
    constructor(file: File) : this() {
        this.url = "file://${file.toURI()}"

    }
    constructor(bytes: ByteArray) : this() {
        this.url = "base64://${Base64.getEncoder().encodeToString(bytes)}"
    }

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

//TODO add more message objects