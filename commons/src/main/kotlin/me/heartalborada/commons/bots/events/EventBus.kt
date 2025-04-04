package me.heartalborada.commons.bots.events

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class EventBus {
    private val listeners = mutableMapOf<Class<out Event>, MutableList<(Event) -> Unit>>()
    private val scope = CoroutineScope(CoroutineName("EventBusScope"))

    fun <E : Event> register(eventType: Class<E>, listener: (E) -> Unit): () -> Unit {
        val eventListeners = listeners.computeIfAbsent(eventType) { mutableListOf() }
        val wrappedListener: (Event) -> Unit = { event -> listener(eventType.cast(event)!!) }
        eventListeners.add(wrappedListener)
        return {
            eventListeners.remove(wrappedListener)
        }
    }

    fun register(listener: Any) {
        listener::class.java.methods.forEach { method ->
            method.getAnnotation(EventHandler::class.java)?.let {
                val eventType = method.parameterTypes.first() as Class<out Event>
                val eventListener: (Event) -> Unit = { event ->
                    method.invoke(listener, event)
                }
                listeners.computeIfAbsent(eventType) { mutableListOf() }.add(eventListener)
            }
        }
    }

    fun broadcast(event: Event) {
        listeners[event::class.java]?.forEach { listener ->
            scope.launch {
                listener(event)
            }
        }
    }
}