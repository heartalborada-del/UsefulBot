package me.heartalborada.commons.bots.events

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Conventional listener priorities. Custom integer values are also supported. */
object EventPriority {
    const val HIGHEST: Int = 1_000
    const val HIGH: Int = 500
    const val NORMAL: Int = 0
    const val LOW: Int = -500
    const val LOWEST: Int = -1_000
}

/** A listener registration that can be removed safely more than once. */
fun interface EventSubscription : AutoCloseable {
    override fun close()
}

/**
 * Thread-safe, ordered event dispatcher used by bot adapters and plugins.
 *
 * A listener registered for a base event type receives its subclasses too.
 * Dispatch order is deterministic: priority first, then registration order.
 * Listener failures are isolated so one plugin cannot prevent later listeners
 * from receiving an event.
 *
 * [broadcast] is the non-blocking compatibility entry point. Code that needs to
 * wait for all listeners (primarily tests and lifecycle code) should use [publish].
 */
class EventBus(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val listenerErrorHandler: (Event, Throwable) -> Unit = DEFAULT_ERROR_HANDLER,
) : AutoCloseable {
    private data class RegisteredListener(
        val id: Long,
        val eventType: Class<out Event>,
        val priority: Int,
        val receiveIntercepted: Boolean,
        val callback: (Event) -> Unit,
    )

    private val listeners = ConcurrentHashMap<Class<out Event>, CopyOnWriteArrayList<RegisteredListener>>()
    private val registrationSequence = AtomicLong()
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("EventBus"))

    /** Registers a typed listener and returns the handle used to unsubscribe it. */
    fun <E : Event> register(
        eventType: Class<E>,
        priority: Int,
        receiveIntercepted: Boolean,
        listener: (E) -> Unit,
    ): EventSubscription {
        check(!closed.get()) { "The event bus is closed." }
        val registered = RegisteredListener(
            id = registrationSequence.getAndIncrement(),
            eventType = eventType,
            priority = priority,
            receiveIntercepted = receiveIntercepted,
            callback = { event -> listener(eventType.cast(event)) },
        )
        listeners.computeIfAbsent(eventType) { CopyOnWriteArrayList() }.add(registered)
        return subscriptionFor(registered)
    }

    /** Source-compatible shorthand for a normal, interception-aware listener. */
    fun <E : Event> register(eventType: Class<E>, listener: (E) -> Unit): EventSubscription =
        register(eventType, EventPriority.NORMAL, false, listener)

    /** Shorthand for setting only listener priority. */
    fun <E : Event> register(
        eventType: Class<E>,
        priority: Int,
        listener: (E) -> Unit,
    ): EventSubscription = register(eventType, priority, false, listener)

    /**
     * Registers every method annotated with [EventHandler] on [listener].
     * The returned composite subscription removes all discovered methods.
     */
    fun register(listener: Any): EventSubscription {
        val subscriptions = mutableListOf<EventSubscription>()
        try {
            annotatedMethods(listener.javaClass).forEach { method ->
                val annotation = checkNotNull(method.getAnnotation(EventHandler::class.java))
                val parameterType = method.parameterTypes.singleOrNull()
                    ?.takeIf(Event::class.java::isAssignableFrom)
                    ?: throw IllegalArgumentException(
                        "Event handler ${method.declaringClass.name}.${method.name} must have exactly one Event parameter.",
                    )
                require(method.trySetAccessible()) {
                    "Event handler ${method.declaringClass.name}.${method.name} is not accessible."
                }
                @Suppress("UNCHECKED_CAST")
                subscriptions += register(
                    eventType = parameterType as Class<out Event>,
                    priority = annotation.priority,
                    receiveIntercepted = annotation.receiveIntercepted,
                ) { event -> invoke(method, listener, event) }
            }
        } catch (throwable: Throwable) {
            subscriptions.asReversed().forEach(EventSubscription::close)
            throw throwable
        }
        return EventSubscription { subscriptions.asReversed().forEach(EventSubscription::close) }
    }

    /** Schedules ordered delivery and returns the dispatch job. */
    fun broadcast(event: Event): Job {
        check(!closed.get()) { "The event bus is closed." }
        return scope.launch { publish(event) }
    }

    /** Delivers [event] to a stable snapshot of the currently registered listeners. */
    suspend fun publish(event: Event): Event {
        check(!closed.get()) { "The event bus is closed." }
        matchingListeners(event.javaClass).forEach { registered ->
            if (event.isIntercepted && !registered.receiveIntercepted) return@forEach
            try {
                registered.callback(event)
            } catch (throwable: Throwable) {
                val cause = unwrapInvocationException(throwable)
                if (cause is VirtualMachineError || cause is ThreadDeath) throw cause
                runCatching { listenerErrorHandler(event, cause) }
                    .onFailure { LOGGER.error("The event listener error handler failed.", it) }
            }
        }
        return event
    }

    /** Returns the number of active registrations, primarily for diagnostics. */
    fun listenerCount(): Int = listeners.values.sumOf(List<*>::size)

    /** Cancels pending dispatches and removes every listener. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listeners.clear()
        scope.cancel()
    }

    private fun matchingListeners(eventType: Class<out Event>): List<RegisteredListener> =
        listeners.entries
            .asSequence()
            .filter { (registeredType, _) -> registeredType.isAssignableFrom(eventType) }
            .flatMap { it.value.asSequence() }
            .sortedWith(compareByDescending<RegisteredListener> { it.priority }.thenBy { it.id })
            .toList()

    private fun subscriptionFor(registered: RegisteredListener): EventSubscription {
        val removed = AtomicBoolean()
        return EventSubscription {
            if (removed.compareAndSet(false, true)) {
                listeners[registered.eventType]?.let { eventListeners ->
                    eventListeners.remove(registered)
                    if (eventListeners.isEmpty()) listeners.remove(registered.eventType, eventListeners)
                }
            }
        }
    }

    private fun annotatedMethods(type: Class<*>): List<Method> = buildList {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            addAll(current.declaredMethods.filter { it.isAnnotationPresent(EventHandler::class.java) })
            current = current.superclass
        }
    }.distinctBy { method -> method.name to method.parameterTypes.toList() }

    private fun invoke(method: Method, target: Any, event: Event) {
        method.invoke(target, event)
    }

    private fun unwrapInvocationException(throwable: Throwable): Throwable =
        (throwable as? InvocationTargetException)?.targetException ?: throwable

    private companion object {
        val LOGGER = LoggerFactory.getLogger(EventBus::class.java)
        val DEFAULT_ERROR_HANDLER: (Event, Throwable) -> Unit = { event, throwable ->
            LOGGER.error("Event listener failed while handling {}.", event.javaClass.name, throwable)
        }
    }
}
