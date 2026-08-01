import me.heartalborada.commons.plugins.PluginMetadata
import me.heartalborada.plugins.PluginSnapshot
import me.heartalborada.plugins.PluginStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginListFormattingTest {
    @Test
    fun `formats plugin names and versions with the command wire format`() {
        val plugins = listOf(
            snapshot("Built-in Permissions", "1.0.0"),
            snapshot("Built-in Comic", "2.1.3"),
        )

        assertEquals("[Built-in Permissions:1.0.0,Built-in Comic:2.1.3,]", formatPluginList(plugins))
        assertEquals("[]", formatPluginList(emptyList()))
    }

    private fun snapshot(name: String, version: String) = PluginSnapshot(
        metadata = PluginMetadata(id = name.lowercase().replace(' ', '-'), name = name, version = version),
        status = PluginStatus.ENABLED,
        jar = null,
        builtIn = true,
        essential = false,
    )
}
