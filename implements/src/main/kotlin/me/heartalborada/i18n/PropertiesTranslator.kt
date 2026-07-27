package me.heartalborada.i18n

import me.heartalborada.commons.i18n.Translator
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties

class PropertiesTranslator(language: String? = DEFAULT_LANGUAGE) : Translator {
    private val fallback = load(ENGLISH_RESOURCE)
    private val selected = resourceFor(language)
        ?.takeUnless { it == ENGLISH_RESOURCE }
        ?.let(::load)

    override fun translate(key: String, vararg arguments: Any?): String {
        val template = selected?.getProperty(key)
            ?: fallback.getProperty(key)
            ?: key
        return arguments.foldIndexed(template) { index, result, argument ->
            result.replace("{$index}", argument?.toString().orEmpty())
        }
    }

    private fun load(resource: String): Properties {
        val stream = PropertiesTranslator::class.java.classLoader.getResourceAsStream(resource)
            ?: error("Missing translation resource: $resource")
        return Properties().apply {
            InputStreamReader(stream, StandardCharsets.UTF_8).use(::load)
        }
    }

    private fun resourceFor(language: String?): String? {
        val normalized = language.orEmpty().trim().lowercase().replace('_', '-')
        return when {
            normalized == "english" || normalized == "en" || normalized.startsWith("en-") -> ENGLISH_RESOURCE
            normalized in setOf("chinese", "简体中文", "中文") ||
                normalized == "zh" ||
                normalized.startsWith("zh-") -> CHINESE_RESOURCE
            else -> null
        }
    }

    companion object {
        const val DEFAULT_LANGUAGE = "en"
        internal const val ENGLISH_RESOURCE = "i18n/messages_en.properties"
        internal const val CHINESE_RESOURCE = "i18n/messages_zh_CN.properties"
    }
}
