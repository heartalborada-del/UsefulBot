package me.heartalborada.plugins

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import me.heartalborada.commons.plugins.PLUGIN_API_VERSION
import me.heartalborada.commons.plugins.PluginMetadata
import java.io.Reader

/** Parsed form of `usefulbot.plugin.json5` stored at the root of a plugin JAR. */
data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val main: String,
    val description: String = "",
    val apiVersion: Int = PLUGIN_API_VERSION,
    val dependencies: Set<String> = emptySet(),
    val libraries: List<String> = emptyList(),
    val repositories: List<String> = listOf(MAVEN_CENTRAL),
) {
    fun metadata(): PluginMetadata = PluginMetadata(
        id = id,
        name = name,
        version = version,
        description = description,
        apiVersion = apiVersion,
        dependencies = dependencies,
    )

    companion object {
        const val RESOURCE_NAME: String = "usefulbot.plugin.json5"
        const val MAVEN_CENTRAL: String = "https://repo.maven.apache.org/maven2/"

        private val mapper = ObjectMapper(
            JsonFactory.builder()
                .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
                .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
                .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .enable(JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS)
                .build(),
        )

        /** Parses comments, single quotes, unquoted names and trailing commas. */
        fun parse(source: Reader): PluginDescriptor {
            val parsed: JsonNode = mapper.readTree(source)
            require(parsed is ObjectNode) { "Plugin descriptor must be an object." }
            return PluginDescriptor(
                id = parsed.requiredString("id"),
                name = parsed.requiredString("name"),
                version = parsed.requiredString("version"),
                main = parsed.requiredString("main"),
                description = parsed.optionalString("description").orEmpty(),
                apiVersion = parsed.get("apiVersion")?.let { value ->
                    require(value.isIntegralNumber) { "Plugin field 'apiVersion' must be an integer." }
                    value.intValue()
                } ?: PLUGIN_API_VERSION,
                dependencies = parsed.stringList("dependencies").toSet(),
                libraries = parsed.stringList("libraries"),
                repositories = parsed.stringList("repositories")
                    .ifEmpty { listOf(MAVEN_CENTRAL) },
            )
        }

        private fun ObjectNode.requiredString(name: String): String =
            requireNotNull(optionalString(name)) { "Plugin field '$name' is required." }

        private fun ObjectNode.optionalString(name: String): String? {
            val value = get(name) ?: return null
            require(value.isTextual) { "Plugin field '$name' must be a string." }
            return value.textValue()
        }

        private fun ObjectNode.stringList(name: String): List<String> {
            val value = get(name) ?: return emptyList()
            require(value.isArray) { "Plugin field '$name' must be an array." }
            return value.mapIndexed { index, element ->
                require(element.isTextual) { "Plugin field '$name[$index]' must be a string." }
                element.textValue()
            }
        }
    }
}
