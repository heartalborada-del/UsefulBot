package me.heartalborada.console

import org.jline.reader.LineReaderBuilder
import org.jline.reader.Reference
import org.jline.keymap.KeyMap
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import org.jline.terminal.TerminalBuilder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JLineConsoleTest {
    @Test
    fun `stop accepts optional slash and surrounding whitespace`() {
        assertTrue(isStopCommand("stop"))
        assertTrue(isStopCommand(" /STOP "))
        assertFalse(isStopCommand("stop now"))
        assertFalse(isStopCommand("restart"))
    }

    @Test
    fun `stop participates in sorted top-level completion`() {
        assertEquals(listOf("status", "stop"), completeTopLevelCommands(listOf("status"), "st"))
        assertEquals(listOf("stop"), completeTopLevelCommands(emptyList(), "sto"))
        assertEquals(emptyList(), completeTopLevelCommands(emptyList(), "health"))
    }

    @Test
    fun `permission editing completes plain nodes only in the node position`() {
        val nodes = listOf("jm.query", "eh.search", "eh.query")
        val suggestions: (String) -> List<String> = { prefix -> nodes.filter { it.startsWith(prefix) } }

        assertEquals(
            listOf("eh.query", "eh.search"),
            completePermissionNodes(listOf("permission", "deny", "qq:user:7", "eh."), 3, "eh.", suggestions),
        )
        assertEquals(
            listOf("jm.query"),
            completePermissionNodes(listOf("perm", "grant", "*", "jm.q"), 3, "jm.q", suggestions),
        )
        assertEquals(
            emptyList(),
            completePermissionNodes(listOf("permission", "show", "qq:user:7"), 2, "qq:user:7", suggestions),
        )
    }

    @Test
    fun `ctrl c stops only when the current input is empty`() {
        assertTrue(shouldStopAfterInterrupt(""))
        assertTrue(shouldStopAfterInterrupt(null))
        assertFalse(shouldStopAfterInterrupt("health"))
        assertFalse(shouldStopAfterInterrupt(" "))
    }

    @Test
    fun `escape widget clears the complete input buffer`() {
        val terminal = TerminalBuilder.builder()
            .dumb(true)
            .streams(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
            .build()
        try {
            val reader = LineReaderBuilder.builder().terminal(terminal).build()
            installClearInputBindings(reader)
            reader.buffer.write("health pending")

            assertTrue(
                reader.keyMaps.values.all {
                    it.getBound(KeyMap.esc()) == Reference(CLEAR_INPUT_WIDGET)
                },
            )
            assertTrue(clearInput(reader, redisplay = false))
            assertEquals("", reader.buffer.toString())
        } finally {
            terminal.close()
        }
    }

    @Test
    fun `redirected output emits complete lines and flushes trailing text on close`() {
        val lines = mutableListOf<String>()
        val fallbackBytes = ByteArrayOutputStream()
        val output = LineRedirectingOutputStream(PrintStream(fallbackBytes), lines::add)

        output.write("first\nsecond\r\npartial".toByteArray(StandardCharsets.UTF_8))
        assertEquals(listOf("first", "second"), lines)

        output.close()
        assertEquals(listOf("first", "second", "partial"), lines)
        assertEquals("", fallbackBytes.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun `redirected output falls back when prompt rendering fails`() {
        val fallbackBytes = ByteArrayOutputStream()
        val output = LineRedirectingOutputStream(PrintStream(fallbackBytes)) { error("terminal closed") }

        output.write("log line\n".toByteArray(StandardCharsets.UTF_8))

        assertEquals("log line${System.lineSeparator()}", fallbackBytes.toString(StandardCharsets.UTF_8))
    }
}
