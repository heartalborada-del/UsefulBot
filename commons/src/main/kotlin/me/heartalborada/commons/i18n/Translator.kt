package me.heartalborada.commons.i18n

fun interface Translator {
    fun translate(key: String, vararg arguments: Any?): String
}
