package me.heartalborada.bots.napcat

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import me.heartalborada.bots.MessageChainTypeAdapter
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.At
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.PlainText
import me.heartalborada.commons.bots.dto.ForwardMessageNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class NapcatForwardMessageTest {
    private val messageGson = GsonBuilder()
        .registerTypeAdapter(MessageChain::class.java, MessageChainTypeAdapter())
        .create()

    @Test
    fun `builds private merged-forward request with both node types`() {
        val content = MessageChain().apply {
            add(PlainText("hello"))
            add(At(42L))
        }

        val params = buildForwardMessageParams(
            ChatType.PRIVATE,
            10001L,
            listOf(
                ForwardMessageNode.ExistingMessage(123L),
                ForwardMessageNode.CustomMessage(42L, "Alice", content),
            ),
            messageGson,
        )
        val json = Gson().toJsonTree(params).asJsonObject

        assertEquals("private", json["message_type"].asString)
        assertEquals(10001L, json["user_id"].asLong)
        assertFalse(json.has("group_id"))

        val nodes = json.getAsJsonArray("messages")
        assertEquals("node", nodes[0].asJsonObject["type"].asString)
        assertEquals(123L, nodes[0].asJsonObject.getAsJsonObject("data")["id"].asLong)

        val custom = nodes[1].asJsonObject.getAsJsonObject("data")
        assertEquals(42L, custom["user_id"].asLong)
        assertEquals("Alice", custom["nickname"].asString)
        assertEquals("text", custom.getAsJsonArray("content")[0].asJsonObject["type"].asString)
        assertEquals("hello", custom.getAsJsonArray("content")[0]
            .asJsonObject.getAsJsonObject("data")["text"].asString)
    }

    @Test
    fun `builds group target and rejects unsupported chat type`() {
        val nodes = listOf(ForwardMessageNode.ExistingMessage(123L))
        val group = buildForwardMessageParams(ChatType.GROUP, 456L, nodes, messageGson)

        assertEquals(456L, group["group_id"])
        assertFalse(group.containsKey("user_id"))
        assertFailsWith<IllegalArgumentException> {
            buildForwardMessageParams(ChatType.SELF, 456L, nodes, messageGson)
        }
    }
}
