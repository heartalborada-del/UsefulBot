package me.heartalborada.config

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigDataTest {
    @Test
    fun `existing configs without language keep the English default`() {
        val config = Gson().fromJson(
            """{"Bot":{"Token":"existing-token"}}""",
            ConfigData::class.java
        )

        assertEquals("en", config.bot.language)
        assertEquals(8, config.jmComic.imageParallelCount)
        assertEquals("https://jm365.work/3YeBdF", config.jmComic.redirectUrl)
        assertEquals("www.cdnhjk.net", config.jmComic.apiDomains.first())
    }
}
