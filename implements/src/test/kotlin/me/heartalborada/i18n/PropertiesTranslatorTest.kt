package me.heartalborada.i18n

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class PropertiesTranslatorTest {
    @Test
    fun `selects Chinese and replaces placeholders`() {
        val translator = PropertiesTranslator("zh-CN")

        assertEquals(
            "签到成功：+50 GP。当前余额：120 GP。",
            translator.translate("command.checkin.success", 50, 120)
        )
    }

    @Test
    fun `unknown languages and missing keys fall back safely`() {
        val translator = PropertiesTranslator("unsupported-language")

        assertEquals("Available commands:", translator.translate("command.help.header"))
        assertEquals("missing.translation.key", translator.translate("missing.translation.key"))
        assertEquals("Available commands:", PropertiesTranslator(null).translate("command.help.header"))
    }

    @Test
    fun `bundled languages contain the same translation keys`() {
        val english = load(PropertiesTranslator.ENGLISH_RESOURCE)
        val chinese = load(PropertiesTranslator.CHINESE_RESOURCE)

        assertEquals(english.stringPropertyNames(), chinese.stringPropertyNames())
    }

    private fun load(resource: String): Properties {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(resource))
        return Properties().apply {
            InputStreamReader(stream, StandardCharsets.UTF_8).use(::load)
        }
    }
}
