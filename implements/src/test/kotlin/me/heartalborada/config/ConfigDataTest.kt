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
        assertTrue(config.bot.napcat.enabled)
        assertTrue(config.bot.napcat.blurImages)
        assertEquals("ws://napcat.example", config.bot.napcat.websocketUrl)
        assertEquals("napcat-token", config.bot.napcat.token)
        assertEquals(512 * 1024, config.bot.napcat.fileUpload.chunkSize)
        assertFalse(config.bot.telegram.enabled)
        assertFalse(config.bot.telegram.blurImages)
        assertEquals("telegram-token", config.bot.telegram.token)
        assertEquals("https://api.telegram.org", config.bot.telegram.apiBaseUrl)
        assertEquals(60L, config.bot.telegram.uploadTimeoutMinutes)
        assertEquals(false, config.bot.telegram.enableInlineMode)
        assertEquals(LargeFilePolicy.SPLIT_PDF, config.bot.telegram.largeFile.policy)
        assertEquals(48, config.bot.telegram.largeFile.maxPartSizeMiB)
        assertEquals("data/telegram/temp", config.bot.telegram.largeFile.tempDirectory)
        assertEquals(0L, config.eHentai.maxArchiveSizeMiB)
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
            assertTrue(config.bot.napcat.enabled)
            assertTrue(config.bot.napcat.blurImages)
            assertFalse(config.bot.telegram.enabled)
            assertFalse(config.bot.telegram.blurImages)

            val upgraded = JsonParser.parseString(configFile.readText()).asJsonObject
            val bot = upgraded.getAsJsonObject("Bot")
            assertEquals(ConfigData.CURRENT_VERSION, upgraded.get("version").asInt)
            assertTrue(bot.has("napcat"))
            assertTrue(bot.has("telegram"))
            assertFalse(bot.has("WebsocketURL"))
            assertFalse(bot.has("Token"))
            assertFalse(bot.has("FileUpload"))
            assertFalse(bot.has("Telegram"))
            assertFalse(bot.has("Adapter"))
            assertTrue(upgraded.getAsJsonObject("UnknownSetting").get("keep").asBoolean)
            assertFalse(upgraded.has("BlurImages"))
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
            assertFalse(config.bot.napcat.enabled)
            assertTrue(config.bot.napcat.blurImages)
            assertTrue(config.bot.telegram.enabled)
            assertFalse(config.bot.telegram.blurImages)
            val upgraded = JsonParser.parseString(configFile.readText()).asJsonObject
            assertFalse(upgraded.has("BlurImages"))
            assertFalse(upgraded.getAsJsonObject("Bot").has("Adapter"))
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
            assertEquals(original, configFile.readText())
        } finally {
            configFile.delete()
        }
    }

    @Test
    fun `both adapters can be enabled with independent image blur settings`() {
        val config = Gson().fromJson(
            """
                {
                  "version": 4,
                  "Bot": {
                    "napcat": {
                      "Enabled": true,
                      "BlurImages": false
                    },
                    "telegram": {
                      "Enabled": true,
                      "BlurImages": true,
                      "Token": "telegram-token"
                    }
                  }
                }
            """.trimIndent(),
            ConfigData::class.java,
        )

        assertTrue(config.bot.napcat.enabled)
        assertFalse(config.bot.napcat.blurImages)
        assertTrue(config.bot.telegram.enabled)
        assertTrue(config.bot.telegram.blurImages)
    }

    @Test
    fun `E-Hentai archive size limit is configurable`() {
        val config = Gson().fromJson(
            """{"version":7,"Ehentai":{"MaxArchiveSizeMiB":768}}""",
            ConfigData::class.java,
        )

        assertEquals(768L, config.eHentai.maxArchiveSizeMiB)
    }

    @Test
    fun `version seven config removes Telegraph fallback settings`() {
        val configFile = Files.createTempFile("useful-bot-v7-config-", ".json").toFile()
        try {
            configFile.writeText(
                """
                    {
                      "version": 7,
                      "Bot": {
                        "telegram": {
                          "Enabled": true,
                          "Token": "token",
                          "LargeFile": {"Policy": "TELEGRAPH"},
                          "TelegraphPreview": {
                            "Enabled": true,
                            "AccessToken": "obsolete-token"
                          }
                        }
                      }
                    }
                """.trimIndent()
            )

            val config = Config(configFile).getConfig()

            assertEquals(ConfigData.CURRENT_VERSION, config.version)
            assertEquals(LargeFilePolicy.SPLIT_PDF, config.bot.telegram.largeFile.policy)
            val upgraded = JsonParser.parseString(configFile.readText()).asJsonObject
            val telegram = upgraded.getAsJsonObject("Bot").getAsJsonObject("telegram")
            assertFalse(telegram.has("TelegraphPreview"))
            assertEquals("SPLIT_PDF", telegram.getAsJsonObject("LargeFile").get("Policy").asString)
        } finally {
            configFile.delete()
        }
    }

    @Test
    fun `version five config gains Telegram PDF splitting settings`() {
        val configFile = Files.createTempFile("useful-bot-v5-config-", ".json").toFile()
        try {
            configFile.writeText(
                """{"version":5,"Bot":{"telegram":{"Enabled":true,"Token":"token"}}}"""
            )

            val config = Config(configFile).getConfig()

            assertEquals(ConfigData.CURRENT_VERSION, config.version)
            assertEquals(LargeFilePolicy.SPLIT_PDF, config.bot.telegram.largeFile.policy)
            assertEquals(48, config.bot.telegram.largeFile.maxPartSizeMiB)
            val upgraded = JsonParser.parseString(configFile.readText()).asJsonObject
            assertEquals(
                "SPLIT_PDF",
                upgraded.getAsJsonObject("Bot")
                    .getAsJsonObject("telegram")
                    .getAsJsonObject("LargeFile")
                    .get("Policy")
                    .asString,
            )
        } finally {
            configFile.delete()
        }
    }

    @Test
    fun `version six config gains E-Hentai archive size limit`() {
        val configFile = Files.createTempFile("useful-bot-v6-config-", ".json").toFile()
        try {
            configFile.writeText(
                """{"version":6,"Ehentai":{"isExHentai":true}}"""
            )

            val config = Config(configFile).getConfig()

            assertEquals(ConfigData.CURRENT_VERSION, config.version)
            assertEquals(0L, config.eHentai.maxArchiveSizeMiB)
            val upgraded = JsonParser.parseString(configFile.readText()).asJsonObject
            assertEquals(
                0L,
                upgraded.getAsJsonObject("Ehentai")
                    .get("MaxArchiveSizeMiB")
                    .asLong,
            )
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
