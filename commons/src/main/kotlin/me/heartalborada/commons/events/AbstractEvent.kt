package me.heartalborada.commons.events

abstract class AbstractEvent : Event {
    @Volatile
    private var _intercepted: Boolean = false

    override val isIntercepted: Boolean
        get() = _intercepted

    override fun intercept() {
        _intercepted = true
    }
}