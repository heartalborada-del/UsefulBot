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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistentPermissionServiceTest {
    @Test
    fun `permission node suggestions are normalized filtered and reference counted`() {
        val directory = Files.createTempDirectory("permission-node-registry-").toFile()
        try {
            val service = PersistentPermissionService(BotStateStore(directory.resolve("state.json")))
            service.register("EH.Query")
            service.register("eh.query")
            service.register("eh.search")
            service.register("jm.query")

            assertEquals(listOf("eh.query", "eh.search"), service.suggestions("EH."))
            service.unregister("eh.query")
            assertEquals(listOf("eh.query"), service.suggestions("eh.q"))
            service.unregister("eh.query")
            assertEquals(emptyList(), service.suggestions("eh.q"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `subject wildcards fall back from exact type platform to global`() {
        val directory = Files.createTempDirectory("permission-subject-wildcards-").toFile()
        val qqUser = subject("qq", PermissionSubjectType.USER, 2)
        val qqGroup = subject("qq", PermissionSubjectType.GROUP, 100)
        val tgUser = subject("tg", PermissionSubjectType.USER, 2)
        try {
            val service = PersistentPermissionService(BotStateStore(directory.resolve("state.json")))
            service.grant(PermissionSubject.all(), "feature.*")
            assertTrue(service.hasPermission(PermissionContext(qqUser), "feature.read"))
            assertTrue(service.hasPermission(PermissionContext(tgUser), "feature.read"))

            service.deny(PermissionSubject.all("napcat"), "feature.*")
            assertFalse(service.hasPermission(PermissionContext(qqUser), "feature.read"))
            assertTrue(service.hasPermission(PermissionContext(tgUser), "feature.read"))

            service.grant(PermissionSubject.wildcard("qq", PermissionSubjectType.USER), "feature.read")
            assertTrue(service.hasPermission(PermissionContext(qqUser), "feature.read"))
            assertFalse(service.hasPermission(PermissionContext(qqUser), "feature.write"))

            service.deny(qqUser, "feature.read")
            assertFalse(service.hasPermission(PermissionContext(qqUser), "feature.read"))

            service.grant(PermissionSubject.wildcard("qq", PermissionSubjectType.GROUP), "group.*")
            assertFalse(service.hasPermission(PermissionContext(qqUser), "group.read"))
            assertTrue(service.hasPermission(PermissionContext(qqUser, qqGroup), "group.read"))
            service.deny(qqGroup, "group.read")
            assertFalse(service.hasPermission(PermissionContext(qqUser, qqGroup), "group.read"))
            assertTrue(service.hasPermission(PermissionContext(qqUser, qqGroup), "group.write"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `specific deny overrides wildcard allow and explicit plus is accepted`() {
        val directory = Files.createTempDirectory("permission-specificity-").toFile()
        val stateFile = directory.resolve("state.json")
        val user = subject("tg", PermissionSubjectType.USER, 2)
        val exceptionUser = subject("tg", PermissionSubjectType.USER, 3)
        try {
            stateFile.writeText(
                """{"permissions":{"tg:user:2":["+a.b.*","-a.b.c"],"tg:user:3":["-a.b.*","+a.b.c"]}}""",
            )
            val service = PersistentPermissionService(BotStateStore(stateFile))

            assertFalse(service.hasPermission(PermissionContext(user), "a.b.c"))
            assertTrue(service.hasPermission(PermissionContext(user), "a.b.d"))
            assertTrue(service.hasPermission(PermissionContext(exceptionUser), "a.b.c"))
            assertFalse(service.hasPermission(PermissionContext(exceptionUser), "a.b.d"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `most specific rule wins across user and group subjects`() {
        val directory = Files.createTempDirectory("permission-context-specificity-").toFile()
        val user = subject("tg", PermissionSubjectType.USER, 2)
        val group = subject("tg", PermissionSubjectType.GROUP, -100)
        try {
            val service = PersistentPermissionService(BotStateStore(directory.resolve("state.json")))
            service.grant(user, "a.b.*")
            service.deny(group, "a.b.c")

            assertFalse(service.hasPermission(PermissionContext(user, group), "a.b.c"))
            assertTrue(service.hasPermission(PermissionContext(user, group), "a.b.d"))
        } finally {
            directory.deleteRecursively()
        }
    }

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
        assertEquals("*", PermissionSubject.parse("*")?.key)
        assertEquals("qq:*", PermissionSubject.parse("napcat:*")?.key)
        assertEquals("tg:user:*", PermissionSubject.parse("telegram:user:*")?.key)
        assertEquals("qq:group:*", PermissionSubject.parse("qq:group:*")?.key)
        assertEquals("tg:user:11", PermissionSubject("telegram", PermissionSubjectType.USER, 11).key)
        assertNull(PermissionSubject.parse("qq:group:user:*"))
        assertNull(PermissionSubject.parse("*:user:*"))
    }

    private fun subject(platform: String, type: PermissionSubjectType, id: Long) =
        PermissionSubject(platform, type, id)

    private fun context(platform: String, userId: Long) =
        PermissionContext(subject(platform, PermissionSubjectType.USER, userId))
}
