package me.heartalborada.commons.i18n

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class TranslatorTest {
    @Test
    fun `selects Chinese and replaces placeholders`() {
        val translator = Translator("zh-CN")

        assertEquals(
            "签到成功：+50 GP。当前余额：120 GP。",
            translator.translate("command.checkin.success", 50, 120)
        )
    }

    @Test
    fun `unknown languages and missing keys fall back safely`() {
        val translator = Translator("unsupported-language")

        assertEquals("Available commands:", translator.translate("command.help.header"))
        assertEquals("missing.translation.key", translator.translate("missing.translation.key"))
        assertEquals("Available commands:", Translator(null).translate("command.help.header"))
    }

    @Test
    fun `bundled languages contain the same translation keys`() {
        val english = load("i18n/messages_en.properties")
        val chinese = load("i18n/messages_zh_CN.properties")

        assertEquals(english.stringPropertyNames(), chinese.stringPropertyNames())
    }

    private fun load(resource: String): Properties {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(resource))
        return Properties().apply {
            InputStreamReader(stream, StandardCharsets.UTF_8).use(::load)
        }
    }
}
