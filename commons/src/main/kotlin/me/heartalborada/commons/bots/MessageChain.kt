package me.heartalborada.commons.bots

import me.heartalborada.commons.bots.beans.FileInfo

class MessageChain {
    private val chain = mutableListOf<AbstractMessageObject>()
    fun append(obj: AbstractMessageObject): MessageChain {
        chain.add(obj)
        return this
    }

    fun removeLast(): MessageChain {
        chain.removeAt(chain.size - 1)
        return this
    }

    fun removeAt(index: Int): MessageChain {
        chain.removeAt(index)
        return this
    }

    fun clear(): MessageChain {
        chain.clear()
        return this
    }

    override fun toString(): String {
        return chain.joinToString(separator = "") { it.toString() }
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