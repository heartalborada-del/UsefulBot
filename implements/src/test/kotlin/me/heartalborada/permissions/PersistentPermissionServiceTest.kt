package me.heartalborada.permissions

import me.heartalborada.commons.permissions.PermissionContext
import me.heartalborada.commons.permissions.PermissionDefault
import me.heartalborada.commons.permissions.PermissionSubject
import me.heartalborada.commons.permissions.PermissionSubjectType
import me.heartalborada.state.BotStateStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistentPermissionServiceTest {
    @Test
    fun `resolves user group fallback deny and platform isolation across restarts`() {
        val directory = Files.createTempDirectory("permission-service-").toFile()
        val stateFile = directory.resolve("state.json")
        val tgUser = subject("tg", PermissionSubjectType.USER, 2)
        val tgGroup = subject("tg", PermissionSubjectType.GROUP, -100)
        val qqUser = subject("qq", PermissionSubjectType.USER, 2)
        try {
            var state = BotStateStore(stateFile)
            var service = PersistentPermissionService(state)

            val tgAdmin = subject("tg", PermissionSubjectType.USER, 1)
            assertFalse(service.hasPermission(context("tg", 1), "anything.at.all", PermissionDefault.ADMIN))
            assertTrue(service.grant(tgAdmin, "usefulbot.admin"))
            assertTrue(service.hasPermission(context("tg", 1), "anything.at.all", PermissionDefault.ADMIN))
            assertFalse(service.hasPermission(PermissionContext(tgUser, tgGroup), "gallery.download.eh"))
            assertTrue(service.grant(tgGroup, "gallery.download"))
            assertTrue(service.hasPermission(PermissionContext(tgUser, tgGroup), "gallery.download.eh"))
            assertFalse(service.hasPermission(PermissionContext(qqUser), "gallery.download.eh"))

            assertTrue(service.deny(tgUser, "gallery.download.eh"))
            assertFalse(service.hasPermission(PermissionContext(tgUser, tgGroup), "gallery.download.eh"))
            assertTrue(service.grant(tgUser, "gallery.download.eh"))
            assertTrue(service.hasPermission(PermissionContext(tgUser, tgGroup), "gallery.download.eh"))
            assertTrue(service.deny(tgUser, "gallery.download.eh"))
            assertTrue(service.clear(tgUser, "gallery.download.eh"))
            assertTrue(service.hasPermission(PermissionContext(tgUser, tgGroup), "gallery.download.eh"))

            state = BotStateStore(stateFile)
            service = PersistentPermissionService(state)
            assertEquals(setOf("gallery.download"), service.rules(tgGroup))
            assertTrue(service.hasPermission(PermissionContext(tgUser, tgGroup), "gallery.download.jm"))
            assertTrue(service.hasPermission(PermissionContext(qqUser), "unconfigured", PermissionDefault.ALLOW))
            assertFalse(service.hasPermission(PermissionContext(qqUser), "unconfigured", PermissionDefault.ADMIN))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `parses canonical shorthand legacy and platform aliases`() {
        assertEquals("tg:user:7", PermissionSubject.parse("tg:user:7")?.key)
        assertEquals("tg:group:-8", PermissionSubject.parse("group:-8", "tg")?.key)
        assertEquals("qq:user:9", PermissionSubject.parse("napcat:9")?.key)
        assertEquals("tg:user:10", PermissionSubject.parse("telegram:user:10")?.key)
    }

    private fun subject(platform: String, type: PermissionSubjectType, id: Long) =
        PermissionSubject(platform, type, id)

    private fun context(platform: String, userId: Long) =
        PermissionContext(subject(platform, PermissionSubjectType.USER, userId))
}
