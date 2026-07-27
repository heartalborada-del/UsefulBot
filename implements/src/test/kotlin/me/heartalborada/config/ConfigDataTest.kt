package me.heartalborada.config

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigDataTest {
    @Test
    fun `adapter configs are isolated under their own sections`() {
        val config = Gson().fromJson(
            """
                {
                  "Bot": {
                    "napcat": {
                      "WebsocketURL": "ws://napcat.example",
                      "Token": "napcat-token"
                    },
                    "telegram": {
                      "Token": "telegram-token",
                      "EnableInlineMode": false
                    }
                  }
                }
            """.trimIndent(),
            ConfigData::class.java
        )

        assertEquals("en", config.bot.language)
        assertEquals(ConfigData.Bot.Adapter.NAPCAT, config.bot.adapter)
        assertEquals("ws://napcat.example", config.bot.napcat.websocketUrl)
        assertEquals("napcat-token", config.bot.napcat.token)
        assertEquals(512 * 1024, config.bot.napcat.fileUpload.chunkSize)
        assertEquals("telegram-token", config.bot.telegram.token)
        assertEquals("https://api.telegram.org", config.bot.telegram.apiBaseUrl)
        assertEquals(false, config.bot.telegram.enableInlineMode)
        assertFalse(config.blurImages)
        assertEquals(8, config.jmComic.imageParallelCount)
        assertEquals("https://jm365.work/3YeBdF", config.jmComic.redirectUrl)
        assertEquals("www.cdnhjk.net", config.jmComic.apiDomains.first())
    }

    @Test
    fun `version one config is upgraded and written back`() {
        val configFile = Files.createTempFile("useful-bot-config-", ".json").toFile()
        try {
            configFile.writeText(
                """
                    {
                      "Bot": {
                        "WebsocketURL": "ws://legacy.example",
                        "Token": "legacy-token",
                        "FileUpload": {
                          "ChunkSize": 1024
                        },
                        "Telegram": {
                          "Token": "telegram-token"
                        }
                      },
                      "UnknownSetting": {
                        "keep": true
                      }
                    }
                """.trimIndent()
            )

            val config = Config(configFile).getConfig()

            assertEquals(ConfigData.CURRENT_VERSION, config.version)
            assertEquals("ws://legacy.example", config.bot.napcat.websocketUrl)
            assertEquals("legacy-token", config.bot.napcat.token)
            assertEquals(1024, config.bot.napcat.fileUpload.chunkSize)
            assertEquals("telegram-token", config.bot.telegram.token)
            assertTrue(config.blurImages)

            val upgraded = JsonParser.parseString(configFile.readText()).asJsonObject
            val bot = upgraded.getAsJsonObject("Bot")
            assertEquals(ConfigData.CURRENT_VERSION, upgraded.get("version").asInt)
            assertTrue(bot.has("napcat"))
            assertTrue(bot.has("telegram"))
            assertFalse(bot.has("WebsocketURL"))
            assertFalse(bot.has("Token"))
            assertFalse(bot.has("FileUpload"))
            assertFalse(bot.has("Telegram"))
            assertTrue(upgraded.getAsJsonObject("UnknownSetting").get("keep").asBoolean)
            assertTrue(upgraded.get("BlurImages").asBoolean)
            assertAllDefaultFieldsPresent(
                actual = upgraded,
                defaults = Gson().toJsonTree(ConfigData()).asJsonObject,
            )
        } finally {
            configFile.delete()
        }
    }

    @Test
    fun `version two Telegram config disables image blur during upgrade`() {
        val configFile = Files.createTempFile("useful-bot-telegram-config-", ".json").toFile()
        try {
            configFile.writeText(
                """{"version":2,"Bot":{"Adapter":"TELEGRAM","telegram":{"Token":"token"}}}"""
            )

            val config = Config(configFile).getConfig()

            assertEquals(ConfigData.CURRENT_VERSION, config.version)
            assertFalse(config.blurImages)
            val upgraded = JsonParser.parseString(configFile.readText()).asJsonObject
            assertFalse(upgraded.get("BlurImages").asBoolean)
            assertEquals(
                "https://api.telegram.org",
                upgraded.getAsJsonObject("Bot")
                    .getAsJsonObject("telegram")
                    .get("ApiBaseURL")
                    .asString,
            )
            assertAllDefaultFieldsPresent(
                actual = upgraded,
                defaults = Gson().toJsonTree(ConfigData()).asJsonObject,
            )
        } finally {
            configFile.delete()
        }
    }

    @Test
    fun `newer config version is not rewritten`() {
        val configFile = Files.createTempFile("useful-bot-future-config-", ".json").toFile()
        try {
            val original = """{"version":999,"Bot":{"Adapter":"TELEGRAM"},"future":true}"""
            configFile.writeText(original)

            val config = Config(configFile).getConfig()

            assertEquals(999, config.version)
            assertEquals(ConfigData.Bot.Adapter.TELEGRAM, config.bot.adapter)
            assertEquals(original, configFile.readText())
        } finally {
            configFile.delete()
        }
    }

    private fun assertAllDefaultFieldsPresent(actual: JsonObject, defaults: JsonObject, path: String = "") {
        defaults.entrySet().forEach { (key, defaultValue) ->
            val fieldPath = if (path.isEmpty()) key else "$path.$key"
            assertTrue(actual.has(key), "Missing migrated config field: $fieldPath")
            val actualValue = actual.get(key)
            if (actualValue.isJsonObject && defaultValue.isJsonObject) {
                assertAllDefaultFieldsPresent(actualValue.asJsonObject, defaultValue.asJsonObject, fieldPath)
            }
        }
    }
}
