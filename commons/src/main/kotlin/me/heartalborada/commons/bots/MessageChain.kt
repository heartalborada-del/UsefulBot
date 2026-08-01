package me.heartalborada.commons.bots

import me.heartalborada.commons.bots.dto.FileInfo

class MessageChain : MutableList<AbstractMessageObject> by mutableListOf() {
    override fun toString(): String {
        return joinToString(separator = "") { it.toString() }
    }

    companion object {
        fun text(text: String): MessageChain = MessageChain().apply {
            add(PlainText(text))
        }

        fun replyTo(messageID: Long, text: String): MessageChain = MessageChain().apply {
            add(Reply(messageID))
            add(PlainText(text))
        }
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

class Image(
    val info: FileInfo,
    val summary: String? = null,
    val subType: String? = null,
) : AbstractMessageObject() {
    override fun toString(): String {
        return "[Image]"
    }
}

class File(val info: FileInfo) : AbstractMessageObject() {
    override fun toString(): String {
        return "[File${info.name}]"
    }
}

class Record(val info: FileInfo) : AbstractMessageObject() {
    override fun toString(): String = "[Record:${info.name}]"
}

class Video(val info: FileInfo, val thumbnail: String? = null) : AbstractMessageObject() {
    override fun toString(): String = "[Video:${info.name}]"
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

class ForwardNode(
    val id: String? = null,
    val content: MessageChain? = null,
    val userId: Long? = null,
    val nickname: String? = null,
) : AbstractMessageObject() {
    init {
        require(id != null || content != null) { "A forward node requires an existing message ID or content." }
    }

    override fun toString(): String = "[Node:${id ?: nickname.orEmpty()}]"
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
        GROUP("group"),
        TELEGRAM("telegram");

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

sealed class Music : AbstractMessageObject() {
    class Standard(val source: Source, val id: String) : Music() {
        override fun toString(): String = "[Music:${source.value}:$id]"
    }

    class Custom(
        val url: String,
        val audio: String,
        val title: String,
        val image: String? = null,
        val singer: String? = null,
    ) : Music() {
        override fun toString(): String = "[Music:$title]"
    }

    enum class Source(val value: String) {
        QQ("qq"),
        NETEASE("163"),
        KUGOU("kugou"),
        MIGU("migu"),
        KUWO("kuwo");

        companion object {
            fun fromValue(value: String): Source = entries.first { it.value == value }
        }
    }
}

class MarketFace(
    val emojiId: String,
    val emojiPackageId: String,
    val key: String,
    val summary: String? = null,
) : AbstractMessageObject() {
    override fun toString(): String = summary ?: "[MarketFace:$emojiId]"
}

class Shake : AbstractMessageObject() {
    override fun toString(): String = "[Shake]"
}

class Markdown(val content: String) : AbstractMessageObject() {
    override fun toString(): String {
        return content.toPlainTextFromMarkdown()
    }
}

data class ActionButton(val label: String, val command: String)

class ActionKeyboard(val rows: List<List<ActionButton>>) : AbstractMessageObject() {
    init {
        require(rows.isNotEmpty() && rows.all { it.isNotEmpty() }) {
            "Action keyboard must contain at least one button."
        }
    }

    override fun toString(): String = rows.joinToString(separator = "\n", prefix = "\n") { row ->
        row.joinToString("  ") { button -> "[${button.label}] ${button.command}" }
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

private fun String.toPlainTextFromMarkdown(): String {
    var text = removeMarkdownLinksAndImages()
    text = text.replace(FENCED_CODE_DELIMITER, "")
    text = text.replace(HORIZONTAL_RULE, "")
    text = text.replace(HEADING_PREFIX, "")
    text = text.replace(BLOCK_QUOTE_PREFIX, "")
    text = text.replace(UNORDERED_LIST_PREFIX) { it.groupValues[1] }
    text = text.replace(ORDERED_LIST_PREFIX) { it.groupValues[1] }
    text = text.replace(INLINE_CODE) { it.groupValues[1] }
    text = text.replace(STRONG_MARKER) { it.groupValues[2] }
    text = text.replace(STRIKETHROUGH_MARKER) { it.groupValues[1] }
    text = text.replace(ASTERISK_EMPHASIS_MARKER) { it.groupValues[1] }
    text = text.replace(UNDERSCORE_EMPHASIS_MARKER) { it.groupValues[1] }
    text = text.replace(ESCAPED_MARKDOWN_CHARACTER) { it.groupValues[1] }
    return text.trim()
}

private fun String.removeMarkdownLinksAndImages(): String {
    val result = StringBuilder(length)
    var index = 0
    while (index < length) {
        val isImage = this[index] == '!' && getOrNull(index + 1) == '['
        val labelStart = if (isImage) index + 1 else index
        if (this[labelStart] != '[') {
            result.append(this[index++])
            continue
        }

        val labelEnd = findUnescaped(']', labelStart + 1)
        if (labelEnd == -1 || getOrNull(labelEnd + 1) != '(') {
            result.append(this[index++])
            continue
        }
        val destinationEnd = findClosingParenthesis(labelEnd + 1)
        if (destinationEnd == -1) {
            result.append(this[index++])
            continue
        }

        if (isImage) {
            val url = substring(labelEnd + 2, destinationEnd)
            val label = substring(labelStart + 1, labelEnd)
            result.append(Image(FileInfo(label, id = url, url = url)))
        } else {
            result.append(substring(labelStart + 1, labelEnd))
        }
        index = destinationEnd + 1
    }
    return result.toString()
}

private fun String.findUnescaped(character: Char, startIndex: Int): Int {
    for (index in startIndex until length) {
        if (this[index] == character && (index == 0 || this[index - 1] != '\\')) return index
    }
    return -1
}

private fun String.findClosingParenthesis(openIndex: Int): Int {
    var depth = 0
    for (index in openIndex until length) {
        if (index > openIndex && this[index - 1] == '\\') continue
        when (this[index]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return index
        }
    }
    return -1
}

private val FENCED_CODE_DELIMITER = Regex("""(?m)^[ \t]*(`{3,}|~{3,})[^\r\n]*\r?\n?""")
private val HORIZONTAL_RULE = Regex("""(?m)^[ \t]{0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$\r?\n?""")
private val HEADING_PREFIX = Regex("""(?m)^[ \t]{0,3}#{1,6}[ \t]+""")
private val BLOCK_QUOTE_PREFIX = Regex("""(?m)^[ \t]{0,3}>[ \t]?""")
private val UNORDERED_LIST_PREFIX = Regex("""(?m)^([ \t]*)[-+*][ \t]+""")
private val ORDERED_LIST_PREFIX = Regex("""(?m)^([ \t]*)\d+[.)][ \t]+""")
private val INLINE_CODE = Regex("""`+([^`\r\n]+?)`+""")
private val STRONG_MARKER = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
private val STRIKETHROUGH_MARKER = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")
private val ASTERISK_EMPHASIS_MARKER = Regex("""(?<!\*)\*(?=\S)(.+?)(?<=\S)\*(?!\*)""")
private val UNDERSCORE_EMPHASIS_MARKER = Regex("""(?<!_)_(?=\S)(.+?)(?<=\S)_(?!_)""")
private val ESCAPED_MARKDOWN_CHARACTER = Regex("""\\([\\`*{}\[\]()#+\-.!_>~|])""")
//TODO add more message objects
