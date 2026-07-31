package me.heartalborada.plugins

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginDescriptorTest {
    @Test
    fun `parses concise JSON5 descriptor syntax`() {
        val descriptor = PluginDescriptor.parse(
            StringReader(
                """
                    {
                      // Comments, unquoted names and single quotes keep descriptors compact.
                      id: 'example-plugin',
                      name: 'Example Plugin',
                      version: '1.2.3',
                      main: 'example.ExamplePlugin',
                      apiVersion: 1,
                      dependencies: ['foundation',],
                      libraries: ['com.example:library:2.0',],
                      repositories: ['https://repo.example.com/releases/',],
                    }
                """.trimIndent(),
            ),
        )

        assertEquals("example-plugin", descriptor.id)
        assertEquals("example.ExamplePlugin", descriptor.main)
        assertEquals(setOf("foundation"), descriptor.dependencies)
        assertEquals(listOf("com.example:library:2.0"), descriptor.libraries)
        assertEquals(listOf("https://repo.example.com/releases/"), descriptor.repositories)
    }
}
