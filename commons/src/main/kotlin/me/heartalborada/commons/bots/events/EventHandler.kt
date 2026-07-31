package me.heartalborada.commons.bots.events

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
/**
 * Marks a single-argument method as an event listener.
 *
 * Higher priority listeners run first. [receiveIntercepted] should only be used
 * for observers such as auditing and metrics because ordinary handlers are
 * expected to respect interception.
 */
annotation class EventHandler(
    val priority: Int = EventPriority.NORMAL,
    val receiveIntercepted: Boolean = false,
)
