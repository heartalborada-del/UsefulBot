package me.heartalborada.commons.bots.events

import kotlinx.coroutines.runBlocking
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.BotType
import me.heartalborada.commons.bots.SupportedBotTypes
import me.heartalborada.commons.bots.dto.UserInfo
import me.heartalborada.commons.bots.events.message.PrivateMessageEvent
import me.heartalborada.commons.bots.events.message.InlineQueryEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventBusTest {
    @Test
    fun `listeners use priority order and can observe base event types`() = runBlocking {
        val calls = mutableListOf<String>()
        EventBus().use { bus ->
            bus.register(Event::class.java, priority = EventPriority.LOW) { calls += "event" }
            bus.register(MessageEvent::class.java, priority = EventPriority.HIGH) { calls += "message" }
            bus.register(PrivateMessageEvent::class.java) { calls += "private" }

            bus.publish(messageEvent())
        }

        assertEquals(listOf("message", "private", "event"), calls)
    }

    @Test
    fun `interception skips ordinary listeners but keeps opted-in observers`() = runBlocking {
        val calls = mutableListOf<String>()
        EventBus().use { bus ->
            bus.register(Event::class.java, EventPriority.HIGHEST) {
                calls += "interceptor"
                it.intercept()
            }
            bus.register(Event::class.java) { calls += "ordinary" }
            bus.register(Event::class.java, EventPriority.LOWEST, receiveIntercepted = true) {
                calls += "observer"
            }

            val event = bus.publish(messageEvent())
            assertTrue(event.isIntercepted)
        }

        assertEquals(listOf("interceptor", "observer"), calls)
    }

    @Test
    fun `subscription is idempotent and removes its listener`() = runBlocking {
        var calls = 0
        EventBus().use { bus ->
            val subscription = bus.register(Event::class.java) { calls++ }
            subscription.close()
            subscription.close()
            bus.publish(messageEvent())
            assertEquals(0, bus.listenerCount())
        }
        assertEquals(0, calls)
    }

    @Test
    fun `listener failure is isolated and reported`() = runBlocking {
        val failures = mutableListOf<Throwable>()
        var delivered = false
        EventBus(listenerErrorHandler = { _, error -> failures += error }).use { bus ->
            bus.register(Event::class.java, priority = EventPriority.HIGH) { error("broken listener") }
            bus.register(Event::class.java) { delivered = true }
            bus.publish(messageEvent())
        }

        assertTrue(delivered)
        assertEquals("broken listener", failures.single().message)
    }

    @Test
    fun `annotated listener honors options and can be unregistered as a group`() = runBlocking {
        val listener = AnnotatedListener()
        EventBus().use { bus ->
            val subscription = bus.register(listener)
            val first = messageEvent().also(Event::intercept)
            bus.publish(first)
            subscription.close()
            bus.publish(messageEvent())
            assertEquals(0, bus.listenerCount())
        }

        assertTrue(listener.called)
        assertFalse(listener.calledAfterClose)
    }

    @Test
    fun `event support annotations are available to documentation and reflection`() {
        val privateSupport = PrivateMessageEvent::class.java.getAnnotation(SupportedBotTypes::class.java)
        val inlineSupport = InlineQueryEvent::class.java.getAnnotation(SupportedBotTypes::class.java)

        assertEquals(setOf(BotType.NAPCAT, BotType.TELEGRAM), privateSupport.value.toSet())
        assertEquals(listOf(BotType.TELEGRAM), inlineSupport.value.toList())
    }

    private class AnnotatedListener {
        var called = false
        var calledAfterClose = false

        @EventHandler(receiveIntercepted = true)
        private fun receive(event: MessageEvent) {
            if (called) calledAfterClose = true else called = event.isIntercepted
        }
    }

    private fun messageEvent() = PrivateMessageEvent(
        botID = 1L,
        timestamp = 2L,
        sender = UserInfo(3L, "user"),
        message = MessageChain.text("hello"),
        messageID = 4L,
    )
}
